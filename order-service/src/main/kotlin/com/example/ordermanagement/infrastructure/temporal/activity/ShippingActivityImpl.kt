package com.example.ordermanagement.infrastructure.temporal.activity

import com.example.contracts.api.ShippingContracts
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
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * ShippingActivityImpl — HTTP client to shipping-service
 *
 * WHAT CHANGED FROM MONOLITH:
 * Before: Generated fake shipmentId/trackingNumber in-process.
 * Now:    Real HTTP call to shipping-service:8083.
 *
 * DELIVERY CONFIRMATION:
 * In production this would wait for a carrier webhook signal.
 * The shipping-service exposes POST /deliveries/{shipmentId}/confirm
 * which simulates the carrier callback.
 *
 * CIRCUIT BREAKER:
 * Wraps the RestClient call (see `circuitBreaker.executeSupplier` below).
 * This is not redundant with Temporal's retry: Temporal's retry is
 * per-order — it keeps giving *this* order's activity another attempt.
 * The breaker is shared across every order's activity invocations in
 * this worker process, so once shipping-service starts failing or
 * running slow (`slow-call-duration-threshold` in application.yml), it
 * trips open and fails fast for all of them instead of letting every
 * concurrent order's retry pile up blocked HTTP calls against an
 * already-struggling dependency.
 */
@Component
@ActivityImpl(taskQueues = ["ORDER_FULFILLMENT_QUEUE"])
class ShippingActivityImpl(
    private val orderCommandService: OrderCommandService,
    private val orderRepository: OrderRepository,
    private val restClientBuilder: RestClient.Builder,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : ShippingActivity {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ShippingActivityImpl::class.java)
    }

    private val circuitBreaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("shipping-service")

    @Value("\${services.shipping.url:http://localhost:8083}")
    private lateinit var shippingServiceUrl: String

    @Value("\${simulation.step-delay-ms:0}")
    private var stepDelayMs: Int = 0

    override fun createShipment(orderId: String): ShippingActivity.ShipmentResult {
        simulateDelay()

        val order = orderRepository.findById(OrderId.of(orderId))
            .orElseThrow { OrderNotFoundException(orderId) }

        val request = ShippingContracts.CreateShipmentRequest(orderId, order.shippingAddress)

        try {
            val response: ShippingContracts.CreateShipmentResponse =
                circuitBreaker.executeSupplier {
                    restClientBuilder.build()
                        .post()
                        .uri("$shippingServiceUrl/shipments")
                        .body(request)
                        .retrieve()
                        .body(ShippingContracts.CreateShipmentResponse::class.java)
                } ?: throw RuntimeException("Empty response from shipping service")

            log.info(
                "Shipment created for order {}: shipmentId={}, tracking={}, carrier={}",
                orderId, response.shipmentId, response.trackingNumber, response.carrier
            )
            return ShippingActivity.ShipmentResult(response.shipmentId, response.trackingNumber, response.carrier)

        } catch (e: Exception) {
            log.warn("Shipping service error for order {} (Temporal will retry): {}", orderId, e.message)
            throw Activity.wrap(RuntimeException("Shipping service error: " + e.message))
        }
    }

    override fun confirmDelivery(orderId: String, shipmentId: String) {
        log.info("Confirming delivery for order {}, shipmentId={}", orderId, shipmentId)
        try {
            circuitBreaker.executeRunnable {
                restClientBuilder.build()
                    .post()
                    .uri("$shippingServiceUrl/deliveries/{shipmentId}/confirm", shipmentId)
                    .retrieve()
                    .body(ShippingContracts.ConfirmDeliveryResponse::class.java)
            }
            log.info("Delivery confirmed for order {}, shipmentId={}", orderId, shipmentId)
        } catch (e: Exception) {
            log.error("Delivery confirmation failed for order {}, shipmentId={}: {}", orderId, shipmentId, e.message)
            throw Activity.wrap(RuntimeException("Delivery confirmation failed: " + e.message))
        }
    }

    override fun recordShipmentCreated(orderId: String, shipmentId: String, trackingNumber: String, carrier: String) {
        try {
            orderCommandService.recordShipmentCreated(OrderId.of(orderId), shipmentId, trackingNumber, carrier)
            log.debug("ShipmentCreated recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("ShipmentCreated already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("ShipmentCreated already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record ShipmentCreated for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordShipmentDelivered(orderId: String, shipmentId: String) {
        try {
            orderCommandService.recordOrderDelivered(OrderId.of(orderId), shipmentId)
            log.debug("ShipmentDelivered recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("ShipmentDelivered already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("ShipmentDelivered already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record ShipmentDelivered for order {}: {}", orderId, e.message)
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
