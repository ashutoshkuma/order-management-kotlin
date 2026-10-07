package com.example.ordermanagement.infrastructure.temporal.workflow

import com.example.ordermanagement.infrastructure.temporal.workflow.model.WorkflowDefinition
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Workflow Interface: OrderFulfillmentWorkflow
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY TEMPORAL? — THE FUNDAMENTAL ANSWER
 * ═══════════════════════════════════════════════════════════════════
 * Consider this problem: a multi-step process that takes 2 minutes to complete.
 * During those 2 minutes, the server could crash, the database could go down,
 * or a dependency could be unavailable.
 *
 * Without Temporal:
 *   - You need queues, retry tables, reconciliation jobs, dead letter queues
 *   - Distributed transactions across multiple services
 *   - Complex state management spread across DB tables
 *   - Difficult testing and debugging
 *
 * With Temporal:
 *   - Write sequential code that looks like a simple function
 *   - Temporal guarantees it WILL complete, even across crashes
 *   - Built-in retry policies, timeouts, and compensation
 *   - Full execution history for debugging
 *
 * ═══════════════════════════════════════════════════════════════════
 * TEMPORAL WORKFLOW EXPLAINED
 * ═══════════════════════════════════════════════════════════════════
 * A Workflow is a DURABLE FUNCTION. Its execution state is persisted
 * in Temporal's database after every step. If the worker crashes:
 *   - Temporal replays the workflow history on a new worker
 *   - Completed activities are NOT re-executed (results cached)
 *   - The workflow continues from where it left off
 *
 * This is called "event sourcing for workflow execution" — and yes,
 * Temporal ITSELF uses event sourcing internally for workflow state.
 *
 * ═══════════════════════════════════════════════════════════════════
 * SIGNALS
 * ═══════════════════════════════════════════════════════════════════
 * Signals are asynchronous messages sent TO a running workflow.
 * They allow external systems (REST API, users) to interact with
 * a long-running workflow without polling.
 *
 * cancelOrder: Customer wants to cancel → sends CancelOrder signal
 * retryPayment: Customer provides new card → sends RetryPayment signal
 *
 * ═══════════════════════════════════════════════════════════════════
 * QUERIES
 * ═══════════════════════════════════════════════════════════════════
 * Queries read the CURRENT state of the workflow without modifying it.
 * They are synchronous and return immediately from workflow's in-memory state.
 * They do NOT trigger workflow execution.
 */
@WorkflowInterface
interface OrderFulfillmentWorkflow {

    /**
     * Main workflow method. Orchestrates the entire order fulfillment process.
     * This method runs durably — surviving worker crashes and restarts.
     *
     * @param orderId      The ID of the confirmed order to fulfill
     * @param stepDelayMs  Milliseconds to pause at each cancel checkpoint before starting the
     *                     next activity. 0 in production; set via simulation.step-delay-ms for
     *                     demos so a cancel signal can arrive before the activity is scheduled.
     */
    @WorkflowMethod
    fun fulfill(orderId: String, stepDelayMs: Long, workflowDefinition: WorkflowDefinition)

    /**
     * Signal: CancelOrder
     *
     * Sent by customer or API to cancel the order.
     * The workflow receives this at its next checkpoint and initiates compensation.
     * Temporal guarantees delivery even if the workflow is mid-activity.
     *
     * @param reason Human-readable cancellation reason
     */
    @SignalMethod
    fun cancelOrder(reason: String)

    /**
     * Signal: RetryPayment
     *
     * Sent when a customer wants to retry a failed payment.
     * The workflow must be in a "waiting for retry" state to accept this.
     */
    @SignalMethod
    fun retryPayment()

    /**
     * Query: getCurrentStatus
     *
     * Returns the current step in the workflow execution.
     * This is a Temporal Query — reads in-memory workflow state, no DB hit.
     */
    @QueryMethod
    fun getCurrentStatus(): String

    /**
     * Query: getProgress
     *
     * Returns detailed progress information including retry count.
     */
    @QueryMethod
    fun getProgress(): WorkflowProgress

    data class WorkflowProgress(
        val status: String,
        val currentStep: String,
        val retryCount: Int,
        val cancellationRequested: Boolean,
        val failureReason: String,
    )
}
