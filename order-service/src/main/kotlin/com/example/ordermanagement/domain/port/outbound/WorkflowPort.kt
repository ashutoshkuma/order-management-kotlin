package com.example.ordermanagement.domain.port.outbound

import com.example.ordermanagement.domain.valueobject.OrderId

/**
 * Output Port: WorkflowPort
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY A PORT FOR TEMPORAL?
 * ═══════════════════════════════════════════════════════════════════
 * The domain/application layer should not know about Temporal directly.
 * By defining this port, we:
 *   1. Keep the domain testable without a running Temporal server
 *   2. Allow swapping Temporal for another orchestrator theoretically
 *   3. Maintain clean hexagonal architecture
 *
 * The Temporal adapter (WorkflowPortAdapter) implements this interface
 * and lives in the infrastructure layer.
 */
interface WorkflowPort {

    /**
     * Starts the OrderFulfillmentWorkflow for a confirmed order.
     *
     * @param orderId    The order to fulfill
     * @param workflowId The workflow ID (pre-generated so we can store it in the event)
     * @return The started workflow ID (same as workflowId parameter)
     */
    fun startFulfillmentWorkflow(orderId: OrderId, workflowId: String): String

    /**
     * Sends a CancelOrder signal to a running workflow.
     * Temporal delivers this signal to the workflow even if it's currently
     * executing an activity — it will be processed at the next checkpoint.
     */
    fun sendCancelSignal(workflowId: String, reason: String)

    /**
     * Sends a RetryPayment signal to a workflow waiting for payment retry.
     */
    fun sendRetryPaymentSignal(workflowId: String)

    /**
     * Queries the current status from the workflow's in-memory state.
     * This is a lightweight read that does NOT replay events.
     */
    fun queryWorkflowStatus(workflowId: String): WorkflowStatusResult

    data class WorkflowStatusResult(
        val workflowId: String,
        val status: String,
        val currentStep: String,
        val retryCount: Int
    )
}
