package com.example.inventory.infrastructure.outbox

import com.example.contracts.messaging.InventoryEventMessage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jms.core.JmsTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Outbox relay for inventory.events.
 *
 * Fast path: triggered AFTER_COMMIT via OutboxFlushSignal — publishes immediately.
 * Safety net: @Scheduled every 30s catches anything the fast path missed (e.g. crash
 *             between DB commit and Solace publish).
 *
 * markPublished uses WHERE published_at IS NULL to guard against concurrent relay runs.
 *
 * The bounded-wait send runs on a dedicated executor rather than
 * CompletableFuture's default ForkJoinPool.commonPool(): that pool's worker
 * threads are JDK "innocuous" threads with no (or a restricted) context
 * classloader, which breaks Solace JCSMP's connection setup (it throws
 * ArrayIndexOutOfBoundsException deep in its Netty transport init) the first
 * time a JMS send happens to land on one of them.
 */
@Component
class InventoryOutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val jmsTemplate: JmsTemplate,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(InventoryOutboxRelay::class.java)

    private val sendExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "inventory-outbox-send").apply { isDaemon = true }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFlushSignal(signal: OutboxFlushSignal) {
        publishPending()
    }

    @Scheduled(fixedDelay = 30_000)
    fun publishPending() {
        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) return

        log.debug("Outbox relay: {} pending inventory event(s)", pending.size)
        for (entry in pending) {
            try {
                val message = objectMapper.readValue(entry.payload, InventoryEventMessage::class.java)
                CompletableFuture.supplyAsync({
                    jmsTemplate.convertAndSend(entry.topic, message)
                }, sendExecutor).get(10, TimeUnit.SECONDS)
                val updated = outboxRepository.markPublished(entry.entryId!!, Instant.now())
                if (updated > 0) {
                    log.debug("Published {} for order {}", entry.eventType, entry.aggregateId)
                }
            } catch (e: Exception) {
                log.error("Outbox relay failed for entry {} ({}): {}", entry.entryId, entry.eventType, e.message)
            }
        }
    }
}
