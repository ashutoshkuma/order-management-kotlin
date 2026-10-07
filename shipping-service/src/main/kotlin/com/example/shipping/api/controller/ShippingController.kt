package com.example.shipping.api.controller

import com.example.contracts.api.ShippingContracts
import com.example.shipping.application.service.ShippingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Shipping", description = "Shipment creation and delivery confirmation")
class ShippingController(
    private val shippingService: ShippingService,
) {

    @Operation(summary = "Create a shipment for an order")
    @PostMapping("/shipments")
    fun createShipment(
        @Valid @RequestBody request: ShippingContracts.CreateShipmentRequest,
    ): ResponseEntity<ShippingContracts.CreateShipmentResponse> =
        ResponseEntity.ok(shippingService.createShipment(request))

    @Operation(
        summary = "Get shipment status by ID",
        description = "Used by ShippingActivityImpl to poll delivery status.",
    )
    @GetMapping("/shipments/{shipmentId}")
    fun getShipment(
        @PathVariable shipmentId: String,
    ): ResponseEntity<ShippingContracts.ShipmentStatusResponse> =
        ResponseEntity.ok(shippingService.getShipment(shipmentId))

    @Operation(summary = "Confirm delivery (simulates carrier webhook)")
    @PostMapping("/deliveries/{shipmentId}/confirm")
    fun confirmDelivery(
        @PathVariable shipmentId: String,
    ): ResponseEntity<ShippingContracts.ConfirmDeliveryResponse> =
        ResponseEntity.ok(shippingService.confirmDelivery(shipmentId))
}
