package com.example.ordermanagement.api.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

data class AddItemRequest(
    @field:NotNull
    val productId: UUID,

    @field:NotBlank
    val productName: String,

    @field:Min(1)
    val quantity: Int,

    @field:NotNull
    @field:DecimalMin("0.01")
    val unitPrice: BigDecimal
)
