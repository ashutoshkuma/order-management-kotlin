package com.example.payment.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Refund(
    @Id val refundId: String,
    val orderId: String,
    val originalTransactionId: String,
    val amount: BigDecimal,
    val currency: String,
    val status: String,
    val createdAt: Instant,
    @Transient val newRecord: Boolean = false,
) : Persistable<String> {

    // See Transaction for why this dedicated persistence constructor
    // (omitting the @Transient flag) is required for Spring Data JDBC's
    // Kotlin row-materialization to work.
    @PersistenceCreator
    constructor(
        refundId: String,
        orderId: String,
        originalTransactionId: String,
        amount: BigDecimal,
        currency: String,
        status: String,
        createdAt: Instant,
    ) : this(refundId, orderId, originalTransactionId, amount, currency, status, createdAt, false)

    override fun getId(): String = refundId
    override fun isNew(): Boolean = newRecord

    companion object {
        fun create(orderId: String, originalTransactionId: String, amount: BigDecimal, currency: String): Refund {
            val id = "REF-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            return Refund(id, orderId, originalTransactionId, amount, currency, "COMPLETED", Instant.now(), true)
        }
    }
}
