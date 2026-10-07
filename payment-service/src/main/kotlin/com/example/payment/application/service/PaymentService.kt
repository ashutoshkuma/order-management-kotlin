package com.example.payment.application.service

import com.example.contracts.api.PaymentContracts
import com.example.contracts.messaging.PaymentEventMessage
import com.example.payment.domain.model.Refund
import com.example.payment.domain.model.Transaction
import com.example.payment.infrastructure.outbox.OutboxEntry
import com.example.payment.infrastructure.outbox.OutboxFlushSignal
import com.example.payment.infrastructure.outbox.OutboxRepository
import com.example.payment.infrastructure.persistence.RefundRepository
import com.example.payment.infrastructure.persistence.TransactionRepository
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Service
class PaymentService(
    private val transactionRepository: TransactionRepository,
    private val refundRepository: RefundRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @field:Value("\${simulation.payment-failure-rate:0.0}")
    private var failureRate: Double = 0.0

    @field:Value("\${simulation.payment-transient-ratio:0.3}")
    private var transientRatio: Double = 0.3

    @Transactional
    fun charge(request: PaymentContracts.ChargePaymentRequest): PaymentContracts.ChargePaymentResponse {
        val existing = transactionRepository.findFirstByOrderIdAndTypeOrderByCreatedAtDesc(
            request.orderId, "CHARGE")
        if (existing != null) {
            return PaymentContracts.ChargePaymentResponse(
                existing.transactionId, "CHARGED", "Already charged (idempotent)")
        }

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            val failType = ThreadLocalRandom.current().nextDouble()
            if (failType < transientRatio) {
                throw RuntimeException("Payment gateway timeout (transient)")
            } else if (failType < 0.7) {
                throw InsufficientFundsException("INSUFFICIENT_FUNDS: account balance too low")
            } else {
                throw CardDeclinedException("CARD_DECLINED: rejected by issuing bank")
            }
        }

        val transaction = Transaction.charge(request.orderId, request.amount, request.currency)
        transactionRepository.save(transaction)

        writeOutbox(
            PaymentEventMessage.PaymentChargedMessage(
                UUID.randomUUID(), request.orderId,
                transaction.transactionId, request.amount, request.currency, Instant.now()),
            "PaymentCharged", "payment.events")

        return PaymentContracts.ChargePaymentResponse(
            transaction.transactionId, "CHARGED", "Payment successful")
    }

    @Transactional
    fun refund(request: PaymentContracts.RefundPaymentRequest): PaymentContracts.RefundPaymentResponse {
        val existing = refundRepository.findByOriginalTransactionId(request.transactionId)
        if (existing != null) {
            return PaymentContracts.RefundPaymentResponse(existing.refundId, "REFUNDED")
        }

        val original = transactionRepository.findById(request.transactionId)
            .orElseThrow { RuntimeException("Original transaction not found: ${request.transactionId}") }

        val refund = Refund.create(
            request.orderId,
            request.transactionId,
            original.amount,
            original.currency)
        refundRepository.save(refund)

        writeOutbox(
            PaymentEventMessage.PaymentRefundedMessage(
                UUID.randomUUID(), request.orderId,
                request.transactionId, refund.refundId,
                original.amount, Instant.now()),
            "PaymentRefunded", "payment.events")

        return PaymentContracts.RefundPaymentResponse(refund.refundId, "REFUNDED")
    }

    // ───────────────────────────────────────────────────────────────────

    private fun writeOutbox(message: PaymentEventMessage, eventType: String, topic: String) {
        try {
            val payload = objectMapper.writeValueAsString(message)
            outboxRepository.save(
                OutboxEntry.create(message.eventId, message.orderId, eventType, topic, payload))
            eventPublisher.publishEvent(OutboxFlushSignal())
        } catch (e: JsonProcessingException) {
            throw RuntimeException("Failed to serialize outbox event", e)
        }
    }

    class InsufficientFundsException(message: String) : RuntimeException(message)

    class CardDeclinedException(message: String) : RuntimeException(message)
}
