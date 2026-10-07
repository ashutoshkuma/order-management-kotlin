package com.example.payment.api.controller

import com.example.contracts.api.PaymentContracts
import com.example.payment.application.service.PaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Payment", description = "Charge and refund operations")
class PaymentController(private val paymentService: PaymentService) {

    @Operation(summary = "Charge payment for an order")
    @PostMapping("/charge")
    fun charge(
        @Valid @RequestBody request: PaymentContracts.ChargePaymentRequest,
    ): ResponseEntity<PaymentContracts.ChargePaymentResponse> {
        return ResponseEntity.ok(paymentService.charge(request))
    }

    @Operation(summary = "Refund a payment (saga compensation)")
    @PostMapping("/refund")
    fun refund(
        @Valid @RequestBody request: PaymentContracts.RefundPaymentRequest,
    ): ResponseEntity<PaymentContracts.RefundPaymentResponse> {
        return ResponseEntity.ok(paymentService.refund(request))
    }

    @ExceptionHandler(
        PaymentService.InsufficientFundsException::class,
        PaymentService.CardDeclinedException::class,
    )
    fun handleNonRetryable(ex: RuntimeException): ResponseEntity<String> {
        // HTTP 422 signals non-retryable to PaymentActivityImpl
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ex.message)
    }
}
