package com.example.inventory.infrastructure.persistence

import com.example.inventory.domain.model.Reservation
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Spring Data JDBC Repository for Reservation persistence.
 *
 * Methods:
 *   - findByOrderId: idempotency check (returns existing if already reserved)
 *   - findById: load reservation by ID
 *   - save: persist new or updated reservation
 */
@Repository
interface ReservationRepository : CrudRepository<Reservation, String> {
    fun findByOrderId(orderId: String): Optional<Reservation>
}
