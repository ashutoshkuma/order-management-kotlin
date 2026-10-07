package com.example.ordermanagement.api.controller

import com.example.ordermanagement.api.dto.request.AddItemRequest
import com.example.ordermanagement.api.dto.request.CancelOrderRequest
import com.example.ordermanagement.api.dto.request.CreateOrderRequest
import com.example.ordermanagement.api.dto.response.EventHistoryResponse
import com.example.ordermanagement.api.dto.response.OrderResponse
import com.example.ordermanagement.api.dto.response.OrderSummaryResponse
import com.example.ordermanagement.api.mapper.OrderMapper
import com.example.ordermanagement.domain.command.AddItemCommand
import com.example.ordermanagement.domain.command.CancelOrderCommand
import com.example.ordermanagement.domain.command.ConfirmOrderCommand
import com.example.ordermanagement.domain.command.CreateOrderCommand
import com.example.ordermanagement.domain.command.RemoveItemCommand
import com.example.ordermanagement.domain.port.inbound.OrderCommandUseCase
import com.example.ordermanagement.domain.port.inbound.OrderQueryUseCase
import com.example.ordermanagement.domain.port.outbound.WorkflowPort
import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * REST Controller: OrderController
 *
 * No business logic here — delegates everything to application services.
 * Business rules live in the Order aggregate.
 * Workflow orchestration lives in Temporal.
 */
