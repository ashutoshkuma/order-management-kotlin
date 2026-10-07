package com.example.ordermanagement.infrastructure.temporal

import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import com.example.ordermanagement.domain.port.outbound.WorkflowPort.WorkflowStatusResult
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.infrastructure.temporal.workflow.OrderFulfillmentWorkflow
import com.example.ordermanagement.infrastructure.temporal.workflow.YamlWorkflowLoader
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Infrastructure Adapter: WorkflowPortAdapter
 *
 * ═══════════════════════════════════════════════════════════════════
 * RESPONSIBILITY
 * ═══════════════════════════════════════════════════════════════════
 * Implements the WorkflowPort interface using Temporal's Java SDK.
 * This is where Spring Boot meets Temporal.
 *
 * The application layer calls WorkflowPort (the interface).
 * This adapter translates those calls into Temporal SDK operations.
 *
 * ═══════════════════════════════════════════════════════════════════
 * TEMPORAL CONCEPTS USED HERE
 * ═══════════════════════════════════════════════════════════════════
 *
 * WorkflowClient: The entry point to Temporal from application code.
 *   - Creates workflow stubs for sending signals and queries
 *   - Starts new workflow executions
 *
 * WorkflowOptions: Configuration for workflow execution
 *   - workflowId: deterministic ID derived from business ID
 *   - taskQueue: which worker pool handles this workflow
 *   - executionTimeout: maximum time the workflow can run
 *   - runTimeout: maximum time for a single run (after which Temporal continues-as-new)
 *
 * WorkflowExecutionAlreadyStarted: Temporal's idempotency mechanism.
 *   If we try to start a workflow with the same workflowId, Temporal
 *   returns this exception instead of creating a duplicate.
 *   We handle this gracefully — it means we've already started it.
 *
 * ═══════════════════════════════════════════════════════════════════
 * TEMPORAL QUERY EXPLAINED
 * ═══════════════════════════════════════════════════════════════════
 * Queries are SYNCHRONOUS reads of workflow in-memory state.
 * The Temporal server routes the query to the worker executing the workflow.
 * The worker calls the @QueryMethod on the workflow instance and returns the result.
 * No DB queries, no replay — just reading current workflow local variables.
 * This makes queries extremely fast (sub-millisecond typically).
 */
@Component
class WorkflowPortAdapter(
    private val workflowClient: WorkflowClient
) : WorkflowPort {

    @Value("\${simulation.step-delay-ms:0}")
    private var stepDelayMs: Long = 0

    override fun startFulfillmentWorkflow(orderId: OrderId, workflowId: String): String {
        log.info("Starting fulfillment workflow {} for order {}", workflowId, orderId)

        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            // Maximum time the entire workflow can run (including waiting for signals)
            .setWorkflowExecutionTimeout(Duration.ofHours(24))
            // Maximum time for a single workflow run
            .setWorkflowRunTimeout(Duration.ofHours(2))
            .build()

        val workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow::class.java, options)

        try {
            // Start asynchronously — don't wait for workflow to complete
            val loader = YamlWorkflowLoader()
            val definition = loader.loadWorkflow("workflow-config.yml")
            WorkflowClient.start(workflow::fulfill, orderId.toString(), stepDelayMs, definition)
            log.info("Workflow {} started on task queue {}", workflowId, TASK_QUEUE)
        } catch (e: WorkflowExecutionAlreadyStarted) {
            // Idempotency: workflow already running with this ID — that's fine
            log.info("Workflow {} already running — skipping start (idempotent)", workflowId)
        }

        return workflowId
    }

    override fun sendCancelSignal(workflowId: String, reason: String) {
        log.info("Sending CancelOrder signal to workflow {}: {}", workflowId, reason)

        val workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow::class.java, workflowId)

        try {
            workflow.cancelOrder(reason)
            log.info("CancelOrder signal delivered to workflow {}", workflowId)
        } catch (e: Exception) {
            // Workflow may have already completed — log but don't fail
            log.warn("Could not deliver CancelOrder signal to workflow {} (may have already completed): {}", workflowId, e.message)
        }
    }

    override fun sendRetryPaymentSignal(workflowId: String) {
        log.info("Sending RetryPayment signal to workflow {}", workflowId)

        val workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow::class.java, workflowId)

        try {
            workflow.retryPayment()
        } catch (e: Exception) {
            log.warn("Could not deliver RetryPayment signal to workflow {} (may have already completed): {}", workflowId, e.message)
        }
    }

    override fun queryWorkflowStatus(workflowId: String): WorkflowStatusResult {
        return try {
            val workflow = workflowClient.newWorkflowStub(OrderFulfillmentWorkflow::class.java, workflowId)
            val progress = workflow.getProgress()
            WorkflowStatusResult(
                workflowId,
                progress.status,
                progress.currentStep,
                progress.retryCount
            )
        } catch (e: Exception) {
            log.debug("Workflow {} not found or completed — returning UNKNOWN status: {}", workflowId, e.message)
            WorkflowStatusResult(workflowId, "UNKNOWN", "UNKNOWN", 0)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkflowPortAdapter::class.java)
        const val TASK_QUEUE = "ORDER_FULFILLMENT_QUEUE"
    }
}
