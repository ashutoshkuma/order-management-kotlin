package com.example.contracts.api

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

/**
 * HTTP API contracts for the Payment Service.
 */
object PaymentContracts {

    data class ChargePaymentRequest(
        @field:NotBlank val orderId: String,
        @field:DecimalMin("0.01") val amount: BigDecimal,
        val currency: String,
    )

    data class ChargePaymentResponse(
        val transactionId: String,
        val status: String, // "CHARGED"
        val message: String,
    )

    data class RefundPaymentRequest(
        @field:NotBlank val orderId: String,
        @field:NotBlank val transactionId: String,
    )

    data class RefundPaymentResponse(
        val refundTransactionId: String,
        val status: String, // "REFUNDED"
    )
}
