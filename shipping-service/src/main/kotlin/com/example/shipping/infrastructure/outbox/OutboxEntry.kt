package com.example.shipping.infrastructure.outbox

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("outbox_events")
class OutboxEntry(
    @Id
    private val id: Long?,
    val eventId: UUID,
    val aggregateId: String,
    val eventType: String,
    val topic: String,
    val payload: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
    @Transient
    private val isNew: Boolean = false,
) : Persistable<Long> {

    // See Shipment for why this dedicated persistence constructor (omitting
    // the @Transient flag) is required for Spring Data JDBC's Kotlin
    // row-materialization to work.
    @PersistenceCreator
    constructor(
        id: Long?,
        eventId: UUID,
        aggregateId: String,
        eventType: String,
        topic: String,
        payload: String,
        createdAt: Instant,
        publishedAt: Instant?,
    ) : this(id, eventId, aggregateId, eventType, topic, payload, createdAt, publishedAt, false)

    override fun getId(): Long? = id
    override fun isNew(): Boolean = isNew

    companion object {
        fun create(eventId: UUID, aggregateId: String, eventType: String, topic: String, payload: String): OutboxEntry =
            OutboxEntry(null, eventId, aggregateId, eventType, topic, payload, Instant.now(), null, isNew = true)
    }
}
