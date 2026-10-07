package com.example.ordermanagement.infrastructure.temporal.activity

import io.temporal.spring.boot.ActivityImpl
import org.springframework.stereotype.Component

/**
 * NotificationActivityImpl — no-op notification bridge
 *
 * WHAT CHANGED FROM MONOLITH:
 * Before: System.out.println() statements only.
 * Then:   Published an OrderEventMessage to Kafka (notification-service
 *         consumed from order.events), but every method here was already a
 *         no-op because OrderEventKafkaPublisher published the equivalent
 *         message directly when the domain event was persisted.
 * Now:    The order.events publishing path is migrating from Kafka to
 *         Solace PubSub+ elsewhere in the codebase (infrastructure/messaging,
 *         via OrderEventPublisher — outside this class's scope). This
 *         activity never actually sent anything itself — it held an unused
 *         KafkaTemplate purely for a no-op implementation — so there is
 *         nothing here to port to Solace; the dead dependency is simply
 *         removed.
 *
 * Notifications are BEST-EFFORT:
 * - Activity has maxAttempts=2 (not 5 or 10)
 * - Failures do NOT abort the workflow
 * - A notification failing to publish doesn't cancel a delivered order
 */
@Component
@ActivityImpl(taskQueues = ["ORDER_FULFILLMENT_QUEUE"])
class NotificationActivityImpl : NotificationActivity {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(NotificationActivityImpl::class.java)
    }

    override fun sendOrderConfirmedNotification(orderId: String) {
        log.debug("sendOrderConfirmedNotification: order={} — no-op, OrderEventPublisher already published", orderId)
        // The OrderConfirmedMessage is already published by OrderEventPublisher
        // when the OrderConfirmedEvent is persisted. This is a no-op to avoid duplication.
    }

    override fun sendOrderShippedNotification(orderId: String, trackingNumber: String) {
        // ShipmentCreatedMessage already published via OrderEventPublisher
        log.debug("sendOrderShippedNotification: order={} tracking={} — no-op, already published via OrderEventPublisher", orderId, trackingNumber)
    }

    override fun sendOrderDeliveredNotification(orderId: String) {
        // ShipmentDeliveredMessage already published via OrderEventPublisher
        log.debug("sendOrderDeliveredNotification: order={} — no-op, already published via OrderEventPublisher", orderId)
    }

    override fun sendOrderCancelledNotification(orderId: String, reason: String) {
        // OrderCancelledMessage already published via OrderEventPublisher
        log.debug("sendOrderCancelledNotification: order={} reason={} — no-op, already published via OrderEventPublisher", orderId, reason)
    }

    override fun sendPaymentFailedNotification(orderId: String, reason: String) {
        // PaymentFailedMessage already published via OrderEventPublisher
        log.debug("sendPaymentFailedNotification: order={} reason={} — no-op, already published via OrderEventPublisher", orderId, reason)
    }
}
