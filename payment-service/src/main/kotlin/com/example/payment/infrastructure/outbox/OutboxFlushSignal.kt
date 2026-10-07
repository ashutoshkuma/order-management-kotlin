package com.example.payment.infrastructure.outbox

/**
 * Marker event published (via ApplicationEventPublisher) right after an outbox row is
 * written, so PaymentOutboxRelay's AFTER_COMMIT listener can pick it up immediately
 * instead of waiting for the 30s safety-net sweep. Carries no data — a zero-arg Kotlin
 * `data class` isn't allowed, so this is a plain marker class.
 */
class OutboxFlushSignal
