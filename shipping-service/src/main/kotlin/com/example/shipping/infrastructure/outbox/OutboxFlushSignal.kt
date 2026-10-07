package com.example.shipping.infrastructure.outbox

/**
 * Marker event published AFTER_COMMIT to trigger the fast-path outbox relay.
 * Kotlin has no zero-component data class (unlike the original Java record),
 * so this is a plain class.
 */
class OutboxFlushSignal
