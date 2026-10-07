package com.example.payment.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Transaction(
    @Id val transactionId: String,
    val orderId: String,
    val amount: BigDecimal,
    val currency: String,
    val type: String,
    val status: String,
    val failureReason: String? = null,
    val createdAt: Instant,
    @Transient val newRecord: Boolean = false,
) : Persistable<String> {

    // Spring Data JDBC's Kotlin instantiator cannot bind a @Transient
    // constructor parameter when materializing rows from the DB ("No property
    // ... found on entity"). A dedicated persistence constructor that omits it
    // (always newRecord=false, correct for anything read back from storage)
    // works around this — verified against a real database round-trip.
    @PersistenceCreator
    constructor(
        transactionId: String,
        orderId: String,
        amount: BigDecimal,
        currency: String,
        type: String,
        status: String,
        failureReason: String?,
        createdAt: Instant,
    ) : this(transactionId, orderId, amount, currency, type, status, failureReason, createdAt, false)

    override fun getId(): String = transactionId
    override fun isNew(): Boolean = newRecord

    fun fail(reason: String): Transaction = copy(status = "FAILED", failureReason = reason, newRecord = false)

    companion object {
        fun charge(orderId: String, amount: BigDecimal, currency: String): Transaction {
            val id = "TXN-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            return Transaction(id, orderId, amount, currency, "CHARGE", "COMPLETED", null, Instant.now(), true)
        }

        fun refund(refundForTransactionId: String, orderId: String, amount: BigDecimal): Transaction {
            val id = "REFUND-$refundForTransactionId"
            return Transaction(id, orderId, amount, "USD", "REFUND", "COMPLETED", null, Instant.now(), true)
        }
    }
}
