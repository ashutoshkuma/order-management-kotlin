package com.example.shipping.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

class Shipment(
    @Id
    val shipmentId: String,
    val orderId: String,
    val trackingNumber: String,
    val carrier: String,
    val status: String,
    val createdAt: Instant,
    val deliveredAt: Instant?,
    @Transient
    private val isNew: Boolean = false,
) : Persistable<String> {

    // Spring Data JDBC's Kotlin instantiator cannot bind a @Transient
    // constructor parameter when materializing rows from the DB ("No property
    // ... found on entity"). A dedicated persistence constructor that omits it
    // (always isNew=false, correct for anything read back from storage) works
    // around this — verified against a real database round-trip.
    @PersistenceCreator
    constructor(
        shipmentId: String,
        orderId: String,
        trackingNumber: String,
        carrier: String,
        status: String,
        createdAt: Instant,
        deliveredAt: Instant?,
    ) : this(shipmentId, orderId, trackingNumber, carrier, status, createdAt, deliveredAt, false)

    override fun getId(): String = shipmentId
    override fun isNew(): Boolean = isNew

    fun markDelivered(): Shipment = Shipment(
        shipmentId, orderId, trackingNumber, carrier, "DELIVERED", createdAt, Instant.now(),
    )

    companion object {
        fun create(orderId: String, trackingNumber: String, carrier: String): Shipment {
            val id = "SHIP-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            return Shipment(id, orderId, trackingNumber, carrier, "CREATED", Instant.now(), null, isNew = true)
        }
    }
}
