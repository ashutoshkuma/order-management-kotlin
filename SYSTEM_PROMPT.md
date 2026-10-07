# System Prompt — Order Management System

Use this prompt to give any AI model full context to understand, extend, or rebuild this application.

---

## What You Are Building

A **production-quality Order Management System** that demonstrates four enterprise patterns working together:

1. **Event Sourcing** — Every state change is an immutable domain event. State is never stored directly; it is rebuilt by replaying events.
2. **Temporal Workflows** — A durable Saga orchestrates the fulfillment lifecycle (inventory → payment → shipping) with automatic retries, crash recovery, and compensation.
3. **Hexagonal Architecture (Ports and Adapters)** — The domain has zero dependencies on Spring, Temporal, or any database. All external concerns plug in through interfaces.
4. **CQRS** — Commands and queries are handled by separate services, never mixed.

**Tech stack:** Kotlin (targeting JVM 21), Spring Boot 3.2.x, Temporal Java SDK 1.25.2, Spring Data JDBC, PostgreSQL 16, Flyway, Solace PubSub+, Kong API Gateway, MapStruct, springdoc-openapi 2.5.0.

**Package root:** `com.example.ordermanagement`

---

## Architecture: Strict Dependency Direction

```
API Layer  →  Application Layer  →  Domain Layer  ←  Infrastructure Layer
```

- **Domain Layer** — zero dependencies on Spring, Temporal, Solace, or any framework. Only Kotlin stdlib and Jackson annotations for serialization.
- **Application Layer** — depends only on Domain. Coordinates domain objects and calls outbound ports (interfaces).
- **Infrastructure Layer** — implements domain ports. Knows about Spring, Temporal SDK, JDBC, Solace.
- **API Layer** — depends only on Application. Controllers call application services only; never repositories or aggregates directly.

**Never violate this direction.** Business logic never goes in controllers. Repositories never contain business logic.

---

## Project Structure

