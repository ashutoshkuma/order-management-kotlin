package com.example.ordermanagement.infrastructure.persistence

import com.example.ordermanagement.config.JacksonConfig
import com.example.ordermanagement.domain.aggregate.Order
import com.example.ordermanagement.domain.event.DomainEvent
import com.example.ordermanagement.domain.event.InventoryReservedEvent
import com.example.ordermanagement.domain.event.ItemAddedEvent
import com.example.ordermanagement.domain.event.OrderConfirmedEvent
import com.example.ordermanagement.domain.event.OrderCreatedEvent
import com.example.ordermanagement.domain.event.PaymentCompletedEvent
import com.example.ordermanagement.domain.event.ShipmentCreatedEvent
import com.example.ordermanagement.domain.event.ShipmentDeliveredEvent
import com.example.ordermanagement.domain.exception.OptimisticLockingException
import com.example.ordermanagement.domain.model.OrderStatus
import com.example.ordermanagement.domain.valueobject.CustomerId
import com.example.ordermanagement.domain.valueobject.Money
import com.example.ordermanagement.domain.valueobject.OrderId
import com.example.ordermanagement.domain.valueobject.OrderItem
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Integration Tests: EventStoreAdapter
 *
 * Tests the event store with a REAL PostgreSQL database via Testcontainers.
 * This is more valuable than mocking because we're testing:
 * - Actual SQL execution
 * - Flyway migration correctness
 * - JSON serialization/deserialization round-trips
 * - Optimistic locking constraint behavior
 *
 * TESTCONTAINERS:
 * Starts a fresh PostgreSQL container per test class.
 * Tests run in isolation with a clean database.
 * Docker must be running for these tests.
 *
 * These tests run slower than unit tests (~5s per class for container startup)
 * but provide high confidence in the persistence layer.
 */
@Tag("integration")
@Testcontainers
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EventStoreAdapter::class, JacksonConfig::class)
@DisplayName("EventStore Integration Tests")
class EventStoreIntegrationTest {

