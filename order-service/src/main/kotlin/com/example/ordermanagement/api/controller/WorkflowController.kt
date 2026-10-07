package com.example.ordermanagement.api.controller

import com.example.ordermanagement.domain.port.inbound.OrderQueryUseCase
import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST Controller: WorkflowController
 *
 * Direct interaction with Temporal workflows — query state and send signals.
 * workflowId format: "order-fulfillment-{orderId}"
 */
@RestController
@RequestMapping("/workflows")
@Tag(
    name = "Workflows",
    description = """
        Direct Temporal workflow interaction. The `workflowId` follows the pattern
        `order-fulfillment-{orderId}` and is returned by `POST /orders/{id}/confirm`.

        **Query** reads live in-memory workflow state (no DB, no event replay).
        **Signals** deliver asynchronous messages to a running workflow.

        Open the **Temporal UI at http://localhost:8088** to visualize all workflow executions.
        """
)
class WorkflowController(
    private val queryService: OrderQueryUseCase,
    private val workflowPort: WorkflowPort
) {

    companion object {
        private val log = LoggerFactory.getLogger(WorkflowController::class.java)
    }

    @Operation(
        summary = "Query workflow status",
        description = """
                Reads the **live in-memory state** of a Temporal workflow via a Temporal Query.

                This is fundamentally different from reading the event store:
                - **Event store query**: reads persisted domain events from PostgreSQL
                - **Temporal query**: reads current variables inside the running workflow process (no DB hit)

                Returns: `status`, `currentStep`, `retryCount`, and whether cancellation was requested.

                Open **http://localhost:8088** for the full graphical workflow execution view.
                """
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "Workflow status",
            content = [Content(schema = Schema(implementation = WorkflowPort.WorkflowStatusResult::class))]
        ),
        ApiResponse(responseCode = "200", description = "Returns UNKNOWN status if workflow is not found or already completed")
    )
    @GetMapping("/{workflowId}")
    fun getWorkflowStatus(
        @Parameter(description = "Temporal workflow ID — format: order-fulfillment-{orderId}", required = true)
        @PathVariable workflowId: String
    ): ResponseEntity<WorkflowPort.WorkflowStatusResult> {
        return ResponseEntity.ok(queryService.getWorkflowStatus(workflowId))
    }

    @Operation(
        summary = "Send CancelOrder signal",
        description = """
                Sends a **CancelOrder signal** to the running Temporal workflow.

                Temporal delivers signals asynchronously — even if the workflow is
                currently mid-activity, the signal is queued and processed at the
                next safe checkpoint.

                The workflow then executes saga compensation:
                - If inventory was reserved → `releaseInventory` (compensating transaction)
                - If payment was taken → `refundPayment` (compensating transaction)
                - Finally records `OrderCancelledEvent` in the event store
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Cancel signal sent — compensation will run asynchronously"),
        ApiResponse(responseCode = "200", description = "Signal silently ignored if workflow already completed")
    )
    @PostMapping("/{workflowId}/signal/cancel")
    fun sendCancelSignal(
        @Parameter(description = "Temporal workflow ID", required = true)
        @PathVariable workflowId: String,
        @RequestBody body: Map<String, String>
    ): ResponseEntity<Map<String, String>> {
        val reason = body.getOrDefault("reason", "Manual cancellation via API")
        log.info("POST /workflows/{}/signal/cancel — reason={}", workflowId, reason)
        workflowPort.sendCancelSignal(workflowId, reason)
        return ResponseEntity.accepted().body(mapOf("message" to "Cancel signal sent", "workflowId" to workflowId))
    }

    @Operation(
        summary = "Send RetryPayment signal",
        description = """
                Sends a **RetryPayment signal** to a workflow waiting for customer action
                after a transient payment failure.

                The workflow uses `Workflow.await(Duration.ofMinutes(5), () -> retryRequested)`
                to pause execution. This signal sets `retryRequested = true`, waking the workflow
                so it re-attempts the `PaymentActivity`.

                Has no effect if the workflow is not in `WAITING_FOR_PAYMENT_RETRY` state.
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "RetryPayment signal sent")
    )
    @PostMapping("/{workflowId}/signal/retry-payment")
    fun sendRetryPaymentSignal(
        @Parameter(description = "Temporal workflow ID", required = true)
        @PathVariable workflowId: String
    ): ResponseEntity<Map<String, String>> {
        log.info("POST /workflows/{}/signal/retry-payment", workflowId)
        workflowPort.sendRetryPaymentSignal(workflowId)
        return ResponseEntity.accepted().body(mapOf("message" to "RetryPayment signal sent", "workflowId" to workflowId))
    }
}