```
order-service/src/main/kotlin/com/example/ordermanagement/
│
├── api/
│   ├── controller/
│   │   ├── OrderController.kt            POST/GET/DELETE endpoints for orders
│   │   ├── WorkflowController.kt         Temporal signal/query endpoints
│   │   └── GlobalExceptionHandler.kt     RFC 7807 ProblemDetail error responses
│   ├── dto/request/
│   │   ├── CreateOrderRequest.kt
│   │   ├── AddItemRequest.kt
│   │   └── CancelOrderRequest.kt
│   ├── dto/response/
│   │   ├── OrderResponse.kt
│   │   └── EventHistoryResponse.kt
│   └── mapper/OrderMapper.kt             MapStruct: domain ↔ DTO
│
├── application/service/
│   ├── OrderCommandService.kt            All writes (@Transactional)
│   └── OrderQueryService.kt              All reads (@Transactional(readOnly=true))
│
├── domain/
│   ├── aggregate/
│   │   ├── Order.kt                      Aggregate root (event sourcing)
│   │   └── OrderSnapshot.kt              Snapshot at every 50 events
│   ├── command/
│   │   ├── CreateOrderCommand.kt
│   │   ├── AddItemCommand.kt
│   │   ├── RemoveItemCommand.kt
│   │   ├── ConfirmOrderCommand.kt
│   │   └── CancelOrderCommand.kt
│   ├── event/
│   │   ├── DomainEvent.kt                Sealed interface (13 permitted subtypes)
│   │   ├── OrderCreatedEvent.kt
│   │   ├── ItemAddedEvent.kt
│   │   ├── ItemRemovedEvent.kt
│   │   ├── OrderConfirmedEvent.kt
│   │   ├── InventoryReservedEvent.kt
│   │   ├── InventoryReservationFailedEvent.kt
│   │   ├── InventoryReleasedEvent.kt
│   │   ├── PaymentCompletedEvent.kt
│   │   ├── PaymentFailedEvent.kt
│   │   ├── RefundCompletedEvent.kt
│   │   ├── ShipmentCreatedEvent.kt
│   │   ├── ShipmentDeliveredEvent.kt
│   │   └── OrderCancelledEvent.kt
│   ├── exception/
│   │   ├── DomainException.kt
│   │   ├── InvalidStateTransitionException.kt
│   │   ├── OptimisticLockingException.kt
│   │   └── OrderNotFoundException.kt
│   ├── model/
│   │   ├── OrderStatus.kt                DRAFT→CONFIRMED→INVENTORY_RESERVED→PAYMENT_COMPLETED→SHIPPED→DELIVERED|CANCELLED
│   │   ├── PaymentStatus.kt              PENDING→COMPLETED|FAILED|REFUNDED
│   │   └── ShipmentStatus.kt             NOT_CREATED→CREATED→DELIVERED
│   ├── port/outbound/
│   │   ├── EventStore.kt                 append / load by aggregateId
│   │   ├── SnapshotStore.kt              save / findLatest
│   │   ├── OrderRepository.kt            save / findById
│   │   └── WorkflowPort.kt               startFulfillment / sendSignal / query
│   └── valueobject/
│       ├── OrderId.kt                    Wraps UUID, no raw UUIDs in domain
│       ├── CustomerId.kt
│       ├── Money.kt                      BigDecimal, never double/float
│       └── OrderItem.kt                  productId, productName, quantity, unitPrice
│
└── infrastructure/
    ├── config/
    │   └── SimulationProperties.kt       Configurable failure rates (YAML-bound)
    ├── observability/
    │   └── CorrelationIdFilter.kt        X-Correlation-ID → MDC
    ├── persistence/
    │   ├── EventStoreAdapter.kt          Implements EventStore — append-only JSONB
    │   ├── SnapshotStoreAdapter.kt       Implements SnapshotStore
    │   └── OrderRepositoryAdapter.kt     Implements OrderRepository — snapshot+replay
    ├── solace/
    │   └── OrderEventSolacePublisher.kt  @TransactionalEventListener(AFTER_COMMIT)
    └── temporal/
        ├── WorkflowPortAdapter.kt        Implements WorkflowPort — Temporal SDK calls
        ├── worker/TemporalWorkerSetup.kt
        ├── activity/
        │   ├── InventoryActivity.kt      (interface)
        │   ├── InventoryActivityImpl.kt
        │   ├── PaymentActivity.kt
        │   ├── PaymentActivityImpl.kt
        │   ├── ShippingActivity.kt
        │   ├── ShippingActivityImpl.kt
        │   ├── NotificationActivity.kt
        │   ├── NotificationActivityImpl.kt
        │   ├── InsufficientFundsException.kt    (non-retryable)
        │   └── CardDeclinedException.kt         (non-retryable)
        └── workflow/
            ├── OrderFulfillmentWorkflow.kt      (interface: @WorkflowMethod, @SignalMethod, @QueryMethod)
            └── OrderFulfillmentWorkflowImpl.kt   (saga implementation)
```

---

## Domain: Event Sourcing Rules (Non-Negotiable)

### The Aggregate Contract

The `Order` aggregate is the single source of truth. These rules are absolute:

1. **State is never mutated directly.** Every change goes through a domain event.
2. **Commands raise events. Events change state.** The `apply(DomainEvent)` method is the ONLY place fields are mutated.
3. **`apply()` methods are side-effect free.** No DB calls, no HTTP calls, no logging inside them.
4. **Events are immutable data classes.** No setters, ever.
5. **`pendingEvents` is drained by the repository, not the service.** Call `order.save()` — the repository handles draining.

```kotlin
// CORRECT pattern:
fun addItem(item: OrderItem) {
    requireStatus(OrderStatus.DRAFT, "add items to")     // validate
    val event = ItemAddedEvent.create(id, item, version + 1)
    applyAndRecord(event)                                // raise event
}

private fun applyItemAdded(event: ItemAddedEvent) {      // apply = only mutation
    items.add(event.item)
    totalAmount = calculateTotal()
}

// WRONG: never do this
fun addItem(item: OrderItem) {
    items.add(item)  // ❌ direct mutation bypasses event sourcing
}
```

### Adding a New Domain Event — All 6 Steps Together

When adding a new event, all of these must be done as a unit or it will not compile:

1. Create a new `data class` in `domain/event/` implementing `DomainEvent`
2. Kotlin sealed interfaces have no `permits` clause — the new data class just needs to live in the same module as `DomainEvent` to be picked up as a variant automatically
3. Add a `@JsonSubTypes.Type` entry to `DomainEvent`
4. Add `is NewEvent -> applyNewEvent(it)` in `Order.apply()`'s `when` expression (compiler fails if missing)
5. Implement `private fun applyNewEvent(e: NewEvent)` on `Order`
6. Add the command handler method on `Order` that raises it

### Event Store Rules

- The `event_store` table is **append-only**. No UPDATE or DELETE SQL ever.
- `UNIQUE(aggregate_id, version)` enforces optimistic locking at the DB level.
- Events are stored as JSONB. `@JsonTypeInfo` type discriminator enables polymorphic deserialization.
- Loading order: check for snapshot → load events after snapshot version → replay.
- Snapshot threshold = 50 events. Snapshots are expendable (can be deleted and regenerated).

---

## Temporal Workflow: Determinism Rules (Critical)

Temporal replays workflow history on crash recovery. If the code is non-deterministic, replay diverges from actual history → corruption.

| ❌ NEVER use | ✅ Use instead |
|-------------|--------------|
| `System.currentTimeMillis()` | `Workflow.currentTimeMillis()` |
| `UUID.randomUUID()` | `Workflow.newRandom().nextLong()` |
| `new Random()` | `Workflow.newRandom()` |
| `Thread.sleep()` | `Workflow.sleep(Duration)` |
| Direct HTTP/DB calls | Activity stubs |
| `System.getenv()` | Pass as workflow parameters |
| `CompletableFuture`, `synchronized` | `Workflow.async()`, `Promise` |

### Saga Flow in OrderFulfillmentWorkflowImpl

```
fulfill(orderId)
│
├─ [cancel check] → compensateAndCancel()
│
├─ STEP 1: inventoryActivity.reserveInventory(orderId)
│   SUCCESS → store reservationId, recordInventoryReserved()
│   FAILURE → recordInventoryReservationFailed(), cancelOrderDirectly()
│
├─ [cancel check] → releaseInventory + cancelOrderDirectly
│
├─ STEP 2: paymentActivity.processPayment(orderId)
│   SUCCESS → store transactionId, recordPaymentCompleted()
│   TRANSIENT FAIL → Workflow.await(5min, () -> paymentRetryRequested || cancellationRequested)
│   NON-RETRYABLE (InsufficientFunds/CardDeclined) → compensateInventory + cancelOrderDirectly
│   RETRY EXHAUSTED → compensateInventory + cancelOrderDirectly
│
├─ [cancel check] → refundPayment + releaseInventory + cancelOrderDirectly
│
├─ STEP 3: shippingActivity.createShipment(orderId)
│   SUCCESS → store shipmentId, recordShipmentCreated()
│   FAILURE → compensatePayment + compensateInventory + cancelOrderDirectly
│
├─ STEP 4: shippingActivity.confirmDelivery(orderId, shipmentId)
│           shippingActivity.recordShipmentDelivered(orderId, shipmentId)
│
└─ STEP 5: notificationActivity.sendOrderDeliveredNotification(orderId)
           status = COMPLETED
```

### Signals and Queries

```kotlin
@SignalMethod fun cancelOrder(reason: String)     // sets cancellationRequested = true
@SignalMethod fun retryPayment()                  // sets paymentRetryRequested = true

@QueryMethod fun getCurrentStatus(): String              // returns status string
@QueryMethod fun getProgress(): WorkflowProgress         // status, currentStep, retryCount, failureReason
```

Signal handlers ONLY set primitive flags. They NEVER call activities.
Query handlers are ONLY read-only. They NEVER modify state.

### Activity Configuration

```kotlin
// Inventory: 3 retries, 2x backoff
ActivityOptions.newBuilder()
    .setStartToCloseTimeout(Duration.ofSeconds(30))
    .setRetryOptions(RetryOptions.newBuilder()
        .setMaximumAttempts(3).setBackoffCoefficient(2.0).build())
    .build()

// Payment: 2 retries, do-not-retry for InsufficientFunds/CardDeclined
// Use simple class name (not fully qualified) — Temporal uses simple names for type matching
.setDoNotRetry("InsufficientFundsException", "CardDeclinedException")

// Notifications: 2 retries (best-effort, fire-and-forget)
```

### Saga Compensation Table

