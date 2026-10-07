package com.example.ordermanagement.infrastructure.temporal.activity

import com.example.contracts.api.InventoryContracts
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
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * InventoryActivityImpl — HTTP client to inventory-service
 *
 * WHAT CHANGED FROM MONOLITH:
 * Before: Simulated success/failure with random numbers in-process.
 * Now:    Makes real HTTP calls to inventory-service:8081.
 *
 * The inventory-service itself controls the failure simulation.
 * This service is just a client — it calls, parses the response,
 * and records the result as a domain event.
 *
 * RETRY BEHAVIOUR:
 * Temporal handles retries when this throws an exception.
 * The inventory-service must be idempotent for the same reservationId
 * (which we pass as the orderId — deterministic per order).
 *
 * CIRCUIT BREAKER:
 * In production, wrap RestClient calls with Resilience4j circuit breaker.
 * Not added here to keep the demo focused on Temporal + Event Sourcing.
 */
@Component
@ActivityImpl(taskQueues = ["ORDER_FULFILLMENT_QUEUE"])
class InventoryActivityImpl(
    private val orderCommandService: OrderCommandService,
    private val orderRepository: OrderRepository,
    private val restClientBuilder: RestClient.Builder,
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : InventoryActivity {

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(InventoryActivityImpl::class.java)
    }

    private val circuitBreaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory-service")

    @Value("\${services.inventory.url:http://localhost:8081}")
    private lateinit var inventoryServiceUrl: String

    @Value("\${simulation.step-delay-ms:0}")
    private var stepDelayMs: Int = 0

    override fun reserveInventory(orderId: String): InventoryActivity.ReservationResult {
        simulateDelay()

        // Load order items to know what to reserve
        val order = orderRepository.findById(OrderId.of(orderId))
            .orElseThrow { OrderNotFoundException(orderId) }

        val lineItems: List<InventoryContracts.ReserveInventoryRequest.LineItem> = order.items.map { item ->
            InventoryContracts.ReserveInventoryRequest.LineItem(item.productId, item.quantity)
        }

        val request = InventoryContracts.ReserveInventoryRequest(orderId, lineItems)

        try {
            val response: InventoryContracts.ReserveInventoryResponse =
                circuitBreaker.executeSupplier {
                    restClientBuilder.build()
                        .post()
                        .uri("$inventoryServiceUrl/reserve")
                        .body(request)
                        .retrieve()
                        .body(InventoryContracts.ReserveInventoryResponse::class.java)
                } ?: throw RuntimeException("Empty response from inventory service")

            log.info("Inventory reserved for order {}: reservationId={}", orderId, response.reservationId)
            return InventoryActivity.ReservationResult(response.reservationId, response.message)

        } catch (e: HttpClientErrorException.UnprocessableEntity) {
            // 422 from inventory-service = not enough stock (non-retryable)
            log.warn("Insufficient stock for order {}: {}", orderId, e.message)
            throw Activity.wrap(RuntimeException("Insufficient stock: " + e.message))
        } catch (e: Exception) {
            // Network errors, 5xx, or circuit open = retryable by Temporal
            log.warn("Inventory service error for order {} (Temporal will retry): {}", orderId, e.message)
            throw Activity.wrap(RuntimeException("Inventory service unavailable: " + e.message))
        }
    }

    override fun releaseInventory(orderId: String, reservationId: String) {
        log.info("Releasing inventory for order {}, reservationId={}", orderId, reservationId)
        try {
            circuitBreaker.executeRunnable {
                restClientBuilder.build()
                    .delete()
                    .uri("$inventoryServiceUrl/reserve/{reservationId}", reservationId)
                    .retrieve()
                    .toBodilessEntity()
            }
            log.info("Inventory released for order {}, reservationId={}", orderId, reservationId)
        } catch (e: Exception) {
            log.error("Failed to release inventory for order {}, reservationId={}: {}", orderId, reservationId, e.message)
            throw Activity.wrap(RuntimeException("Failed to release inventory: " + e.message))
        }
    }

    override fun recordInventoryReserved(orderId: String, reservationId: String) {
        try {
            orderCommandService.recordInventoryReserved(OrderId.of(orderId), reservationId)
            log.debug("InventoryReserved recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("InventoryReserved already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("InventoryReserved already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record InventoryReserved for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordInventoryReservationFailed(orderId: String, reason: String) {
        try {
            orderCommandService.recordInventoryReservationFailed(OrderId.of(orderId), reason)
            log.debug("InventoryReservationFailed recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("InventoryReservationFailed already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("InventoryReservationFailed already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record InventoryReservationFailed for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordInventoryReleased(orderId: String, reason: String) {
        try {
            orderCommandService.recordInventoryReleased(OrderId.of(orderId), reason)
            log.debug("InventoryReleased recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("InventoryReleased already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("InventoryReleased already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record InventoryReleased for order {}: {}", orderId, e.message)
            throw Activity.wrap(e)
        }
    }

    override fun recordOrderCancelled(orderId: String, reason: String, cancelledBy: String) {
        try {
            orderCommandService.recordOrderCancelled(OrderId.of(orderId), reason, cancelledBy)
            log.debug("OrderCancelled recorded for order {}", orderId)
        } catch (e: DuplicateKeyException) {
            log.debug("OrderCancelled already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: OptimisticLockingException) {
            log.debug("OrderCancelled already recorded for order {} — skipping (idempotent)", orderId)
        } catch (e: Exception) {
            log.error("Failed to record OrderCancelled for order {}: {}", orderId, e.message)
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
