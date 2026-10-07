package com.example.shipping.infrastructure.outbox

import com.example.contracts.messaging.ShippingEventMessage
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
 * Outbox relay for shipping.events.
 *
 * Fast path: triggered AFTER_COMMIT via OutboxFlushSignal — publishes immediately.
 * Safety net: @Scheduled every 30s catches anything the fast path missed (e.g. crash
 *             between DB commit and Solace send).
 *
 * markPublished uses WHERE published_at IS NULL to guard against concurrent relay runs.
 *
 * Publishing goes through Solace JMS's JmsTemplate (Topic: shipping.events). The 10s
 * bounded wait that Kafka's Future.get(10, SECONDS) used to provide is reproduced here
 * with CompletableFuture.supplyAsync, since JmsTemplate.convertAndSend() is itself
 * synchronous — this preserves "log and retry on the next sweep" behavior on timeout.
 *
 * The wait runs on a dedicated executor rather than CompletableFuture's default
 * ForkJoinPool.commonPool(): that pool's worker threads are JDK "innocuous" threads
 * with no (or a restricted) context classloader, which breaks Solace JCSMP's
 * connection setup (ArrayIndexOutOfBoundsException deep in its Netty transport init)
 * the first time a send lands on one of them.
 */
@Component
class ShippingOutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val jmsTemplate: JmsTemplate,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ShippingOutboxRelay::class.java)
    }

    private val sendExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "shipping-outbox-send").apply { isDaemon = true }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFlushSignal(signal: OutboxFlushSignal) {
        publishPending()
    }

    @Scheduled(fixedDelay = 30_000)
    fun publishPending() {
        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) return

        log.debug("Outbox relay: {} pending shipping event(s)", pending.size)
        for (entry in pending) {
            try {
                val message = objectMapper.readValue(entry.payload, ShippingEventMessage::class.java)
                CompletableFuture.supplyAsync({
                    jmsTemplate.convertAndSend(entry.topic, message)
                }, sendExecutor).get(10, TimeUnit.SECONDS)

                val updated = outboxRepository.markPublished(entry.getId()!!, Instant.now())
                if (updated > 0) {
                    log.debug("Published {} for order {}", entry.eventType, entry.aggregateId)
                }
            } catch (e: Exception) {
                log.error(
                    "Outbox relay failed for entry {} ({}): {}",
                    entry.getId(), entry.eventType, e.message,
                )
            }
        }
    }
}
