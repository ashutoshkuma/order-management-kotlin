package com.example.shipping.infrastructure.persistence

import com.example.shipping.domain.model.Delivery
import org.springframework.data.repository.CrudRepository
import java.util.Optional

interface DeliveryRepository : CrudRepository<Delivery, String> {

    /** Idempotency check — returns existing delivery if already confirmed. */
    fun findByShipmentId(shipmentId: String): Optional<Delivery>
}
