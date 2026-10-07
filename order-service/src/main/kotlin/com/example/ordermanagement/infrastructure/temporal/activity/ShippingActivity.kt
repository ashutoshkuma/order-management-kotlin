package com.example.ordermanagement.infrastructure.temporal.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Activity Interface: ShippingActivity
 *
 * Handles shipment creation and delivery tracking.
 * In a real system, this would integrate with carrier APIs (FedEx, UPS, etc.).
 */
@ActivityInterface
interface ShippingActivity {

    /**
     * Creates a shipment with the carrier.
     * Returns tracking information.
     */
    @ActivityMethod
    fun createShipment(orderId: String): ShipmentResult

    /**
     * Confirms delivery (simulates carrier webhook callback).
     * In reality, this would wait for an async signal from the carrier.
     */
    @ActivityMethod
    fun confirmDelivery(orderId: String, shipmentId: String)

    @ActivityMethod
    fun recordShipmentCreated(orderId: String, shipmentId: String, trackingNumber: String, carrier: String)

    @ActivityMethod
    fun recordShipmentDelivered(orderId: String, shipmentId: String)

    data class ShipmentResult(val shipmentId: String, val trackingNumber: String, val carrier: String)
}