| Forward Step | Compensation |
|---|---|
| `reserveInventory()` | `releaseInventory()` |
| `processPayment()` | `refundPayment()` |
| `createShipment()` | *(none — no side effect to undo)* |

Compensation always runs in reverse order. Compensation failures are logged but do not abort the chain.

---

## API Endpoints

| Method | Path | Response | Description |
|--------|------|----------|-------------|
| `POST` | `/orders` | 201 + `{orderId}` + Location | Create order in DRAFT |
| `POST` | `/orders/{id}/items` | 200 | Add item (DRAFT only) |
| `DELETE` | `/orders/{id}/items/{productId}` | 200 | Remove item (DRAFT only) |
| `POST` | `/orders/{id}/confirm` | 202 + `{workflowId}` | Confirm → start Temporal saga |
| `POST` | `/orders/{id}/cancel` | 200 | Cancel → send signal |
| `POST` | `/orders/{id}/payment` | 200 | Record payment (activity callback) |
| `POST` | `/orders/{id}/retry-payment` | 202 | Send retry signal |
| `GET` | `/orders/{id}` | 200 + OrderResponse | Current state (event replay) |
| `GET` | `/orders/{id}/history` | 200 + events[] | Raw event store records |
| `GET` | `/orders/{id}/timeline` | 200 + timeline[] | Human-readable event history |
| `GET` | `/workflows/{workflowId}` | 200 + WorkflowProgress | Live Temporal query (no DB) |
| `POST` | `/workflows/{id}/signal/cancel` | 200 | Direct cancel signal |
| `POST` | `/workflows/{id}/signal/retry-payment` | 202 | Retry payment signal |

**API rules:**
- Controllers use Bean Validation (`@Valid`, `@NotNull`). Never validate in services.
- All error responses use RFC 7807 `ProblemDetail` via `GlobalExceptionHandler`.
- `202 Accepted` for async operations. `201 Created` with `Location` for resource creation.
- Every controller has `@Tag`. Every endpoint has `@Operation` and `@ApiResponses`.

---

## Database Schema

### event_store table
```sql
CREATE TABLE event_store (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID        NOT NULL UNIQUE,           -- idempotency key
    aggregate_id  UUID        NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    version       BIGINT      NOT NULL,
    payload       JSONB       NOT NULL,                  -- serialized event
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (aggregate_id, version)                        -- optimistic locking
);
CREATE INDEX idx_event_store_aggregate_id ON event_store(aggregate_id);
```

