package com.example.inventory.domain.model

/** Thrown when a reserve() request exceeds available stock. Non-retryable. */
class InsufficientStockException(message: String) : RuntimeException(message)
