package com.example.ordermanagement.infrastructure.temporal.workflow

import com.example.ordermanagement.infrastructure.temporal.activity.CardDeclinedException
import com.example.ordermanagement.infrastructure.temporal.activity.InsufficientFundsException
import com.example.ordermanagement.infrastructure.temporal.activity.InventoryActivity
import com.example.ordermanagement.infrastructure.temporal.activity.NotificationActivity
import com.example.ordermanagement.infrastructure.temporal.activity.PaymentActivity
import com.example.ordermanagement.infrastructure.temporal.activity.ShippingActivity
import com.example.ordermanagement.infrastructure.temporal.workflow.model.WorkflowDefinition
import com.example.ordermanagement.infrastructure.temporal.workflow.model.WorkflowStep
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.Stack

class OrderFulfillmentWorkflowImpl : OrderFulfillmentWorkflow {

    private var status: String = "STARTING"
    private var currentStep: String = "INITIALIZING"
    private var retryCount: Int = 0
    private var cancellationRequested: Boolean = false
    private var cancellationReason: String = ""
    private var paymentRetryRequested: Boolean = false
    private var failureReason: String = ""

    // Compensation state
    private var reservationId: String? = null
    private var transactionId: String? = null
    private var shipmentId: String? = null

    private lateinit var notificationActivity: NotificationActivity

    private val executedSteps = Stack<WorkflowStep>()

    override fun fulfill(orderId: String, stepDelayMs: Long, workflowDefinition: WorkflowDefinition) {
        Workflow.getLogger(OrderFulfillmentWorkflowImpl::class.java)
            .info("Starting YAML-based OrderFulfillmentWorkflow for orderId={}", orderId)

        // Setup generic notification activity
        notificationActivity = Workflow.newActivityStub(
            NotificationActivity::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .build()
        )

        for (step in workflowDefinition.steps) {
            awaitCancelWindow(stepDelayMs)
            if (shouldCancel()) {
                compensateAndCancel(orderId, cancellationReason, "CUSTOMER")
                return
            }

            currentStep = step.name
            status = "IN_PROGRESS"

            try {
                executeStep(step, orderId)
                executedSteps.push(step)
            } catch (e: Exception) {
                if (step.name == "PROCESS_PAYMENT") {
                    val retryable = isPaymentRetryable(e)
                    try {
                        val paymentActivity = Workflow.newActivityStub(
                            PaymentActivity::class.java,
                            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
                        )
                        paymentActivity.recordPaymentFailed(orderId, extractMessage(e), retryable)
                    } catch (ex: Exception) {
                        // Ignore
                    }

                    if (retryable) {
                        currentStep = "WAITING_FOR_PAYMENT_RETRY"
                        val signalReceived = Workflow.await(Duration.ofMinutes(5)) {
                            paymentRetryRequested || cancellationRequested
                        }
                        if (cancellationRequested || !signalReceived) {
                            compensateAndCancel(
                                orderId,
                                if (cancellationReason.isEmpty()) "Payment retry timed out" else cancellationReason,
                                "CUSTOMER"
                            )
                            return
                        }
                        paymentRetryRequested = false
                        try {
                            executeStep(step, orderId)
                            executedSteps.push(step)
                        } catch (e2: Exception) {
                            handleFailure(orderId, step, e2)
                            return
                        }
                        continue
                    }
                }
                handleFailure(orderId, step, e)
                return
            }
        }

        currentStep = "AWAITING_DELIVERY"
        val shippingActivity = Workflow.newActivityStub(
            ShippingActivity::class.java,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
        )
        // shipmentId is guaranteed set here: the loop above only reaches this point
        // after CREATE_SHIPMENT has executed successfully.
        shippingActivity.confirmDelivery(orderId, shipmentId!!)
        shippingActivity.recordShipmentDelivered(orderId, shipmentId!!)

        currentStep = "COMPLETED"
        status = "COMPLETED"
        notificationActivity.sendOrderDeliveredNotification(orderId)
        Workflow.getLogger(OrderFulfillmentWorkflowImpl::class.java)
            .info("OrderFulfillmentWorkflow completed successfully orderId={}", orderId)
    }

    private fun executeStep(step: WorkflowStep, orderId: String) {
        val retryOptions = RetryOptions.newBuilder()
            .setMaximumAttempts(step.maxAttempts)
            .setInitialInterval(Duration.ofSeconds(1))
            .setBackoffCoefficient(2.0)

        // Insufficient funds / card declined are deterministic business rejections —
        // retrying the same charge won't change the outcome, so exclude them from
        // Temporal's activity-level retry rather than burning an attempt (and its
        // backoff delay) before isPaymentRetryable() below reaches the same verdict.
        if (step.name == "PROCESS_PAYMENT") {
            retryOptions.setDoNotRetry(
                InsufficientFundsException::class.java.name,
                CardDeclinedException::class.java.name,
            )
        }

        val options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(step.timeoutSeconds.toLong()))
            .setRetryOptions(retryOptions.build())
            .build()

