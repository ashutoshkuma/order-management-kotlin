package com.example.ordermanagement.api.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * REST Request DTO for POST /orders
 *
 * DTOs are the API contract — they're decoupled from domain objects.
 * If the domain model changes (e.g., shipping address becomes a structured object),
 * the API contract can stay stable or evolve independently.
 *
 * NOTE ON CONVERSION FROM JAVA:
 * Bean Validation annotations use the `@field:` use-site target so Hibernate
 * Validator sees them on the backing field (matching what it saw on the Java
 * record's canonical constructor parameter) rather than only on the Kotlin
 * primary-constructor parameter, which Hibernate Validator does not inspect.
 */
data class CreateOrderRequest(
    @field:NotNull(message = "customerId is required")
    val customerId: UUID,

    @field:NotBlank(message = "shippingAddress is required")
    val shippingAddress: String
)