### order_snapshots table
```sql
CREATE TABLE order_snapshots (
    aggregate_id  UUID        PRIMARY KEY,
    version       BIGINT      NOT NULL,
    snapshot_data JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Database conventions:**
- All timestamps use `TIMESTAMPTZ`. Never `TIMESTAMP WITHOUT TIME ZONE`.
- Event payloads use `JSONB`, not `TEXT`. Enables indexing.
- Flyway naming: `V{N}__{description}.sql` (two underscores). Never modify existing migrations.
- Foreign keys between microservice databases are forbidden. Reference by ID only.

---

## Microservices (Current State: Implemented)

### Services and Ports

| Service | Port | Database | Failure Rate |
|---------|------|----------|--------------|
| order-service | 8080 | orderdb (5432) | — |
| inventory-service | 8081 | inventorydb (5434) | 20% |
| payment-service | 8082 | paymentdb (5435) | 30% |
| shipping-service | 8083 | shippingdb (5436) | 10% |
| notification-service | 8084 | — (Solace consumer) | — |

### Inventory Service Endpoints
```
POST   /inventory/reserve              → ReserveInventoryResponse {reservationId, status}
DELETE /inventory/reserve/{reservationId} → ReleaseInventoryResponse
```

### Payment Service Endpoints
```
POST   /payments/charge                → ChargePaymentResponse {transactionId, status}
POST   /payments/refund                → RefundPaymentResponse
```

### Shipping Service Endpoints
```
POST   /shipping/shipments             → CreateShipmentResponse {shipmentId, trackingNumber, carrier}
POST   /shipping/deliveries/{id}/confirm → ConfirmDeliveryResponse
```

### Notification Service
- Solace JMS `@JmsListener` on durable queue `notification-service-group`, which carries a Topic Subscription to `order.events`
- Dispatches to `NotificationService` based on event type
- Idempotency: deduplicates by `eventId`
- Error handling: in-app retry (2 retries, 2s apart) then logs, never rethrows past that (best-effort)

### Solace Rules (Outbox Pattern)
- Persist event to DB first, publish to Solace in `@TransactionalEventListener(AFTER_COMMIT)`
- Messaging is via **Solace JMS** (`solace-jms-spring-boot-starter`, `JmsTemplate`/`@JmsListener`) — not raw JCSMP
- Each "topic + consumer group" pair is: one Solace **Topic** for publish, plus one durable **Queue per consumer group** with a Topic Subscription to that topic. A `@JmsListener`'s `destination` is the **queue name** (the group name), never the topic name
- Queues that must load-balance across multiple instances rely on Solace Queue semantics (competing consumers), never a bare Topic Subscription (exclusive-by-default)
- Every consumer MUST be idempotent — deduplicate by `eventId` before processing
- **DLQ**: keep in-app retry (2 retries, 2s apart) wrapped around the `@JmsListener` body; only the final exhausted-retry rethrow reaches Solace's native DMQ (Dead Message Queue). Provision **one DMQ per topic** (not per queue)
- Queue names (unchanged naming convention): `{service-name}-group`
- Topics (unchanged naming convention): `order.events`, `inventory.events`, `payment.events`, `shipping.events`, `notification.requests`

### Kong API Gateway
- Client traffic routes through Kong (port 8000)
- Service-to-service traffic (Temporal activity HTTP calls) bypasses Kong
- All Kong routes have `rate-limiting` plugin
- `correlation-id` plugin applied globally — no per-service filter once Kong is active

---

## CQRS Rules

```kotlin
@Service
@Transactional
class OrderCommandService(...) {  // handles all writes, returns Unit or new ID only
    fun createOrder(req: CreateOrderRequest): UUID { ... }
    fun addItem(orderId: UUID, req: AddItemRequest) { ... }
    fun confirmOrder(orderId: UUID): String { ... }  // returns workflowId
    // never returns aggregate state
}

@Service
@Transactional(readOnly = true)
class OrderQueryService(...) {  // handles all reads, never modifies state
    fun getOrder(orderId: UUID): OrderResponse { ... }
    fun getTimeline(orderId: UUID): List<TimelineEntry> { ... }
    // never accepts command objects as parameters
}
```

---

## Value Objects and Domain Types

```kotlin
// All value objects are Kotlin data classes (immutable by definition — val properties only)
data class OrderId(val value: UUID) {
    companion object { fun of(v: UUID) = OrderId(v) }
}
data class CustomerId(val value: UUID) { ... }
data class Money(val amount: BigDecimal, val currency: String) {
    // Never use double or float for financial amounts
    companion object { val ZERO = Money(BigDecimal.ZERO, "USD") }
    fun add(other: Money) = Money(amount.add(other.amount), currency)
    // Returns new instance; never mutates in place
}
data class OrderItem(val productId: UUID, val productName: String, val quantity: Int, val unitPrice: Money) {
    fun totalPrice(): Money = unitPrice.multiply(quantity)
}
```

---

## Naming Conventions

| Artifact | Pattern | Example |
|---|---|---|
| Domain events | `{Entity}{PastTense}Event` | `OrderConfirmedEvent` |
| Commands | `{Verb}{Entity}Command` | `ConfirmOrderCommand` |
| Repository ports | `{Entity}Repository` | `OrderRepository` |
| Repository adapters | `{Entity}RepositoryAdapter` | `OrderRepositoryAdapter` |
| Activity interfaces | `{Domain}Activity` | `InventoryActivity` |
| Activity impls | `{Domain}ActivityImpl` | `InventoryActivityImpl` |
| Application services | `Order{Command|Query}Service` | `OrderCommandService` |
| DTOs | `{Entity}{Request|Response}` | `CreateOrderRequest` |
| Solace topics | `{domain}.events` | `order.events` |

---

## Testing Conventions

| Test type | Suffix | Infrastructure | Speed |
|---|---|---|---|
| Domain unit | `*Test` | None | <1s |
| Application unit | `*Test` | Mockito | <1s |
| Temporal workflow | `*Test` | `TestWorkflowEnvironment` | ~5s |
| Integration | `*IntegrationTest` | Testcontainers PostgreSQL | ~30s |

- Use `TestWorkflowEnvironment` for all Temporal tests. Never require a running Temporal server.
- Activity test doubles MUST be plain Kotlin classes implementing the activity interface. Do NOT use Mockito for Temporal activities — Temporal inspects `@ActivityMethod` annotations and fails on proxies.
- Integration tests use `@DynamicPropertySource` with Testcontainers to inject the real DB URL.

---

## Running the System

### Infrastructure (Docker Compose)
```bash
cd docker
docker compose up -d postgres temporal-postgres temporal temporal-ui solace
# Solace PubSub+ has no ZooKeeper-style coordination dependency — no separate service needed
# Wait ~20s for Temporal to initialize
```

### Start Services
```bash
# Each in its own terminal
cd order-service      && mvn spring-boot:run
cd inventory-service  && mvn spring-boot:run
cd payment-service    && mvn spring-boot:run
cd shipping-service   && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
```

### Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/orderdb` | Event store DB |
| `TEMPORAL_SERVICE_ADDRESS` | `localhost:7233` | Temporal gRPC |
| `SIMULATION_PAYMENT_FAILURE_RATE` | `0.30` | Payment failure probability |
| `SIMULATION_INVENTORY_FAILURE_RATE` | `0.20` | Inventory failure probability |
| `SIMULATION_SHIPPING_FAILURE_RATE` | `0.10` | Shipping failure probability |