@RestController
@RequestMapping("/orders")
@Tag(
    name = "Orders",
    description = """
        Order lifecycle management. State is **never stored directly** — it is
        always rebuilt by replaying immutable domain events from the append-only
        event store (PostgreSQL JSONB). Use `/history` and `/timeline` to see
        every state change that ever happened.
        """
)
class OrderController(
    private val commandService: OrderCommandUseCase,
    private val queryService: OrderQueryUseCase,
    private val orderMapper: OrderMapper,
    private val workflowPort: WorkflowPort
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderController::class.java)
    }

    // ═══════════════════════════════════════════════════════
    // COMMAND ENDPOINTS
    // ═══════════════════════════════════════════════════════

    @Operation(
        summary = "Create a new order",
        description = "Creates an order in **DRAFT** status. Returns the new `orderId`. " +
            "Stored event: `OrderCreatedEvent` (version 1)."
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Order created — Location header points to the new resource"),
        ApiResponse(responseCode = "400", description = "Validation error — customerId or shippingAddress missing")
    )
    @PostMapping
    fun createOrder(@Valid @RequestBody request: CreateOrderRequest): ResponseEntity<Map<String, String>> {
        log.info("POST /orders — customerId={}", request.customerId)
        val command = CreateOrderCommand(
            OrderId.generate(),
            CustomerId.of(request.customerId),
            request.shippingAddress
        )
        val orderId = commandService.createOrder(command)
        return ResponseEntity
            .created(URI.create("/orders/$orderId"))
            .body(mapOf("orderId" to orderId.toString()))
    }

    @Operation(
        summary = "Add an item to a DRAFT order",
        description = "Adds a product line item. Duplicate productIds are merged (quantities summed). " +
            "Stored event: `ItemAddedEvent`. Only valid while order is in **DRAFT** status."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Item added"),
        ApiResponse(responseCode = "400", description = "Validation error or order not in DRAFT status"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @PostMapping("/{orderId}/items")
    fun addItem(
        @Parameter(description = "Order UUID", required = true) @PathVariable orderId: UUID,
        @Valid @RequestBody request: AddItemRequest
    ): ResponseEntity<Void> {
        log.debug("POST /orders/{}/items — productId={}", orderId, request.productId)
        val command = AddItemCommand(
            OrderId.of(orderId),
            request.productId,
            request.productName,
            request.quantity,
            Money.of(request.unitPrice)
        )
        commandService.addItem(command)
        return ResponseEntity.ok().build()
    }

    @Operation(
        summary = "Remove an item from a DRAFT order",
        description = "Removes a product line item by productId. " +
            "Stored event: `ItemRemovedEvent`. Only valid while order is in **DRAFT** status."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Item removed"),
        ApiResponse(responseCode = "400", description = "Product not found in order, or order not in DRAFT"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @DeleteMapping("/{orderId}/items/{productId}")
    fun removeItem(@PathVariable orderId: UUID, @PathVariable productId: UUID): ResponseEntity<Void> {
        commandService.removeItem(RemoveItemCommand(OrderId.of(orderId), productId))
        return ResponseEntity.ok().build()
    }

    @Operation(
        summary = "Confirm the order and start Temporal workflow",
        description = """
                Transitions order from **DRAFT → CONFIRMED** and launches the
                `OrderFulfillmentWorkflow` in Temporal.

                The workflow orchestrates: Reserve Inventory → Process Payment →
                Create Shipment → Deliver, with automatic saga compensation on failures.

                Returns **202 Accepted** because fulfillment is asynchronous.
                Poll `GET /orders/{id}` or `GET /workflows/{workflowId}` to track progress.

                Stored event: `OrderConfirmedEvent` (includes workflowId).
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Order confirmed — Temporal workflow started"),
        ApiResponse(responseCode = "400", description = "Order is empty or not in DRAFT status"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @PostMapping("/{orderId}/confirm")
    fun confirmOrder(
        @Parameter(description = "Order UUID", required = true) @PathVariable orderId: UUID
    ): ResponseEntity<Map<String, String>> {
        log.info("POST /orders/{}/confirm", orderId)
        commandService.confirmOrder(ConfirmOrderCommand(OrderId.of(orderId)))
        return ResponseEntity.accepted()
            .body(
                mapOf(
                    "message" to "Order confirmed. Fulfillment workflow started.",
                    "orderId" to orderId.toString(),
                    "workflowId" to "order-fulfillment-$orderId"
                )
            )
    }

    @Operation(
        summary = "Cancel the order",
        description = """
                Cancels the order from any non-terminal state.

                If a Temporal workflow is active, a **CancelOrder signal** is sent to it.
                Temporal then runs the appropriate saga compensation:
                - If inventory was reserved → releases it
                - If payment was taken → issues a refund

                Stored event: `OrderCancelledEvent` (by CUSTOMER).
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Order cancelled"),
        ApiResponse(responseCode = "400", description = "Order is already in a terminal state (DELIVERED or CANCELLED)"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @PostMapping("/{orderId}/cancel")
    fun cancelOrder(
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: CancelOrderRequest
    ): ResponseEntity<Void> {
        log.info("POST /orders/{}/cancel — reason={}", orderId, request.reason)
        commandService.cancelOrder(CancelOrderCommand(OrderId.of(orderId), request.reason))
        return ResponseEntity.ok().build()
    }

    @Operation(
        summary = "Record a payment (webhook / manual)",
        description = "Records a `PaymentCompletedEvent` directly — used for webhook-based payment flows " +
            "outside the standard Temporal workflow. In the normal flow, payment is handled " +
            "automatically by `PaymentActivity`."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment recorded"),
        ApiResponse(responseCode = "400", description = "Missing transactionId or order not in correct state"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @PostMapping("/{orderId}/payment")
    fun recordPayment(
        @PathVariable orderId: UUID,
        @RequestBody body: Map<String, String>
    ): ResponseEntity<Map<String, String>> {
        val transactionId = body["transactionId"]
        if (transactionId.isNullOrBlank()) {
            return ResponseEntity.badRequest().body(mapOf("error" to "transactionId is required"))
        }
        commandService.recordPaymentCompleted(OrderId.of(orderId), transactionId)
        return ResponseEntity.ok(mapOf("message" to "Payment recorded", "transactionId" to transactionId))
    }

    @Operation(
        summary = "Retry payment (send Temporal signal)",
        description = """
                Sends a **RetryPayment signal** to the running Temporal workflow.
                Only effective when the workflow is in `WAITING_FOR_PAYMENT_RETRY` state
                (after a transient payment failure, before the 5-minute timeout).

                The workflow resumes and retries the `PaymentActivity`.
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "RetryPayment signal sent to workflow"),
        ApiResponse(responseCode = "400", description = "Order has no active workflow"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @PostMapping("/{orderId}/retry-payment")
    fun retryPayment(@PathVariable orderId: UUID): ResponseEntity<Map<String, String>> {
        log.info("POST /orders/{}/retry-payment", orderId)
        val order = queryService.getOrder(OrderId.of(orderId))
        val workflowId = order.workflowId
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "No active workflow for this order"))
        workflowPort.sendRetryPaymentSignal(workflowId)
        return ResponseEntity.accepted().body(mapOf("message" to "Retry payment signal sent to workflow"))
    }

    // ═══════════════════════════════════════════════════════
    // QUERY ENDPOINTS
    // ═══════════════════════════════════════════════════════

    @Operation(
        summary = "List orders",
        description = """
                Returns a paginated list of orders from the **read model** (order_summary projection).

                Results come from a denormalized projection table updated by `OrderProjectionUpdater`
                on every domain event — no event replay needed. Sub-millisecond queries regardless
                of how many events each order has accumulated.

                **Filters** (all optional, combinable):
                - `status` — comma-separated list: `DRAFT,CONFIRMED,INVENTORY_RESERVED,PAYMENT_COMPLETED,SHIPPED,DELIVERED,CANCELLED`
                - `customerId` — filter to a single customer's orders

                **Pagination**: `page` (0-based) and `size` (default 20, max 100).
                Results are ordered by `createdAt DESC`.
                """
    )
    @ApiResponse(responseCode = "200", description = "Paginated order list")
    @GetMapping
    fun listOrders(
        @Parameter(description = "Comma-separated statuses, e.g. DELIVERED,CANCELLED")
        @RequestParam(required = false) status: String?,
        @Parameter(description = "Filter by customer UUID")
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<Page<OrderSummaryResponse>> {
        // OrderQueryUseCase.listOrders declares non-null List<String>/String
        // parameters (see domain/port/inbound/OrderQueryUseCase.kt) — an empty
        // list / blank string stands in for "no filter" instead of null.
        val statuses = if (!status.isNullOrBlank()) status.split(",") else emptyList()
        val customerIdStr = customerId?.toString() ?: ""
        val clampedSize = minOf(size, 100)

        val result = queryService.listOrders(
            statuses,
            customerIdStr,
            PageRequest.of(page, clampedSize, Sort.by("created_at").descending())
        )

        return ResponseEntity.ok(result)
    }

    @Operation(
        summary = "Get current order state",
        description = """
                Returns the current state of the order.

                **How it works (Event Sourcing):** The order is NOT read from a 'current state' table.
                Instead, all `DomainEvent`s for this orderId are loaded from the `event_store`
                table and replayed in sequence. The final in-memory state is returned.

                If a snapshot exists (taken every 50 events), it is loaded first and only
                subsequent events are replayed — bounding replay to at most 50 events.
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Order state"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @GetMapping("/{orderId}")
    fun getOrder(
        @Parameter(description = "Order UUID", required = true) @PathVariable orderId: UUID
    ): ResponseEntity<OrderResponse> {
        val order = queryService.getOrder(OrderId.of(orderId))
        return ResponseEntity.ok(orderMapper.toResponse(order))
    }

    @Operation(
        summary = "Get raw event history",
        description = """
                Returns every domain event ever stored for this order, in version order.

                This is the **raw event store** — immutable facts, never updated, never deleted.
                Each entry includes: `eventId`, `eventType`, `version`, `occurredAt`, and full `payload`.

                Use this for: auditing, debugging, compliance, or understanding exactly what happened and when.
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "List of all domain events"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @GetMapping("/{orderId}/history")
    fun getOrderHistory(
        @Parameter(description = "Order UUID", required = true) @PathVariable orderId: UUID
    ): ResponseEntity<List<EventHistoryResponse>> {
        val events = queryService.getOrderHistory(OrderId.of(orderId))
        return ResponseEntity.ok(orderMapper.toEventHistoryResponses(events))
    }

    @Operation(
        summary = "Get human-readable event timeline",
        description = """
                Returns the same events as `/history` but formatted for human reading.
                Each entry has: `version`, `eventType`, `occurredAt`, and a plain-English `description`.

                Example description: *"Payment completed. Transaction: TXN-9051C2C1 Amount: $159.97"*
                """
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Timeline of events"),
        ApiResponse(responseCode = "404", description = "Order not found")
    )
    @GetMapping("/{orderId}/timeline")
    fun getOrderTimeline(
        @Parameter(description = "Order UUID", required = true) @PathVariable orderId: UUID
    ): ResponseEntity<List<OrderQueryUseCase.TimelineEntry>> {
        val timeline = queryService.getOrderTimeline(OrderId.of(orderId))
        return ResponseEntity.ok(timeline)
    }
}
