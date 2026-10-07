package com.example.payment.infrastructure.persistence

import com.example.payment.domain.model.Refund
import org.springframework.data.repository.CrudRepository

interface RefundRepository : CrudRepository<Refund, String> {

    /** Idempotency check — returns existing refund if this charge was already refunded. */
    fun findByOriginalTransactionId(originalTransactionId: String): Refund?
}
