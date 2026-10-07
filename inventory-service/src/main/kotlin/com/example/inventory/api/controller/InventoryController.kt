package com.example.inventory.api.controller

import com.example.contracts.api.InventoryContracts
import com.example.inventory.application.service.InventoryService
import com.example.inventory.domain.model.InsufficientStockException
import com.example.inventory.domain.model.InventoryItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.NoSuchElementException

@RestController
@Tag(name = "Inventory", description = "Stock reservation and release for order fulfillment")
class InventoryController(private val inventoryService: InventoryService) {

    @Operation(
        summary = "Reserve inventory for an order",
        description = "Called by order-service InventoryActivity. " +
            "Idempotent: same orderId always returns same reservationId.",
    )
    @PostMapping("/reserve")
    fun reserve(
        @Valid @RequestBody request: InventoryContracts.ReserveInventoryRequest,
    ): ResponseEntity<InventoryContracts.ReserveInventoryResponse> =
        ResponseEntity.ok(inventoryService.reserve(request))

    @Operation(summary = "Release a reservation (saga compensation)")
    @DeleteMapping("/reserve/{reservationId}")
    fun release(@PathVariable reservationId: String): ResponseEntity<InventoryContracts.ReleaseInventoryResponse> =
        ResponseEntity.ok(inventoryService.release(reservationId))

    // ─── CRUD: Inventory Items ────────────────────────────────────────

    @Operation(summary = "List all inventory items")
    @GetMapping("/inventory")
    fun listItems(): ResponseEntity<List<InventoryItemResponse>> =
        ResponseEntity.ok(inventoryService.getAllItems().map { InventoryItemResponse.from(it) })

    @Operation(summary = "Get a single inventory item by productId")
    @GetMapping("/inventory/{productId}")
    fun getItem(@PathVariable productId: String): ResponseEntity<InventoryItemResponse> =
        ResponseEntity.ok(InventoryItemResponse.from(inventoryService.getItem(productId)))

    @Operation(summary = "Create a new inventory item")
    @PostMapping("/inventory")
    fun createItem(@Valid @RequestBody request: CreateItemRequest): ResponseEntity<InventoryItemResponse> {
        val created = inventoryService.createItem(request.productId, request.productName, request.quantityAvailable)
        return ResponseEntity.status(HttpStatus.CREATED).body(InventoryItemResponse.from(created))
    }

    @Operation(summary = "Update an inventory item's name or available quantity")
    @PutMapping("/inventory/{productId}")
    fun updateItem(
        @PathVariable productId: String,
        @RequestBody request: UpdateItemRequest,
    ): ResponseEntity<InventoryItemResponse> {
        val updated = inventoryService.updateItem(productId, request.productName, request.quantityAvailable)
        return ResponseEntity.ok(InventoryItemResponse.from(updated))
    }

    @Operation(summary = "Delete an inventory item")
    @DeleteMapping("/inventory/{productId}")
    fun deleteItem(@PathVariable productId: String): ResponseEntity<Void> {
        inventoryService.deleteItem(productId)
        return ResponseEntity.noContent().build()
    }

    // ─── Exception handlers ───────────────────────────────────────────

    @ExceptionHandler(InsufficientStockException::class)
    fun handleInsufficientStock(ex: InsufficientStockException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ex.message)

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.message)

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ex.message)

    // ─── DTOs ─────────────────────────────────────────────────────────

    data class CreateItemRequest(
        @field:NotBlank val productId: String,
        @field:NotBlank val productName: String,
        @field:Min(0) val quantityAvailable: Int,
    )

    data class UpdateItemRequest(
        val productName: String?, // null = keep existing
        val quantityAvailable: Int?, // null = keep existing
    )

    data class InventoryItemResponse(
        val productId: String,
        val productName: String,
        val quantityAvailable: Int,
        val quantityReserved: Int,
        val updatedAt: Instant,
    ) {
        companion object {
            fun from(item: InventoryItem): InventoryItemResponse =
                InventoryItemResponse(
                    item.productId, item.productName,
                    item.quantityAvailable, item.quantityReserved,
                    item.updatedAt)
        }
    }
}
