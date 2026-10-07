package com.example.ordermanagement.infrastructure.temporal

import com.example.ordermanagement.infrastructure.temporal.activity.InsufficientFundsException
import com.example.ordermanagement.infrastructure.temporal.activity.InventoryActivity
import com.example.ordermanagement.infrastructure.temporal.activity.NotificationActivity
import com.example.ordermanagement.infrastructure.temporal.activity.PaymentActivity
import com.example.ordermanagement.infrastructure.temporal.activity.ShippingActivity
import com.example.ordermanagement.infrastructure.temporal.workflow.OrderFulfillmentWorkflow
import com.example.ordermanagement.infrastructure.temporal.workflow.OrderFulfillmentWorkflowImpl
import com.example.ordermanagement.infrastructure.temporal.workflow.YamlWorkflowLoader
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.assertj.core.api.Assertions.assertThat

/**
 * Temporal Workflow Tests
 *
 * ═══════════════════════════════════════════════════════════════════
 * HOW TEMPORAL WORKFLOW TESTING WORKS
 * ═══════════════════════════════════════════════════════════════════
 * Temporal provides TestWorkflowEnvironment — an in-memory server
 * that runs locally without a Docker/Temporal server.
 *
 * Key features:
 * - Time is virtual: fast-forward time to test timeouts
 * - Activities can be replaced with test doubles (POJOs implementing the interface)
 * - Signals and queries work exactly as in production
 *
 * IMPORTANT: Temporal does NOT support Mockito mocks for activities.
 * Activities must be real objects implementing the activity interface.
 * We use plain Kotlin classes as test doubles here — they're clear
 * and don't require any mocking framework.
 *
 * TESTING APPROACH:
 * 1. Implement activities as simple POJOs controlling behavior via flags
 * 2. Start workflow execution
 * 3. Optionally send signals / fast-forward time
 * 4. Assert workflow completion and state via queries
 */
@DisplayName("OrderFulfillmentWorkflow Tests")
class OrderFulfillmentWorkflowTest {

    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var client: WorkflowClient

    // ─────────────────────────────────────────────────────
    // Controllable activity test doubles
    // ─────────────────────────────────────────────────────

    private lateinit var inventoryActivity: TestInventoryActivity
    private lateinit var paymentActivity: TestPaymentActivity
    private lateinit var shippingActivity: TestShippingActivity
    private lateinit var notificationActivity: TestNotificationActivity

