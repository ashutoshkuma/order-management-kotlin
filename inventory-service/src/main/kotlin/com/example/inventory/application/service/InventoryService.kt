package com.example.inventory.application.service

import com.example.contracts.api.InventoryContracts
import com.example.contracts.messaging.InventoryEventMessage
import com.example.inventory.domain.model.InsufficientStockException
import com.example.inventory.domain.model.InventoryItem
import com.example.inventory.domain.model.Reservation
import com.example.inventory.infrastructure.outbox.OutboxEntry
import com.example.inventory.infrastructure.outbox.OutboxFlushSignal
import com.example.inventory.infrastructure.outbox.OutboxRepository
import com.example.inventory.infrastructure.persistence.InventoryItemRepository
import com.example.inventory.infrastructure.persistence.ReservationRepository
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.NoSuchElementException
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Service
class InventoryService(
    private val reservationRepository: ReservationRepository,
    private val inventoryItemRepository: InventoryItemRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @field:Value("\${simulation.inventory-failure-rate:0.0}")
    private var failureRate: Double = 0.0

    @Transactional
    fun reserve(request: InventoryContracts.ReserveInventoryRequest): InventoryContracts.ReserveInventoryResponse {
        val existing = reservationRepository.findByOrderId(request.orderId)
        if (existing.isPresent) {
            val r = existing.get()
            return InventoryContracts.ReserveInventoryResponse(
                r.reservationId, "RESERVED", "Already reserved (idempotent)")
        }

        simulateFailure(request.orderId)

        val items = request.items

        for (lineItem in items) {
            val item = inventoryItemRepository.findById(lineItem.productId.toString())
                .orElseThrow { InsufficientStockException("Unknown product: ${lineItem.productId}") }
            inventoryItemRepository.save(item.reserve(lineItem.quantity))
        }

        val itemsJson = serializeItems(items)
        val reservation = Reservation.create(request.orderId, itemsJson)
        reservationRepository.save(reservation)

        writeOutbox(
            InventoryEventMessage.InventoryReservedMessage(
                UUID.randomUUID(), request.orderId, reservation.reservationId, Instant.now()),
            "InventoryReserved", "inventory.events")

        return InventoryContracts.ReserveInventoryResponse(
            reservation.reservationId, "RESERVED", "Inventory reserved successfully")
    }

    @Transactional
    fun release(reservationId: String): InventoryContracts.ReleaseInventoryResponse {
        reservationRepository.findById(reservationId).ifPresent { reservation ->
            val items = deserializeItems(reservation.itemsJson)
            for (lineItem in items) {
                inventoryItemRepository.findById(lineItem.productId)
                    .ifPresent { item -> inventoryItemRepository.save(item.release(lineItem.quantity)) }
            }
            reservationRepository.save(reservation.release())

            writeOutbox(
                InventoryEventMessage.InventoryReleasedMessage(
                    UUID.randomUUID(), reservation.orderId, reservationId, "Saga compensation", Instant.now()),
                "InventoryReleased", "inventory.events")
        }

        return InventoryContracts.ReleaseInventoryResponse(reservationId, "RELEASED")
    }

    // ─── CRUD operations ───────────────────────────────────────────────

    fun getAllItems(): List<InventoryItem> {
        val result = ArrayList<InventoryItem>()
        inventoryItemRepository.findAll().forEach { result.add(it) }
        return result
    }

    fun getItem(productId: String): InventoryItem =
        inventoryItemRepository.findById(productId)
            .orElseThrow { NoSuchElementException("Product not found: $productId") }

    @Transactional
    fun createItem(productId: String, productName: String, quantity: Int): InventoryItem {
        if (inventoryItemRepository.existsById(productId)) {
            throw IllegalStateException("Product already exists: $productId")
        }
        return inventoryItemRepository.save(InventoryItem.create(productId, productName, quantity))
    }

    @Transactional
    fun updateItem(productId: String, productName: String?, quantityAvailable: Int?): InventoryItem {
        val existing = inventoryItemRepository.findById(productId)
            .orElseThrow { NoSuchElementException("Product not found: $productId") }
        val newName = productName ?: existing.productName
        val newQty = quantityAvailable ?: existing.quantityAvailable
        return inventoryItemRepository.save(
            InventoryItem(productId, newName, newQty, existing.quantityReserved, Instant.now()))
    }

    @Transactional
    fun deleteItem(productId: String) {
        if (!inventoryItemRepository.existsById(productId)) {
            throw NoSuchElementException("Product not found: $productId")
        }
        inventoryItemRepository.deleteById(productId)
    }

    // ───────────────────────────────────────────────────────────────────

    private fun writeOutbox(message: InventoryEventMessage, eventType: String, topic: String) {
        try {
            val payload = objectMapper.writeValueAsString(message)
            outboxRepository.save(OutboxEntry.create(message.eventId, message.orderId, eventType, topic, payload))
            eventPublisher.publishEvent(OutboxFlushSignal())
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to serialize outbox event", e)
        }
    }

    private fun simulateFailure(orderId: String) {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw RuntimeException("Inventory service unavailable (simulated, rate=$failureRate)")
        }
    }

    private fun serializeItems(items: List<InventoryContracts.ReserveInventoryRequest.LineItem>): String =
        try {
            val records = items.map { LineItemRecord(it.productId.toString(), it.quantity) }
            objectMapper.writeValueAsString(records)
        } catch (e: JsonProcessingException) {
            "[]"
        }

    private fun deserializeItems(json: String?): List<LineItemRecord> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            objectMapper.readValue(json, object : TypeReference<List<LineItemRecord>>() {})
        } catch (e: JsonProcessingException) {
            emptyList()
        }
    }

    data class LineItemRecord(val productId: String, val quantity: Int)
}