    @Autowired
    lateinit var eventStore: EventStoreAdapter

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("orderdb_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Test
    @DisplayName("Should append and load events correctly")
    fun shouldAppendAndLoadEvents() {
        val aggregateId = UUID.randomUUID().toString()
        val orderId = OrderId.of(aggregateId)
        val customerId = CustomerId.generate()

        val events: List<DomainEvent> = listOf(
            OrderCreatedEvent.create(orderId, customerId, "Test Address", 1L),
            ItemAddedEvent.create(orderId, OrderItem(UUID.randomUUID(), "Widget", 2, Money.of(10.00)), 2L)
        )

        eventStore.appendEvents(aggregateId, "Order", events, 0L)

        val loaded = eventStore.loadEvents(aggregateId)

        assertThat(loaded).hasSize(2)
        assertThat(loaded[0]).isInstanceOf(OrderCreatedEvent::class.java)
        assertThat(loaded[1]).isInstanceOf(ItemAddedEvent::class.java)
    }

    @Test
    @DisplayName("Should enforce optimistic locking")
    fun shouldEnforceOptimisticLocking() {
        val aggregateId = UUID.randomUUID().toString()
        val orderId = OrderId.of(aggregateId)
        val customerId = CustomerId.generate()

        // First append succeeds
        val firstEvents: List<DomainEvent> = listOf(
            OrderCreatedEvent.create(orderId, customerId, "Address", 1L)
        )
        eventStore.appendEvents(aggregateId, "Order", firstEvents, 0L)

        // Second append with wrong expected version fails
        val secondEvents: List<DomainEvent> = listOf(
            ItemAddedEvent.create(orderId, OrderItem(UUID.randomUUID(), "Widget", 1, Money.of(5.00)), 2L)
        )

        // Simulate concurrent modification: another request already wrote version 2
        // We expect version 1 but actual is 1 (correct), so first concurrent write wins
        val conflictingEvents: List<DomainEvent> = listOf(
            ItemAddedEvent.create(orderId, OrderItem(UUID.randomUUID(), "Conflicting Item", 1, Money.of(5.00)), 2L)
        )

        // First write at version 1 → succeeds
        eventStore.appendEvents(aggregateId, "Order", secondEvents, 1L)

        // Second write also claims expected version = 1, but current is now 2 → fails
        assertThatThrownBy { eventStore.appendEvents(aggregateId, "Order", conflictingEvents, 1L) }
            .isInstanceOf(OptimisticLockingException::class.java)
    }

    @Test
    @DisplayName("Should load events from specific version")
    fun shouldLoadEventsFromVersion() {
        val aggregateId = UUID.randomUUID().toString()
        val orderId = OrderId.of(aggregateId)
        val customerId = CustomerId.generate()

        // Append 3 events
        eventStore.appendEvents(
            aggregateId, "Order",
            listOf(OrderCreatedEvent.create(orderId, customerId, "Address", 1L)), 0L
        )

        eventStore.appendEvents(
            aggregateId, "Order",
            listOf(ItemAddedEvent.create(orderId, OrderItem(UUID.randomUUID(), "A", 1, Money.of(5.00)), 2L)), 1L
        )

        eventStore.appendEvents(
            aggregateId, "Order",
            listOf(OrderConfirmedEvent.create(orderId, Money.of(5.00), "wf-1", 3L)), 2L
        )

        // Load only events after version 1 (simulating post-snapshot load)
        val eventsAfterV1 = eventStore.loadEvents(aggregateId, 1L)
        assertThat(eventsAfterV1).hasSize(2)
        assertThat(eventsAfterV1[0]).isInstanceOf(ItemAddedEvent::class.java)
        assertThat(eventsAfterV1[1]).isInstanceOf(OrderConfirmedEvent::class.java)
    }

    @Test
    @DisplayName("Should correctly serialize and deserialize all event types")
    fun shouldSerializeAndDeserializeAllEventTypes() {
        val aggregateId = UUID.randomUUID().toString()
        val orderId = OrderId.of(aggregateId)
        val customerId = CustomerId.generate()
        val productId = UUID.randomUUID()

        // Build a complete lifecycle
        val lifecycle: List<DomainEvent> = listOf(
            OrderCreatedEvent.create(orderId, customerId, "123 Test St", 1L),
            ItemAddedEvent.create(orderId, OrderItem(productId, "Widget", 2, Money.of(10.00)), 2L),
            OrderConfirmedEvent.create(orderId, Money.of(20.00), "wf-serial", 3L),
            InventoryReservedEvent.create(orderId, "RES-SERIAL", 4L),
            PaymentCompletedEvent.create(orderId, "TXN-SERIAL", Money.of(20.00), 5L),
            ShipmentCreatedEvent.create(orderId, "SHIP-SERIAL", "TRACK-SERIAL", "UPS", 6L),
            ShipmentDeliveredEvent.create(orderId, "SHIP-SERIAL", 7L)
        )

        // Append each event
        for (i in lifecycle.indices) {
            eventStore.appendEvents(aggregateId, "Order", listOf(lifecycle[i]), i.toLong())
        }

        // Load and verify
        val loaded = eventStore.loadEvents(aggregateId)
        assertThat(loaded).hasSize(lifecycle.size)

        // Verify type preservation
        assertThat(loaded[0]).isInstanceOf(OrderCreatedEvent::class.java)
        assertThat(loaded[1]).isInstanceOf(ItemAddedEvent::class.java)
        assertThat(loaded[2]).isInstanceOf(OrderConfirmedEvent::class.java)
        assertThat(loaded[3]).isInstanceOf(InventoryReservedEvent::class.java)
        assertThat(loaded[4]).isInstanceOf(PaymentCompletedEvent::class.java)
        assertThat(loaded[5]).isInstanceOf(ShipmentCreatedEvent::class.java)
        assertThat(loaded[6]).isInstanceOf(ShipmentDeliveredEvent::class.java)
    }

    @Test
    @DisplayName("Should replay events to reconstruct order state")
    fun shouldReplayEventsToReconstructOrder() {
        val aggregateId = UUID.randomUUID().toString()
        val orderId = OrderId.of(aggregateId)
        val customerId = CustomerId.generate()

        // Create order through lifecycle and persist events
        val order = Order.create(orderId, customerId, "Replay Test Address")
        order.addItem(OrderItem(UUID.randomUUID(), "Widget", 3, Money.of(25.00)))
        order.confirm("wf-replay-test")
        order.reserveInventory("RES-REPLAY-TEST")

        val events = order.drainPendingEvents()
        eventStore.appendEvents(aggregateId, "Order", events, 0L)

        // Load events and reconstitute — this is the replay
        val loadedEvents = eventStore.loadEvents(aggregateId)
        val reconstructed = Order.reconstitute(loadedEvents)

        assertThat(reconstructed.status).isEqualTo(OrderStatus.INVENTORY_RESERVED)
        assertThat(reconstructed.totalAmount).isEqualTo(Money.of(75.00))
        assertThat(reconstructed.reservationId).isEqualTo("RES-REPLAY-TEST")
    }
}
