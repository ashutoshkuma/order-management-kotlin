package com.example.ordermanagement.domain.model

/**
 * Enum: ShipmentStatus
 *
 * Tracks the shipment sub-status independently.
 * In a real system, this might be managed by a separate Shipping bounded context.
 * Here we capture enough state for the order saga to make decisions.
 */
enum class ShipmentStatus {
    NOT_CREATED,
    CREATED,
    IN_TRANSIT,
    DELIVERED,
    FAILED
}
