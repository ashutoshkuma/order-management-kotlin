package com.example.notification.solace

import com.example.contracts.messaging.PaymentEventMessage
import com.example.notification.service.EventIdempotencyService
import com.example.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * PaymentEventConsumer — idempotent handler for payment.events messages delivered via
 * the "notification-service-group" durable queue.
 *
 * Handles direct payment events from payment-service, most importantly
 * PaymentRefundedMessage which is not republished on order.events.
 * Deduplicates by eventId using Redis (TTL=24h) via EventIdempotencyService.
 *
 * Not a @JmsListener itself — see NotificationQueueListener.
 *
 * DLQ hybrid: 2 retries, 2s apart. On exhaustion, publishes explicitly to
 * payment.events.dmq via DlqPublisher instead of rethrowing.
 */
@Component
class PaymentEventConsumer(
    private val notificationService: NotificationService,
    private val idempotencyService: EventIdempotencyService,
    private val dlqPublisher: DlqPublisher,
) {

    companion object {
        private val log = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    fun handle(message: PaymentEventMessage) {
        if (!idempotencyService.isFirstOccurrence(message.eventId)) {
            return
        }

        var attempt = 0
        while (true) {
            try {
                dispatch(message)
                return
            } catch (e: Exception) {
                // Revert the idempotency mark so a retry attempt is not skipped.
                idempotencyService.deleteOccurrence(message.eventId)
                attempt++
                if (attempt > MAX_RETRIES) {
                    log.error(
                        "Failed to process {} for event {} after {} retries — routing to DMQ",
                        message.javaClass.simpleName, message.eventId, MAX_RETRIES, e,
                    )
                    dlqPublisher.publish(
                        SolaceDestinations.PAYMENT_EVENTS_DMQ,
                        SolaceDestinations.PAYMENT_EVENTS_TOPIC,
                        message.eventId,
                        message,
                        e,
                    )
                    return
                }
                log.warn(
                    "Failed to process {} for event {} — reverting idempotency mark, retrying ({}/{})",
                    message.javaClass.simpleName, message.eventId, attempt, MAX_RETRIES, e,
                )
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun dispatch(message: PaymentEventMessage) {
        when (message) {
            is PaymentEventMessage.PaymentChargedMessage -> notificationService.notifyPaymentCharged(message)
            is PaymentEventMessage.PaymentFailedMessage -> notificationService.notifyPaymentChargeFailed(message)
            is PaymentEventMessage.PaymentRefundedMessage -> notificationService.notifyPaymentRefunded(message)
        }
    }
}
