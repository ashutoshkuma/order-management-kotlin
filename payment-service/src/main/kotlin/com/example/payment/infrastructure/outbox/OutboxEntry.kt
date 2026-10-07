package com.example.payment.infrastructure.outbox

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("outbox_events")
data class OutboxEntry(
    @Id @Column("id") val entryId: Long?,
    val eventId: UUID,
    val aggregateId: String,
    val eventType: String,
    val topic: String,
    val payload: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
    @Transient val newRecord: Boolean = false,
) : Persistable<Long> {

    // See Transaction for why this dedicated persistence constructor
    // (omitting the @Transient flag) is required for Spring Data JDBC's
    // Kotlin row-materialization to work.
    @PersistenceCreator
    constructor(
        entryId: Long?,
        eventId: UUID,
        aggregateId: String,
        eventType: String,
        topic: String,
        payload: String,
        createdAt: Instant,
        publishedAt: Instant?,
    ) : this(entryId, eventId, aggregateId, eventType, topic, payload, createdAt, publishedAt, false)

    override fun getId(): Long? = entryId
    override fun isNew(): Boolean = newRecord

    companion object {
        fun create(eventId: UUID, aggregateId: String, eventType: String, topic: String, payload: String): OutboxEntry {
            return OutboxEntry(null, eventId, aggregateId, eventType, topic, payload, Instant.now(), null, true)
        }
    }
}