    @BeforeEach
    fun setUp() {
        // WorkflowDefinition/WorkflowStep (and the activity result data classes) are Kotlin
        // data classes with no no-arg constructor. TestWorkflowEnvironment's default
        // DataConverter builds its own internal Jackson ObjectMapper with no Kotlin module,
        // so workflow arguments fail to deserialize during workflow task processing unless we
        // supply the same Kotlin-aware DataConverter that TemporalConfig wires up for the real
        // WorkflowClient/WorkerFactory in production (see TemporalConfig.temporalDataConverter).
        val kotlinAwareMapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
        val dataConverter = DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(JacksonJsonPayloadConverter(kotlinAwareMapper))

        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(dataConverter)
                        .build()
                )
                .build()
        )
        client = testEnv.workflowClient

        worker = testEnv.newWorker("TEST_QUEUE")
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl::class.java)

        // Create fresh test doubles for each test
        inventoryActivity = TestInventoryActivity()
        paymentActivity = TestPaymentActivity()
        shippingActivity = TestShippingActivity()
        notificationActivity = TestNotificationActivity()

        worker.registerActivitiesImplementations(
            inventoryActivity, paymentActivity, shippingActivity, notificationActivity
        )

        testEnv.start()
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    @Test
    @DisplayName("Happy path: Order fulfills successfully end-to-end")
    fun shouldFulfillOrderSuccessfully() {
        val orderId = UUID.randomUUID().toString()

        startWorkflow(orderId)

        // Wait for workflow to complete
        testEnv.sleep(Duration.ofSeconds(10))

        // Verify activities were called
        assertThat(inventoryActivity.reserveCallCount.get()).isEqualTo(1)
        assertThat(inventoryActivity.reservedRecordCount.get()).isEqualTo(1)
        assertThat(paymentActivity.processCallCount.get()).isEqualTo(1)
        assertThat(paymentActivity.completedRecordCount.get()).isEqualTo(1)
        assertThat(shippingActivity.createCallCount.get()).isEqualTo(1)
        assertThat(shippingActivity.deliveredRecordCount.get()).isEqualTo(1)
        assertThat(notificationActivity.deliveredCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Inventory failure: Compensate and cancel order")
    fun shouldCompensateOnInventoryFailure() {
        val orderId = UUID.randomUUID().toString()

        // Configure inventory to always fail
        inventoryActivity.shouldFail = true

        startWorkflow(orderId)
        testEnv.sleep(Duration.ofMinutes(3)) // Let retries exhaust

        // Compensation: failure recorded, order cancelled
        assertThat(inventoryActivity.failedRecordCount.get()).isGreaterThan(0)
        assertThat(inventoryActivity.cancelledRecordCount.get()).isEqualTo(1)

        // Payment should never have been called
        assertThat(paymentActivity.processCallCount.get()).isEqualTo(0)
    }

    @Test
    @DisplayName("Payment failure after inventory: Release inventory and cancel")
    fun shouldCompensateOnPaymentFailure() {
        val orderId = UUID.randomUUID().toString()

        // Inventory succeeds, payment always fails with NON-retryable error.
        // InsufficientFundsException bypasses the 5-min await, triggering
        // compensation immediately (attempt 0, retryable=false → short-circuit).
        paymentActivity.shouldFail = true
        paymentActivity.failWithTransient = false

        startWorkflow(orderId)
        // Short sleep — non-retryable failure compensates without waiting for signals
        testEnv.sleep(Duration.ofSeconds(10))

        // Compensation: inventory reserved, then released after payment failure
        assertThat(inventoryActivity.reserveCallCount.get()).isEqualTo(1)
        assertThat(inventoryActivity.releaseCallCount.get()).isGreaterThanOrEqualTo(1)
        assertThat(inventoryActivity.cancelledRecordCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Cancel signal before inventory: Cancel immediately")
    fun shouldHandleCancelSignalBeforeInventory() {
        val orderId = UUID.randomUUID().toString()

        // Slow inventory so we can send signal during it
        inventoryActivity.latencyMs = 3000

        val workflow = startWorkflow(orderId)

        // Send cancel signal immediately
        workflow.cancelOrder("Test cancellation")

        testEnv.sleep(Duration.ofSeconds(10))

        // Order should be cancelled
        assertThat(inventoryActivity.cancelledRecordCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Query: getCurrentStatus returns workflow state")
    fun shouldReturnCurrentStatusViaQuery() {
        val orderId = UUID.randomUUID().toString()

        // Make inventory slow so we can query mid-execution
        inventoryActivity.latencyMs = 3000

        val workflow = startWorkflow(orderId)

        // Sleep briefly then query
        testEnv.sleep(Duration.ofMillis(500))

        val status = workflow.getCurrentStatus()
        // May be STARTING or IN_PROGRESS depending on timing
        assertThat(status).isNotNull().isNotEmpty()

        val progress = workflow.getProgress()
        assertThat(progress).isNotNull()
        assertThat(progress.cancellationRequested).isFalse()
    }

    @Test
    @DisplayName("Shipment failure: Refund payment, release inventory, cancel")
    fun shouldCompensateOnShipmentFailure() {
        val orderId = UUID.randomUUID().toString()

        // Inventory and payment succeed, shipment fails
        shippingActivity.shouldFail = true

        startWorkflow(orderId)
        testEnv.sleep(Duration.ofMinutes(3))

        // Full compensation chain
        assertThat(paymentActivity.refundCallCount.get()).isEqualTo(1)
        assertThat(inventoryActivity.releaseCallCount.get()).isEqualTo(1)
        assertThat(inventoryActivity.cancelledRecordCount.get()).isEqualTo(1)
    }

    // ─────────────────────────────────────────────────────
    // Test double activity implementations
    // These implement the activity interfaces directly — no mocking framework
    // ─────────────────────────────────────────────────────

    class TestInventoryActivity : InventoryActivity {
        @Volatile
        var shouldFail: Boolean = false

        @Volatile
        var latencyMs: Int = 100
        val reserveCallCount = AtomicInteger()
        val releaseCallCount = AtomicInteger()
        val reservedRecordCount = AtomicInteger()
        val failedRecordCount = AtomicInteger()
        val cancelledRecordCount = AtomicInteger()

        override fun reserveInventory(orderId: String): InventoryActivity.ReservationResult {
            reserveCallCount.incrementAndGet()
            sleep(latencyMs)
            if (shouldFail) throw RuntimeException("Inventory unavailable (test)")
            return InventoryActivity.ReservationResult("RES-TEST-" + orderId.substring(0, 6), "Reserved")
        }

        override fun releaseInventory(orderId: String, reservationId: String) {
            releaseCallCount.incrementAndGet()
        }

        override fun recordInventoryReserved(orderId: String, reservationId: String) {
            reservedRecordCount.incrementAndGet()
        }

        override fun recordInventoryReservationFailed(orderId: String, reason: String) {
            failedRecordCount.incrementAndGet()
        }

        override fun recordInventoryReleased(orderId: String, reason: String) {}

        override fun recordOrderCancelled(orderId: String, reason: String, cancelledBy: String) {
            cancelledRecordCount.incrementAndGet()
        }
    }

    class TestPaymentActivity : PaymentActivity {
        @Volatile
        var shouldFail: Boolean = false

        @Volatile
        var failWithTransient: Boolean = false
        val processCallCount = AtomicInteger()
        val completedRecordCount = AtomicInteger()
        val refundCallCount = AtomicInteger()

        override fun processPayment(orderId: String): PaymentActivity.PaymentResult {
            processCallCount.incrementAndGet()
            if (shouldFail) {
                if (failWithTransient) throw RuntimeException("Payment timeout (transient)")
                throw InsufficientFundsException("Insufficient funds (test)")
            }
            return PaymentActivity.PaymentResult("TXN-TEST-" + orderId.substring(0, 6), "Paid")
        }

        override fun refundPayment(orderId: String, transactionId: String) {
            refundCallCount.incrementAndGet()
        }

        override fun recordPaymentCompleted(orderId: String, transactionId: String) {
            completedRecordCount.incrementAndGet()
        }

        override fun recordPaymentFailed(orderId: String, reason: String, retryable: Boolean) {}

        override fun recordRefundCompleted(orderId: String, refundTransactionId: String) {}
    }

    class TestShippingActivity : ShippingActivity {
        @Volatile
        var shouldFail: Boolean = false
        val createCallCount = AtomicInteger()
        val deliveredRecordCount = AtomicInteger()

        override fun createShipment(orderId: String): ShippingActivity.ShipmentResult {
            createCallCount.incrementAndGet()
            if (shouldFail) throw RuntimeException("Shipment service down (test)")
            return ShippingActivity.ShipmentResult("SHIP-TEST", "TRACK-TEST", "UPS")
        }

        override fun confirmDelivery(orderId: String, shipmentId: String) {}

        override fun recordShipmentCreated(orderId: String, shipmentId: String, trackingNumber: String, carrier: String) {}

        override fun recordShipmentDelivered(orderId: String, shipmentId: String) {
            deliveredRecordCount.incrementAndGet()
        }
    }

    class TestNotificationActivity : NotificationActivity {
        val deliveredCount = AtomicInteger()

        override fun sendOrderConfirmedNotification(orderId: String) {}

        override fun sendOrderShippedNotification(orderId: String, trackingNumber: String) {}

        override fun sendOrderDeliveredNotification(orderId: String) {
            deliveredCount.incrementAndGet()
        }

        override fun sendOrderCancelledNotification(orderId: String, reason: String) {}

        override fun sendPaymentFailedNotification(orderId: String, reason: String) {}
    }

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun startWorkflow(orderId: String): OrderFulfillmentWorkflow {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId("test-workflow-$orderId")
            .setTaskQueue("TEST_QUEUE")
            .build()

        val workflow = client.newWorkflowStub(OrderFulfillmentWorkflow::class.java, options)

        val loader = YamlWorkflowLoader()
        val definition = loader.loadWorkflow("workflow-config.yml")

        WorkflowClient.start(workflow::fulfill, orderId, 0L, definition)
        return workflow
    }
}

private fun sleep(ms: Int) {
    try {
        Thread.sleep(ms.toLong())
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
