package com.example.inventory.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("inventory_items")
data class InventoryItem(
    @Id val productId: String,
    val productName: String,
    val quantityAvailable: Int,
    val quantityReserved: Int,
    val updatedAt: Instant,
    @Transient private val newRecord: Boolean = false,
) : Persistable<String> {

    // Spring Data JDBC's Kotlin instantiator cannot bind a @Transient primary-
    // constructor parameter when materializing rows from the DB ("No property
    // ... found on entity"). A dedicated persistence constructor that omits it
    // (always newRecord=false, correct for anything read back from storage)
    // works around this — verified against a real database round-trip.
    @PersistenceCreator
    constructor(
        productId: String,
        productName: String,
        quantityAvailable: Int,
        quantityReserved: Int,
        updatedAt: Instant,
    ) : this(productId, productName, quantityAvailable, quantityReserved, updatedAt, false)

    override fun getId(): String = productId
    override fun isNew(): Boolean = newRecord

    fun hasStock(quantity: Int): Boolean = quantityAvailable >= quantity

    /** Returns a new instance with stock decremented by quantity. */
    fun reserve(quantity: Int): InventoryItem {
        if (!hasStock(quantity)) {
            throw InsufficientStockException(
                "Insufficient stock for product $productId: requested=$quantity, available=$quantityAvailable")
        }
        return copy(
            quantityAvailable = quantityAvailable - quantity,
            quantityReserved = quantityReserved + quantity,
            updatedAt = Instant.now(),
            newRecord = false,
        )
    }

    /** Returns a new instance with stock restored by quantity. */
    fun release(quantity: Int): InventoryItem =
        copy(
            quantityAvailable = quantityAvailable + quantity,
            quantityReserved = maxOf(0, quantityReserved - quantity),
            updatedAt = Instant.now(),
            newRecord = false,
        )

    companion object {
        /** Factory for brand-new items — Spring Data JDBC will INSERT rather than UPDATE. */
        fun create(productId: String, productName: String, quantity: Int): InventoryItem =
            InventoryItem(productId, productName, quantity, 0, Instant.now(), newRecord = true)
    }
}
