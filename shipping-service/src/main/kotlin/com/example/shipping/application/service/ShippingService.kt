package com.example.shipping.application.service

import com.example.contracts.api.ShippingContracts
import com.example.contracts.messaging.ShippingEventMessage
import com.example.shipping.domain.model.Delivery
import com.example.shipping.domain.model.Shipment
import com.example.shipping.infrastructure.outbox.OutboxEntry
import com.example.shipping.infrastructure.outbox.OutboxFlushSignal
import com.example.shipping.infrastructure.outbox.OutboxRepository
import com.example.shipping.infrastructure.persistence.DeliveryRepository
import com.example.shipping.infrastructure.persistence.ShipmentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Service
class ShippingService(
    private val shipmentRepository: ShipmentRepository,
    private val deliveryRepository: DeliveryRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Value("\${simulation.shipping-failure-rate:0.0}")
    private var failureRate: Double = 0.0

    @Transactional
    fun createShipment(request: ShippingContracts.CreateShipmentRequest): ShippingContracts.CreateShipmentResponse {
        val existing = shipmentRepository.findByOrderId(request.orderId)
        if (existing.isPresent) {
            val s = existing.get()
            return ShippingContracts.CreateShipmentResponse(
                s.shipmentId, s.trackingNumber, s.carrier, s.status,
            )
        }

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw RuntimeException("Carrier service unavailable (simulated)")
        }

        val tracking = "1Z" + UUID.randomUUID().toString().replace("-", "")
            .substring(0, 16).uppercase()
        val carrier = if (ThreadLocalRandom.current().nextBoolean()) "UPS" else "FedEx"

        val shipment = Shipment.create(request.orderId, tracking, carrier)
        shipmentRepository.save(shipment)

        writeOutbox(
            ShippingEventMessage.ShipmentCreatedMessage(
                UUID.randomUUID(), request.orderId,
                shipment.shipmentId, tracking, carrier, Instant.now(),
            ),
            "ShipmentCreated", "shipping.events",
        )

        return ShippingContracts.CreateShipmentResponse(
            shipment.shipmentId, tracking, carrier, "CREATED",
        )
    }

    fun getShipment(shipmentId: String): ShippingContracts.ShipmentStatusResponse {
        val s = shipmentRepository.findById(shipmentId)
            .orElseThrow { RuntimeException("Shipment not found: $shipmentId") }
        return ShippingContracts.ShipmentStatusResponse(
            s.shipmentId, s.status, s.trackingNumber, s.carrier,
        )
    }

    @Transactional
    fun confirmDelivery(shipmentId: String): ShippingContracts.ConfirmDeliveryResponse {
        val shipment = shipmentRepository.findById(shipmentId)
            .orElseThrow { RuntimeException("Shipment not found: $shipmentId") }

        val existing = deliveryRepository.findByShipmentId(shipmentId)
        if (existing.isPresent) {
            return ShippingContracts.ConfirmDeliveryResponse(shipmentId, "DELIVERED")
        }

        val delivered = shipment.markDelivered()
        shipmentRepository.save(delivered)
        deliveryRepository.save(Delivery.create(shipmentId, shipment.orderId))

        writeOutbox(
            ShippingEventMessage.ShipmentDeliveredMessage(
                UUID.randomUUID(), shipment.orderId,
                shipmentId, Instant.now(), Instant.now(),
            ),
            "ShipmentDelivered", "shipping.events",
        )

        return ShippingContracts.ConfirmDeliveryResponse(shipmentId, "DELIVERED")
    }

    // ───────────────────────────────────────────────────────────────────

    private fun writeOutbox(message: ShippingEventMessage, eventType: String, topic: String) {
        try {
            val payload = objectMapper.writeValueAsString(message)
            outboxRepository.save(
                OutboxEntry.create(message.eventId, message.orderId, eventType, topic, payload),
            )
            eventPublisher.publishEvent(OutboxFlushSignal())
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            throw RuntimeException("Failed to serialize outbox event", e)
        }
    }
}
