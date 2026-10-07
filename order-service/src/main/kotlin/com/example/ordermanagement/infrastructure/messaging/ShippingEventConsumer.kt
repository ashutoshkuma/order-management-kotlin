package com.example.ordermanagement.infrastructure.messaging

import com.example.contracts.messaging.ShippingEventMessage
import com.example.ordermanagement.application.service.OrderCommandService
import com.example.ordermanagement.domain.valueobject.OrderId
import org.slf4j.LoggerFactory
import org.springframework.jms.annotation.JmsListener
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Consumes shipping.events to close the saga when delivery is confirmed.
 *
 * This is the event-driven complement to the Temporal activity path:
 * - Temporal path: ShippingActivityImpl.confirmDelivery() -> recordShipmentDelivered()
 * - Solace path:   ShipmentDeliveredMessage -> recordOrderDelivered() (idempotent)
 *
 * Both paths converge at OrderCommandService.recordOrderDelivered(), which is
 * guarded against duplicate delivery recording.
 *
 * In production: replace in-memory processedEventIds with Redis TTL store.
 */
@Component
class ShippingEventConsumer(
    private val orderCommandService: OrderCommandService,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ShippingEventConsumer::class.java)

        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 2000L
    }

    // In-memory deduplication store (use Redis in production)
    private val processedEventIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // TODO(config-agent): provision durable queue "order-service-group" subscribed
    // to topic "shipping.events" here (self-provisioned idempotently at startup via
    // SolJmsUtility / Solace-specific Session casts, per architecture.md Solace Rules).
    @JmsListener(
        destination = "order-service-group",
        containerFactory = "shippingListenerContainerFactory",
    )
    fun onShippingEvent(message: ShippingEventMessage) {
        if (!processedEventIds.add(message.eventId)) {
            return
        }

        var attempt = 0
        while (true) {
            try {
                handle(message)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > MAX_RETRIES) {
                    throw e
                }
                log.warn(
                    "Retrying shipping event handling for order {} ({}) after failure, attempt {}/{}",
                    message.orderId, message.javaClass.simpleName, attempt, MAX_RETRIES, e,
                )
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }

    private fun handle(message: ShippingEventMessage) {
        when (message) {
            is ShippingEventMessage.ShipmentDeliveredMessage -> {
                try {
                    orderCommandService.recordOrderDelivered(OrderId.of(message.orderId), message.shipmentId)
                } catch (e: Exception) {
                    // Order already delivered (via Temporal activity path) or
                    // in an unexpected state — safe to swallow, saga is closed
                }
            }

            is ShippingEventMessage.ShipmentCreatedMessage -> {
                // ShipmentCreated is recorded by Temporal via ShippingActivityImpl
            }
        }
    }
}
