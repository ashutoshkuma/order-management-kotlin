package com.example.notification.service

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Redis-backed idempotency check for consumer deduplication.
 *
 * Key schema:  notification:processed:{eventId}
 * TTL:         24 hours — sufficient to cover broker redelivery windows after restarts.
 *
 * Uses SET NX (set-if-not-exists): atomic, no race condition between check and set.
 * Returns true on the FIRST occurrence of an eventId; false on duplicates.
 *
 * Transport-agnostic — this class doesn't care whether messages arrived via Kafka or
 * Solace, so its logic is unchanged from the original Java implementation.
 */
@Service
class EventIdempotencyService(
    private val redisTemplate: StringRedisTemplate,
) {

    companion object {
        private val TTL: Duration = Duration.ofHours(24)
        private const val KEY_PREFIX = "notification:processed:"
    }

    /**
     * Returns true if this is the first time the eventId has been seen.
     * Atomically marks the eventId as processed with a 24-hour TTL.
     */
    fun isFirstOccurrence(eventId: UUID): Boolean {
        val inserted = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + eventId, "1", TTL)
        return inserted == true
    }

    /**
     * Removes the processed mark for an eventId.
     * Called on processing failure so the next retry attempt is not skipped
     * by the idempotency check, allowing the retry loop to try again and
     * eventually route to the DMQ.
     */
    fun deleteOccurrence(eventId: UUID) {
        redisTemplate.delete(KEY_PREFIX + eventId)
    }
}
