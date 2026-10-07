package com.example.ordermanagement.domain.valueobject

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/**
 * Value Object: CustomerId
 *
 * Represents the identity of a customer in the order domain.
 * In a real microservices environment, this would be the ID used to call
 * the Customer bounded context for customer details.
 */
data class CustomerId(val value: UUID) {

    companion object {
        @JvmStatic
        fun generate(): CustomerId = CustomerId(UUID.randomUUID())

        @JvmStatic
        @JsonCreator
        fun of(value: String): CustomerId = CustomerId(UUID.fromString(value))

        @JvmStatic
        fun of(value: UUID): CustomerId = CustomerId(value)
    }

    @JsonValue
    override fun toString(): String = value.toString()
}
