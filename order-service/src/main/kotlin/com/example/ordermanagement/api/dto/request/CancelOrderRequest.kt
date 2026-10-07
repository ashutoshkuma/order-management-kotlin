package com.example.ordermanagement.api.dto.request

import jakarta.validation.constraints.NotBlank

data class CancelOrderRequest(
    @field:NotBlank(message = "Cancellation reason is required")
    val reason: String
)