### Testing Failure Paths
```bash
# Always fail payment → demonstrates saga compensation
--simulation.payment-failure-rate=1.0

# Clean happy path
--simulation.inventory-failure-rate=0.0
--simulation.payment-failure-rate=0.0
--simulation.shipping-failure-rate=0.0
```

### Access Points
| URL | What it is |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8088 | Temporal UI (workflow visualizer) |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/prometheus | Prometheus metrics |

---

## Explicit Anti-Patterns (Never Do These)

- Shared database between services
- Calling `OrderRepository` from a controller — always go through application service
- Business logic in `apply()` methods — `apply()` only mutates state, no validation
- Direct state mutation on `Order` — all changes go through command methods which raise events
- `System.currentTimeMillis()` in workflow code — non-deterministic, breaks replay
- Catching `OptimisticLockingException` silently — expose it so callers can retry
- Storing the same event twice — `event_id` has UNIQUE index; catch `DuplicateKeyException` for idempotency
- Publishing to Solace inside a DB transaction — always use `@TransactionalEventListener(AFTER_COMMIT)`
- Mockito mocks for Temporal activities — use plain Kotlin classes
- Raw `UUID` or `double` in domain code — use `OrderId`/`CustomerId` and `Money(BigDecimal)`

---

## Key Design Decisions (Why)

**Why Event Sourcing instead of CRUD?**
Every state change is a permanent, immutable fact. You can see what happened, when, and why. `PaymentFailedEvent` records the failure reason. `InventoryReleasedEvent` proves compensation ran. CRUD would only show `CANCELLED` with no history.

**Why Temporal instead of queue-based saga?**
A queue-based saga needs: retry tables, DLQs, reconciliation jobs, state machine persistence, distributed coordination. Temporal provides all of that as primitives. Crash recovery, retries, timeouts, and compensation are built-in. The workflow code reads like sequential logic.

**Why both Temporal AND Event Sourcing?**
Temporal manages the *process* (which step, when to retry, how to compensate). Event Sourcing manages the *state* (what happened to the domain). They're complementary — Temporal's activity results are recorded as domain events in the event store.

**Why Hexagonal Architecture?**
The domain knows nothing about Temporal, PostgreSQL, Solace, or Spring. Swapping Temporal for another orchestrator only touches the infrastructure layer. Domain unit tests run in milliseconds with no infrastructure.

**Why the Outbox Pattern for Solace?**
Without it: persist order event to DB (succeeds) → publish to Solace (fails) → notification service never gets the event. With Outbox: DB commit happens first, Solace publish happens after commit, never inside the same transaction. The `@TransactionalEventListener(AFTER_COMMIT)` is the implementation of this pattern.
