package com.example.ordermanagement.api.dto.response

import java.time.Instant
import java.util.UUID

/**
 * REST Response DTO for individual domain events.
 * Exposes the raw event structure for audit/debugging.
 */
data class EventHistoryResponse(
    val eventId: UUID,
    val aggregateId: String,
    val eventType: String,
    val version: Long,
    val occurredAt: Instant,
    val payload: Any // Raw event — serialized as JSON
)