        when (step.name) {
            "RESERVE_INVENTORY" -> {
                val inventoryActivity = Workflow.newActivityStub(InventoryActivity::class.java, options)
                val reservationResult = inventoryActivity.reserveInventory(orderId)
                reservationId = reservationResult.reservationId
                inventoryActivity.recordInventoryReserved(orderId, reservationId!!)
            }
            "PROCESS_PAYMENT" -> {
                val paymentActivity = Workflow.newActivityStub(PaymentActivity::class.java, options)
                val paymentResult = paymentActivity.processPayment(orderId)
                transactionId = paymentResult.transactionId
                paymentActivity.recordPaymentCompleted(orderId, transactionId!!)
            }
            "CREATE_SHIPMENT" -> {
                val shippingActivity = Workflow.newActivityStub(ShippingActivity::class.java, options)
                val shipmentResult = shippingActivity.createShipment(orderId)
                shipmentId = shipmentResult.shipmentId
                shippingActivity.recordShipmentCreated(
                    orderId, shipmentId!!, shipmentResult.trackingNumber, shipmentResult.carrier
                )
            }
            else -> throw IllegalArgumentException("Unknown step: ${step.name}")
        }
    }

    private fun handleFailure(orderId: String, step: WorkflowStep, e: Exception) {
        val options = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()

        if (step.name == "RESERVE_INVENTORY") {
            try {
                val inventoryActivity = Workflow.newActivityStub(InventoryActivity::class.java, options)
                inventoryActivity.recordInventoryReservationFailed(orderId, extractMessage(e))
            } catch (ex: Exception) {
                // Ignore
            }
        } else if (step.name == "PROCESS_PAYMENT") {
            try {
                val paymentActivity = Workflow.newActivityStub(PaymentActivity::class.java, options)
                paymentActivity.recordPaymentFailed(orderId, extractMessage(e), false)
            } catch (ex: Exception) {
                // Ignore
            }
        }

        compensateAll(orderId)
        status = "FAILED"
        currentStep = "${step.name}_FAILED"
        failureReason = e.message ?: ""
        try {
            val inventoryActivity = Workflow.newActivityStub(InventoryActivity::class.java, options)
            inventoryActivity.recordOrderCancelled(
                orderId, "Step ${step.name} failed: ${e.message}", "SYSTEM_COMPENSATION"
            )
        } catch (ex: Exception) {
            // Ignore
        }
    }

    private fun extractMessage(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message
            if (!msg.isNullOrBlank()) {
                return msg
            }
            t = t.cause
        }
        return e.javaClass.simpleName
    }

    private fun compensateAll(orderId: String) {
        val options = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
        while (!executedSteps.isEmpty()) {
            val step = executedSteps.pop()
            when (val compMethod = step.compensationMethod) {
                null -> Unit
                "releaseInventory" -> {
                    if (reservationId != null) {
                        val inventoryActivity = Workflow.newActivityStub(InventoryActivity::class.java, options)
                        inventoryActivity.releaseInventory(orderId, reservationId!!)
                        inventoryActivity.recordInventoryReleased(orderId, "Compensated")
                    }
                }
                "refundPayment" -> {
                    if (transactionId != null) {
                        val paymentActivity = Workflow.newActivityStub(PaymentActivity::class.java, options)
                        paymentActivity.refundPayment(orderId, transactionId!!)
                        paymentActivity.recordRefundCompleted(orderId, "REFUND-$transactionId")
                    }
                }
                else -> Workflow.getLogger(OrderFulfillmentWorkflowImpl::class.java)
                    .warn("Unknown compensation method: {}", compMethod)
            }
        }
    }

    private fun compensateAndCancel(orderId: String, reason: String, cancelledBy: String) {
        compensateAll(orderId)
        try {
            val inventoryActivity = Workflow.newActivityStub(
                InventoryActivity::class.java,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build()
            )
            inventoryActivity.recordOrderCancelled(orderId, reason, cancelledBy)
        } catch (e: Exception) {
            // Ignore
        }
        status = "CANCELLED"
        currentStep = "CANCELLED"
    }

    override fun cancelOrder(reason: String) {
        this.cancellationReason = reason
        this.cancellationRequested = true
    }

    override fun retryPayment() {
        this.paymentRetryRequested = true
    }

    override fun getCurrentStatus(): String = status

    override fun getProgress(): OrderFulfillmentWorkflow.WorkflowProgress =
        OrderFulfillmentWorkflow.WorkflowProgress(status, currentStep, retryCount, cancellationRequested, failureReason)

    private fun awaitCancelWindow(stepDelayMs: Long) {
        if (stepDelayMs > 0) {
            Workflow.await(Duration.ofMillis(stepDelayMs)) { cancellationRequested }
        }
    }

    private fun shouldCancel(): Boolean = cancellationRequested

    private fun isPaymentRetryable(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is InsufficientFundsException || t is CardDeclinedException) {
                return false
            }
            if (t is ApplicationFailure) {
                val type = t.type
                if (type != null && (type.contains("InsufficientFunds") || type.contains("CardDeclined"))) {
                    return false
                }
            }
            t = t.cause
        }
        return true
    }
}
