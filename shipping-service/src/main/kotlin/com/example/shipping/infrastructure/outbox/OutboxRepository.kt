package com.example.shipping.infrastructure.outbox

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OutboxRepository : CrudRepository<OutboxEntry, Long> {

    @Query("SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100")
    fun findUnpublished(): List<OutboxEntry>

    @Modifying
    @Query("UPDATE outbox_events SET published_at = :publishedAt WHERE id = :id AND published_at IS NULL")
    fun markPublished(@Param("id") id: Long, @Param("publishedAt") publishedAt: Instant): Int
}
