package com.example.payment.infrastructure.persistence

import com.example.payment.domain.model.Transaction
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

/**
 * Spring Data JDBC Repository for Transaction persistence.
 */
@Repository
interface TransactionRepository : CrudRepository<Transaction, String> {
    /**
     * Find the most recent charge transaction for an order.
     * Used for idempotency checks.
     */
    fun findFirstByOrderIdAndTypeOrderByCreatedAtDesc(orderId: String, type: String): Transaction?

    /**
     * Find all transactions for an order.
     */
    fun findByOrderId(orderId: String): List<Transaction>
}
