package com.example.payment.infrastructure.outbox

import com.example.contracts.messaging.PaymentEventMessage
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
 * Outbox relay for payment.events.
 *
 * Fast path: triggered AFTER_COMMIT via OutboxFlushSignal — publishes immediately.
 * Safety net: @Scheduled every 30s catches anything the fast path missed (e.g. crash
 *             between DB commit and Solace send).
 *
 * markPublished uses WHERE published_at IS NULL to guard against concurrent relay runs.
 *
 * Plain JMS sends have no built-in client-side timeout the way Kafka's Future did, so the
 * send is wrapped in a CompletableFuture with an explicit 10s bounded wait — preserving the
 * original "timeout, log, let the next sweep retry" behavior.
 *
 * The wait runs on a dedicated executor rather than CompletableFuture's default
 * ForkJoinPool.commonPool(): that pool's worker threads are JDK "innocuous" threads
 * with no (or a restricted) context classloader, which breaks Solace JCSMP's
 * connection setup (ArrayIndexOutOfBoundsException deep in its Netty transport init)
 * the first time a send lands on one of them.
 */
@Component
class PaymentOutboxRelay(
    private val outboxRepository: OutboxRepository,
    private val jmsTemplate: JmsTemplate,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(PaymentOutboxRelay::class.java)

    private val sendExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "payment-outbox-send").apply { isDaemon = true }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFlushSignal(signal: OutboxFlushSignal) {
        publishPending()
    }

    @Scheduled(fixedDelay = 30_000)
    fun publishPending() {
        val pending = outboxRepository.findUnpublished()
        if (pending.isEmpty()) return

        log.debug("Outbox relay: {} pending payment event(s)", pending.size)
        for (entry in pending) {
            val id = entry.entryId ?: continue
            try {
                val message = objectMapper.readValue(entry.payload, PaymentEventMessage::class.java)
                CompletableFuture.supplyAsync({
                    jmsTemplate.convertAndSend(entry.topic, message)
                }, sendExecutor).get(10, TimeUnit.SECONDS)
                val updated = outboxRepository.markPublished(id, Instant.now())
                if (updated > 0) {
                    log.debug("Published {} for order {}", entry.eventType, entry.aggregateId)
                }
            } catch (e: Exception) {
                log.error("Outbox relay failed for entry {} ({}): {}", id, entry.eventType, e.message)
            }
        }
    }
}
