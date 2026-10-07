package com.example.inventory.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

data class Reservation(
    @Id val reservationId: String,
    val orderId: String,
    val status: String,
    val itemsJson: String, // JSON array of {productId, quantity}
    val createdAt: Instant,
    val updatedAt: Instant,
    @Transient private val newRecord: Boolean = false,
) : Persistable<String> {

    // See InventoryItem for why this dedicated persistence constructor
    // (omitting the @Transient flag) is required for Spring Data JDBC's
    // Kotlin row-materialization to work.
    @PersistenceCreator
    constructor(
        reservationId: String,
        orderId: String,
        status: String,
        itemsJson: String,
        createdAt: Instant,
        updatedAt: Instant,
    ) : this(reservationId, orderId, status, itemsJson, createdAt, updatedAt, false)

    override fun getId(): String = reservationId
    override fun isNew(): Boolean = newRecord

    fun release(): Reservation =
        copy(status = "RELEASED", updatedAt = Instant.now(), newRecord = false)

    companion object {
        fun create(orderId: String, itemsJson: String): Reservation {
            val id = "INV-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            val now = Instant.now()
            return Reservation(id, orderId, "ACTIVE", itemsJson, now, now, newRecord = true)
        }
    }
}
