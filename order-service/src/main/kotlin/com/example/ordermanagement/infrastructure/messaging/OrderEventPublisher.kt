package com.example.ordermanagement.infrastructure.messaging

import com.example.contracts.messaging.OrderEventMessage
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.event.InventoryReleasedEvent
import com.example.ordermanagement.domain.event.InventoryReservationFailedEvent
import com.example.ordermanagement.domain.event.InventoryReservedEvent
import com.example.ordermanagement.domain.event.ItemAddedEvent
import com.example.ordermanagement.domain.event.ItemRemovedEvent
import com.example.ordermanagement.domain.event.OrderCancelledEvent
import com.example.ordermanagement.domain.event.OrderConfirmedEvent
import com.example.ordermanagement.domain.event.OrderCreatedEvent
import com.example.ordermanagement.domain.event.PaymentCompletedEvent
import com.example.ordermanagement.domain.event.PaymentFailedEvent
import com.example.ordermanagement.domain.event.RefundCompletedEvent
import com.example.ordermanagement.domain.event.ShipmentCreatedEvent
import com.example.ordermanagement.domain.event.ShipmentDeliveredEvent
import org.slf4j.LoggerFactory
import org.springframework.jms.core.JmsTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * OrderEventPublisher — Outbox Pattern Implementation
 *
 * OUTBOX PATTERN EXPLAINED:
 * 1. Domain event is persisted to event_store (inside DB transaction)
 * 2. Spring's TransactionSynchronizationManager queues a post-commit callback
 * 3. AFTER the DB transaction commits, this method is called
 * 4. We publish to Solace (via JMS Topic)
 *
 * WHY THIS MATTERS:
 * If we published to Solace INSIDE the transaction and then the DB rolled back,
 * consumers would receive an event for a change that never happened — a phantom event.
 *
 * If we published AFTER commit but crashed before publishing, the event is lost.
 * For full guarantee in production, use Debezium CDC (Change Data Capture) to
 * stream from the event_store table directly to Solace.
 * For this PoC, AFTER_COMMIT is sufficient and demonstrates the pattern.
 *
 * ORDERING NOTE:
 * Kafka's message-key-based partition routing (orderId → partition) has no direct
 * JMS Topic equivalent, so per-order ordering across consumers is not guaranteed
 * here — this is an accepted gap for this pass (see architecture.md Solace Rules).
 */
@Component
class OrderEventPublisher(
    private val jmsTemplate: JmsTemplate,
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderEventPublisher::class.java)

        const val TOPIC = "order.events"
    }

    /**
     * Called AFTER the DB transaction commits.
     * Spring publishes a DomainEvent as an application event from OrderRepositoryAdapter.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onDomainEvent(domainEvent: DomainEvent) {
        val message = toMessage(domainEvent) ?: return // Not all events need Solace publication

        log.debug("Publishing {} to Solace for order {}", domainEvent.eventType(), domainEvent.aggregateId)

        try {
            jmsTemplate.convertAndSend(TOPIC, message)
            log.debug("Published {} to Solace for order {}", domainEvent.eventType(), domainEvent.aggregateId)
        } catch (e: Exception) {
            // The AFTER_COMMIT listener cannot roll back the DB transaction at this point,
            // so this is a known at-most-once gap (use Debezium CDC for at-least-once
            // production guarantees).
            log.error(
                "Failed to publish {} to Solace for order {} — event lost",
                domainEvent.eventType(), domainEvent.aggregateId, e,
            )
        }
    }

    private fun toMessage(event: DomainEvent): OrderEventMessage? = when (event) {
        is OrderCreatedEvent -> OrderEventMessage.OrderCreatedMessage(
            event.eventId, event.aggregateId, event.customerId.toString(),
            event.shippingAddress, event.occurredAt,
        )

        is OrderConfirmedEvent -> OrderEventMessage.OrderConfirmedMessage(
            event.eventId, event.aggregateId,
            // OrderConfirmedEvent does not carry customerId; the Kafka-era message
            // shape passed null here, but the Solace-era contract field is non-nullable.
            "",
            event.totalAmount.amount.toDouble(), event.workflowId, event.occurredAt,
        )

        is OrderCancelledEvent -> OrderEventMessage.OrderCancelledMessage(
            event.eventId, event.aggregateId, event.cancellationReason,
            event.cancelledBy, event.occurredAt,
        )

        is PaymentCompletedEvent -> OrderEventMessage.PaymentCompletedMessage(
            event.eventId, event.aggregateId, event.transactionId,
            event.amountCharged.amount.toDouble(), event.occurredAt,
        )

        is PaymentFailedEvent -> OrderEventMessage.PaymentFailedMessage(
            event.eventId, event.aggregateId, event.reason, event.retryable, event.occurredAt,
        )

        is ShipmentCreatedEvent -> OrderEventMessage.ShipmentCreatedMessage(
            event.eventId, event.aggregateId, event.shipmentId,
            event.trackingNumber, event.carrier, event.occurredAt,
        )

        is ShipmentDeliveredEvent -> OrderEventMessage.ShipmentDeliveredMessage(
            event.eventId, event.aggregateId, event.shipmentId,
            event.deliveredAt, event.occurredAt,
        )

        is InventoryReservedEvent -> OrderEventMessage.InventoryReservedMessage(
            event.eventId, event.aggregateId, event.reservationId, event.occurredAt,
        )

        is InventoryReleasedEvent -> OrderEventMessage.InventoryReleasedMessage(
            event.eventId, event.aggregateId,
            // Domain event models reservationId as nullable; the message contract does not.
            event.reservationId ?: "", event.reason, event.occurredAt,
        )

        is ItemAddedEvent -> OrderEventMessage.ItemAddedMessage(
            event.eventId, event.aggregateId, event.occurredAt,
        )

        is ItemRemovedEvent -> OrderEventMessage.ItemRemovedMessage(
            event.eventId, event.aggregateId, event.occurredAt,
        )

        is RefundCompletedEvent -> OrderEventMessage.RefundCompletedMessage(
            event.eventId, event.aggregateId, event.occurredAt,
        )

        is InventoryReservationFailedEvent -> null
    }
}
