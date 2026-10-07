package com.example.ordermanagement.domain.exception

/**
 * Thrown when a command is issued to an aggregate in an incompatible state.
 * E.g., trying to add items to a CONFIRMED order.
 * Maps to HTTP 409 Conflict.
 */
class InvalidStateTransitionException(message: String) : DomainException(message)
