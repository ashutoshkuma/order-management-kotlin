package com.example.shipping.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

class Delivery(
    @Id
    val deliveryId: String,
    val shipmentId: String,
    val orderId: String,
    val confirmedAt: Instant,
    @Transient
    private val isNew: Boolean = false,
) : Persistable<String> {

    // See Shipment for why this dedicated persistence constructor (omitting
    // the @Transient flag) is required for Spring Data JDBC's Kotlin
    // row-materialization to work.
    @PersistenceCreator
    constructor(
        deliveryId: String,
        shipmentId: String,
        orderId: String,
        confirmedAt: Instant,
    ) : this(deliveryId, shipmentId, orderId, confirmedAt, false)

    override fun getId(): String = deliveryId
    override fun isNew(): Boolean = isNew

    companion object {
        fun create(shipmentId: String, orderId: String): Delivery {
            val id = "DEL-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            return Delivery(id, shipmentId, orderId, Instant.now(), isNew = true)
        }
    }
}
