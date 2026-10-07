package com.example.ordermanagement.infrastructure.temporal.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Activity Interface: PaymentActivity
 *
 * Handles payment processing with support for:
 * - Simulated failures at configurable rates
 * - Non-retryable errors (card declined, insufficient funds)
 * - Compensation via refund
 */
@ActivityInterface
interface PaymentActivity {

    /**
     * Processes payment for the order.
     * May throw InsufficientFundsException or CardDeclinedException
     * which are non-retryable.
     * May throw transient exceptions which Temporal will retry.
     */
    @ActivityMethod
    fun processPayment(orderId: String): PaymentResult

    /**
     * Issues a refund for a previously completed payment.
     * This is the compensation action for the saga.
     */
    @ActivityMethod
    fun refundPayment(orderId: String, transactionId: String)

    @ActivityMethod
    fun recordPaymentCompleted(orderId: String, transactionId: String)

    @ActivityMethod
    fun recordPaymentFailed(orderId: String, reason: String, retryable: Boolean)

    @ActivityMethod
    fun recordRefundCompleted(orderId: String, refundTransactionId: String)

    data class PaymentResult(val transactionId: String, val message: String)
}
