package com.example.ordermanagement.infrastructure.temporal.activity

import com.example.contracts.api.PaymentContracts
import com.example.ordermanagement.application.service.OrderCommandService
import com.example.ordermanagement.domain.exception.OptimisticLockingException
import com.example.ordermanagement.domain.exception.OrderNotFoundException
import com.example.ordermanagement.domain.port.outbound.OrderRepository
import com.example.ordermanagement.domain.valueobject.OrderId
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.temporal.activity.Activity
import io.temporal.spring.boot.ActivityImpl
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * PaymentActivityImpl — HTTP client to payment-service
 *
 * WHAT CHANGED FROM MONOLITH:
 * Before: Random failure simulation in-process.
 * Now:    Real HTTP call to payment-service:8082.
 *
 * NON-RETRYABLE EXCEPTIONS:
 * The payment-service returns HTTP 422 for non-retryable failures
 * (insufficient funds, card declined). We translate those to
 * InsufficientFundsException / CardDeclinedException so Temporal's
 * doNotRetry policy kicks in correctly.
 */
@Component
@ActivityImpl(taskQueues = ["ORDER_FULFILLMENT_QUEUE"])
class PaymentActivityImpl(
    private val orderCommandService: OrderCommandService,
    private val orderRepository: OrderRepository,
    private val restClientBuilder: RestClient.Builder,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : PaymentActivity {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(PaymentActivityImpl::class.java)
    }

    private val circuitBreaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("payment-service")

    @Value("\${services.payment.url:http://localhost:8082}")
    private lateinit var paymentServiceUrl: String

    @Value("\${simulation.step-delay-ms:0}")
    private var stepDelayMs: Int = 0

    override fun processPayment(orderId: String): PaymentActivity.PaymentResult {
        simulateDelay()

        val order = orderRepository.findById(OrderId.of(orderId))
            .orElseThrow { OrderNotFoundException(orderId) }

        val request = PaymentContracts.ChargePaymentRequest(
            orderId,
            order.totalAmount.amount,
            order.totalAmount.currencyCode
        )

        try {
            val response: PaymentContracts.ChargePaymentResponse =
                circuitBreaker.executeSupplier {
                    restClientBuilder.build()
                        .post()
                        .uri("$paymentServiceUrl/charge")
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError) { _, resp ->
                            val body = String(resp.body.readAllBytes())
                            if (resp.statusCode.value() == 422) {
                                if (body.contains("INSUFFICIENT_FUNDS")) {
                                    throw InsufficientFundsException("Insufficient funds in account")
                                } else if (body.contains("CARD_DECLINED")) {
                                    throw CardDeclinedException("Card declined by issuing bank")
                                }
                            }
                            throw RuntimeException("Payment failed: $body")
                        }
                        .body(PaymentContracts.ChargePaymentResponse::class.java)
                } ?: throw RuntimeException("Empty response from payment service")

            log.info("Payment processed for order {}: txId={}", orderId, response.transactionId)
            return PaymentActivity.PaymentResult(response.transactionId, response.message)

        } catch (e: InsufficientFundsException) {
            throw e // Let Temporal's doNotRetry handle these
        } catch (e: CardDeclinedException) {
            throw e // Let Temporal's doNotRetry handle these
        } catch (e: Exception) {
            log.warn("Payment service error for order {} (Temporal will retry): {}", orderId, e.message)
            throw Activity.wrap(RuntimeException("Payment service error: " + e.message))
        }
    }

    override fun refundPayment(orderId: String, transactionId: String) {
        log.info("Refunding payment for order {}, transactionId={}", orderId, transactionId)
        try {
            circuitBreaker.executeRunnable {
                restClientBuilder.build()
                    .post()
                    .uri("$paymentServiceUrl/refund")
                    .body(PaymentContracts.RefundPaymentRequest(orderId, transactionId))
                    .retrieve()
                    .body(PaymentContracts.RefundPaymentResponse::class.java)
            }
            log.info("Refund completed for order {}, transactionId={}", orderId, transactionId)
        } catch (e: Exception) {
            log.error("Refund failed for order {}, transactionId={}: {}", orderId, transactionId, e.message)
            throw Activity.wrap(RuntimeException("Refund failed: " + e.message))
        }
    }

    override fun recordPaymentCompleted(orderId: String, transactionId: String) {
        try {
            orderCommandService.recordPaymentCompleted(OrderId.of(orderId), transactionId)
            log.debug("PaymentCompleted recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("PaymentCompleted already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("PaymentCompleted already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record PaymentCompleted for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordPaymentFailed(orderId: String, reason: String, retryable: Boolean) {
        try {
            orderCommandService.recordPaymentFailed(OrderId.of(orderId), reason, retryable)
            log.debug("PaymentFailed recorded for order {} (retryable={})", orderId, retryable)
        } catch (e: DuplicateKeyException) {
            log.debug("PaymentFailed already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("PaymentFailed already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record PaymentFailed for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordRefundCompleted(orderId: String, refundTransactionId: String) {
        try {
            orderCommandService.recordRefundCompleted(OrderId.of(orderId), refundTransactionId)
            log.debug("RefundCompleted recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("RefundCompleted already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("RefundCompleted already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record RefundCompleted for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    private fun simulateDelay() {
        if (stepDelayMs <= 0) return
        try {
            Thread.sleep(stepDelayMs.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
