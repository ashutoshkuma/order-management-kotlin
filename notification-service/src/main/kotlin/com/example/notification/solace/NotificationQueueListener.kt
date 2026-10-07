package com.example.notification.solace

import com.example.contracts.messaging.OrderEventMessage
import com.example.contracts.messaging.PaymentEventMessage
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.jms.Message
import jakarta.jms.TextMessage
import jakarta.jms.Topic
import org.slf4j.LoggerFactory
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component

/**
 * The single physical Solace consumer bound to the "notification-service-group"
 * durable queue.
 *
 * WHY ONE LISTENER INSTEAD OF TWO:
 * The Kafka-era design had one consumer GROUP subscribed to two TOPICS (order.events,
 * payment.events) via two separate @KafkaListener methods; Kafka itself guarantees
 * each message is routed to the correct method because each method is bound to a
 * specific topic.
 *
 * Solace's equivalent primitive is one durable QUEUE with two topic subscriptions
 * (architecture.md "Solace Rules"), but a queue has no notion of "topic-typed"
 * sub-listeners: every bound consumer thread — regardless of which Java method or
 * class registered it — competes for every message on the queue. Two independent
 * @JmsListener methods on the same destination would each receive an unpredictable
 * mix of order and payment messages. Forcing whichever listener gets the "wrong" type
 * to reject/rethrow just to get another shot at the right one would burn through the
 * queue's maxMsgRedelivery budget on perfectly valid messages, and could still land on
 * the wrong listener again — a starvation/data-loss risk, not a faithful translation
 * of the Kafka behaviour.
 *
 * So there is exactly one @JmsListener bound to the queue. Solace's JMS client
 * populates JMSDestination with the ORIGINAL publish-time Topic even for messages
 * delivered via a Queue's topic subscription, so that header reliably tells the two
 * message families apart without needing to touch the (out-of-scope) producers.
 * From there, this class parses the body and delegates to the topic-specific
 * consumer, each of which keeps its own idempotency + retry + DMQ handling — mirroring
 * the original per-topic Kafka consumers.
 *
 * A body that fails to parse (or a non-text message) is the Solace-era analog of
 * Kafka's DeserializationException: no in-app retry is attempted — it's published
 * straight to the origin topic's DMQ.
 */
@Component
class NotificationQueueListener(
    private val orderEventConsumer: OrderEventConsumer,
    private val paymentEventConsumer: PaymentEventConsumer,
    private val dlqPublisher: DlqPublisher,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(NotificationQueueListener::class.java)
    }

    @JmsListener(
        destination = SolaceDestinations.NOTIFICATION_QUEUE,
        containerFactory = "notificationListenerContainerFactory",
    )
    fun onMessage(message: Message) {
        val originTopic = originTopic(message)
        val body = (message as? TextMessage)?.text

        if (body == null) {
            log.error("Received a non-text message on {} (origin-topic={}) — cannot parse, dropping", SolaceDestinations.NOTIFICATION_QUEUE, originTopic)
            return
        }

        when (originTopic) {
            SolaceDestinations.ORDER_EVENTS_TOPIC -> parseAndHandle(body, originTopic) {
                orderEventConsumer.handle(objectMapper.readValue(body, OrderEventMessage::class.java))
            }

            SolaceDestinations.PAYMENT_EVENTS_TOPIC -> parseAndHandle(body, originTopic) {
                paymentEventConsumer.handle(objectMapper.readValue(body, PaymentEventMessage::class.java))
            }

            else -> {
                log.error(
                    "Received a message on {} from an unrecognized origin topic '{}' — dropping",
                    SolaceDestinations.NOTIFICATION_QUEUE, originTopic,
                )
            }
        }
    }

    private inline fun parseAndHandle(body: String, originTopic: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // In practice this only fires for objectMapper.readValue() failures —
            // OrderEventConsumer/PaymentEventConsumer already run their own retry loop
            // and never rethrow (see DlqPublisher). A malformed body is the Solace-era
            // analog of Kafka's DeserializationException: no in-app retry, straight to
            // the origin topic's DMQ.
            log.error("Failed to handle message from {} — treating as non-retryable, routing to DMQ", originTopic, e)
            val dmq = if (originTopic == SolaceDestinations.ORDER_EVENTS_TOPIC) {
                SolaceDestinations.ORDER_EVENTS_DMQ
            } else {
                SolaceDestinations.PAYMENT_EVENTS_DMQ
            }
            dlqPublisher.publishRaw(dmq, originTopic, body, e)
        }
    }

    private fun originTopic(message: Message): String? =
        (message.jmsDestination as? Topic)?.topicName
}
