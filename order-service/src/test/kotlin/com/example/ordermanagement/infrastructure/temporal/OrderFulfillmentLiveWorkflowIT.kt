package com.example.ordermanagement.infrastructure.temporal

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
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.assertj.core.api.Assertions.assertThat

/**
 * Live Temporal Integration Test
 *
 * This test class connects to a REAL Temporal server (expected at localhost:7233)
 * and runs the exact same scenarios as OrderFulfillmentWorkflowTest.
 * Workflows executed here WILL be visible in your Temporal UI (localhost:8233).
 *
 * It starts a local Worker connecting to the real server, but registers the
 * "Test Double" activities so we can simulate failures (like Inventory failure).
 */
@Disabled("Remove this annotation when your local Temporal server is running via Docker to execute these tests.")
@DisplayName("Live Temporal Workflow Tests")
class OrderFulfillmentLiveWorkflowIT {

    private val taskQueue: String = "LIVE_TEST_QUEUE_" + UUID.randomUUID().toString().substring(0, 8)
    private lateinit var service: WorkflowServiceStubs
    private lateinit var client: WorkflowClient
    private lateinit var factory: WorkerFactory

    private lateinit var inventoryActivity: TestInventoryActivity
    private lateinit var paymentActivity: TestPaymentActivity
    private lateinit var shippingActivity: TestShippingActivity
    private lateinit var notificationActivity: TestNotificationActivity

    @BeforeEach
    fun setUp() {
        // Connect to the real Temporal server (default localhost:7233)
        service = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build()
        )

        // WorkflowDefinition/WorkflowStep are Kotlin data classes with no no-arg constructor.
        // The default DataConverter's internal Jackson ObjectMapper has no Kotlin module, so
        // deserialization of workflow arguments fails during workflow task processing unless we
        // use the same Kotlin-aware DataConverter TemporalConfig wires up in production (see
        // TemporalConfig.temporalDataConverter).
        val kotlinAwareMapper = JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule()
        val dataConverter = DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(JacksonJsonPayloadConverter(kotlinAwareMapper))

        client = WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setDataConverter(dataConverter).build()
        )
        factory = WorkerFactory.newInstance(client)

        // Create a worker specifically for this test's task queue
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl::class.java)

        // Create fresh test doubles
        inventoryActivity = TestInventoryActivity()
        paymentActivity = TestPaymentActivity()
        shippingActivity = TestShippingActivity()
        notificationActivity = TestNotificationActivity()

        worker.registerActivitiesImplementations(
            inventoryActivity, paymentActivity, shippingActivity, notificationActivity
        )

        factory.start()
    }

    @AfterEach
    fun tearDown() {
        factory.shutdown()
        service.shutdown()
    }

    @Test
    @DisplayName("Happy path: Order fulfills successfully end-to-end")
    fun shouldFulfillOrderSuccessfully() {
        val orderId = UUID.randomUUID().toString()
        startWorkflow(orderId)

        // Wait for workflow to complete (real time now, no skipping)
        Thread.sleep(2000)

        assertThat(inventoryActivity.reserveCallCount.get()).isEqualTo(1)
        assertThat(paymentActivity.processCallCount.get()).isEqualTo(1)
        assertThat(shippingActivity.createCallCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("Inventory failure: Compensate and cancel order")
    fun shouldCompensateOnInventoryFailure() {
        val orderId = UUID.randomUUID().toString()

        // Configure inventory to always fail
        inventoryActivity.shouldFail = true

        startWorkflow(orderId)

        // Wait for 3 retries (1s + 2s + 4s approx)
        Thread.sleep(8000)

        assertThat(inventoryActivity.failedRecordCount.get()).isGreaterThan(0)
        assertThat(inventoryActivity.cancelledRecordCount.get()).isEqualTo(1)
        assertThat(paymentActivity.processCallCount.get()).isEqualTo(0)
    }

    // ─────────────────────────────────────────────────────
    // Test double activity implementations
    // ─────────────────────────────────────────────────────

    class TestInventoryActivity : InventoryActivity {
        @Volatile
        var shouldFail: Boolean = false
        val reserveCallCount = AtomicInteger()
        val releaseCallCount = AtomicInteger()
        val failedRecordCount = AtomicInteger()
        val cancelledRecordCount = AtomicInteger()

        override fun reserveInventory(orderId: String): InventoryActivity.ReservationResult {
            reserveCallCount.incrementAndGet()
            if (shouldFail) throw RuntimeException("Inventory unavailable (test)")
            return InventoryActivity.ReservationResult("RES-TEST-" + orderId.substring(0, 6), "Reserved")
        }

        override fun releaseInventory(orderId: String, reservationId: String) {
            releaseCallCount.incrementAndGet()
        }

        override fun recordInventoryReserved(orderId: String, reservationId: String) {}

        override fun recordInventoryReservationFailed(orderId: String, reason: String) {
            failedRecordCount.incrementAndGet()
        }

        override fun recordInventoryReleased(orderId: String, reason: String) {}

        override fun recordOrderCancelled(orderId: String, reason: String, cancelledBy: String) {
            cancelledRecordCount.incrementAndGet()
        }
    }

    class TestPaymentActivity : PaymentActivity {
        val processCallCount = AtomicInteger()
        val refundCallCount = AtomicInteger()

        override fun processPayment(orderId: String): PaymentActivity.PaymentResult {
            processCallCount.incrementAndGet()
            return PaymentActivity.PaymentResult("TXN-TEST-" + orderId.substring(0, 6), "Paid")
        }

        override fun refundPayment(orderId: String, transactionId: String) {
            refundCallCount.incrementAndGet()
        }

        override fun recordPaymentCompleted(orderId: String, transactionId: String) {}

        override fun recordPaymentFailed(orderId: String, reason: String, retryable: Boolean) {}

        override fun recordRefundCompleted(orderId: String, refundTransactionId: String) {}
    }

    class TestShippingActivity : ShippingActivity {
        val createCallCount = AtomicInteger()

        override fun createShipment(orderId: String): ShippingActivity.ShipmentResult {
            createCallCount.incrementAndGet()
            return ShippingActivity.ShipmentResult("SHIP-TEST", "TRACK-TEST", "UPS")
        }

        override fun confirmDelivery(orderId: String, shipmentId: String) {}

        override fun recordShipmentCreated(orderId: String, shipmentId: String, trackingNumber: String, carrier: String) {}

        override fun recordShipmentDelivered(orderId: String, shipmentId: String) {}
    }

    class TestNotificationActivity : NotificationActivity {
        override fun sendOrderConfirmedNotification(orderId: String) {}

        override fun sendOrderShippedNotification(orderId: String, trackingNumber: String) {}

        override fun sendOrderDeliveredNotification(orderId: String) {}

        override fun sendOrderCancelledNotification(orderId: String, reason: String) {}

        override fun sendPaymentFailedNotification(orderId: String, reason: String) {}
    }

    // ─────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────

    private fun startWorkflow(orderId: String): OrderFulfillmentWorkflow {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId("live-test-workflow-$orderId")
            .setTaskQueue(taskQueue)
            .build()

        val workflow = client.newWorkflowStub(OrderFulfillmentWorkflow::class.java, options)

        val loader = YamlWorkflowLoader()
        val definition = loader.loadWorkflow("workflow-config.yml")

        WorkflowClient.start(workflow::fulfill, orderId, 0L, definition)
        return workflow
    }
}
