package com.example.shipping.infrastructure.persistence

import com.example.shipping.domain.model.Shipment
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Spring Data JDBC Repository for Shipment persistence.
 */
@Repository
interface ShipmentRepository : CrudRepository<Shipment, String> {
    /**
     * Find the shipment for an order.
     * Idempotency: if already shipped, return existing.
     */
    fun findByOrderId(orderId: String): Optional<Shipment>

    /**
     * Find all shipments for an order.
     */
    fun findAllByOrderId(orderId: String): List<Shipment>
}
