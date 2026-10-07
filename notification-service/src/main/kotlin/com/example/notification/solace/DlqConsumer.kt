package com.example.notification.solace

import jakarta.jms.Message
import jakarta.jms.TextMessage
import org.slf4j.LoggerFactory
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component

/**
 * DlqConsumer — reads messages that exhausted retries (or failed to parse) from the
 * per-topic Dead Message Queues provisioned by MessagingConfig.
 *
 * Solace-era analog of the original Kafka DlqConsumer, which read order.events.DLT /
 * payment.events.DLT (Spring Kafka's auto-generated DeadLetterPublishingRecoverer
 * topics). DlqPublisher is the producer side of this hand-off — see its class doc for
 * why routing here is done explicitly at the application layer rather than relying on
 * Solace's single, VPN-wide system DMQ.
 *
 * Current behaviour: logs at ERROR level for alerting/investigation.
 * Production extension: write to an incidents table, send a PagerDuty/Slack alert,
 * or re-queue to a manual-review topic.
 */
@Component
class DlqConsumer {

    companion object {
        private val log = LoggerFactory.getLogger(DlqConsumer::class.java)
    }

    @JmsListener(
        destination = SolaceDestinations.ORDER_EVENTS_DMQ,
        containerFactory = "dmqListenerContainerFactory",
    )
    fun onOrderEventDeadMessage(message: Message) = logDeadMessage(message)

    @JmsListener(
        destination = SolaceDestinations.PAYMENT_EVENTS_DMQ,
        containerFactory = "dmqListenerContainerFactory",
    )
    fun onPaymentEventDeadMessage(message: Message) = logDeadMessage(message)

    private fun logDeadMessage(message: Message) {
        // Defensive: DLQ messages must never recurse into another DLQ, so every
        // property/body read is best-effort and this method never throws — a single
        // attempt only, log and move on (mirrors the original's dlqListenerContainerFactory
        // with a no-retry FixedBackOff(0L, 0L)).
        try {
            val originalTopic = stringProperty(message, "dmqOriginalTopic")
            val exceptionClass = stringProperty(message, "dmqExceptionClass")
            val exceptionMessage = stringProperty(message, "dmqExceptionMessage")
            val redeliveryCount = runCatching { message.getIntProperty("JMSXDeliveryCount") }.getOrDefault(-1)
            val body = (message as? TextMessage)?.text ?: "n/a"

            log.error(
                "DLQ message received — action required! original-topic={}, jms-message-id={}, " +
                    "redelivery-count={}, exception={}: {} — body={}",
                originalTopic, message.jmsMessageID, redeliveryCount, exceptionClass, exceptionMessage, body,
            )
        } catch (e: Exception) {
            log.error("DLQ message received — action required! (failed to read message details)", e)
        }
    }

    private fun stringProperty(message: Message, name: String): String =
        runCatching { message.getStringProperty(name) }.getOrNull() ?: "n/a"
}
