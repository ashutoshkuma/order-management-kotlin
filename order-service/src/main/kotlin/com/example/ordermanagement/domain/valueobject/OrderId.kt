package com.example.ordermanagement.domain.valueobject

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

/**
 * Value Object: OrderId
 *
 * Why a value object instead of a raw UUID?
 * - Type safety: you cannot accidentally pass a CustomerId where an OrderId is expected
 * - Self-documenting code: method signatures are unambiguous
 * - Encapsulates the ID generation strategy (UUID here, could be ULID in production)
 * - Jackson annotations allow transparent JSON serialization as a simple string
 *
 * DDD Principle: Value Objects are immutable and defined by their value, not identity.
 */
data class OrderId(val value: UUID) {

    companion object {
        /**
         * Factory method for generating new OrderIds.
         * Centralizing generation here means if we ever switch from UUID to ULID,
         * only this class needs changing.
         */
        @JvmStatic
        fun generate(): OrderId = OrderId(UUID.randomUUID())

        @JvmStatic
        @JsonCreator
        fun of(value: String): OrderId = OrderId(UUID.fromString(value))

        @JvmStatic
        fun of(value: UUID): OrderId = OrderId(value)
    }

    @JsonValue
    override fun toString(): String = value.toString()
}
