package com.example.contracts.api

import jakarta.validation.constraints.NotBlank

/**
 * HTTP API contracts for the Shipping Service.
 */
object ShippingContracts {

    data class CreateShipmentRequest(
        @field:NotBlank val orderId: String,
        @field:NotBlank val shippingAddress: String,
    )

    data class CreateShipmentResponse(
        val shipmentId: String,
        val trackingNumber: String,
        val carrier: String,
        val status: String, // "CREATED"
    )

    data class ShipmentStatusResponse(
        val shipmentId: String,
        val status: String, // "CREATED" | "IN_TRANSIT" | "DELIVERED"
        val trackingNumber: String,
        val carrier: String,
    )

    data class ConfirmDeliveryResponse(
        val shipmentId: String,
        val status: String, // "DELIVERED"
    )
}
