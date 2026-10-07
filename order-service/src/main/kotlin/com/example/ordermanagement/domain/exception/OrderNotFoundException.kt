package com.example.ordermanagement.domain.exception

/**
 * Thrown when an order cannot be found by its ID.
 * Maps to HTTP 404 Not Found.
 */
class OrderNotFoundException(orderId: String) : DomainException("Order not found: $orderId")
