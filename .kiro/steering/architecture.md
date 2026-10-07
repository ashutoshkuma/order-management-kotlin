---
inclusion: always
---

# Architecture Steering — Order Management System

This document defines the architectural rules, patterns, and conventions for this project. When generating or modifying code, always follow these guidelines.

---

## Project Identity

- **Type:** Production-quality learning PoC — Kotlin (JVM 21 target), Spring Boot 3.2.x
- **Purpose:** Demonstrates Temporal Workflows + Event Sourcing + Hexagonal Architecture
- **Current state:** Monolith (single deployable). Microservices architecture designed in `docs/diagrams/`, ready for implementation.
- **Package root:** `com.example.ordermanagement`
- **Build:** Maven multi-module reactor with the `kotlin-maven-plugin` (no Gradle) — see root `pom.xml`. All modules are pure Kotlin; no `.java` sources remain.

---

## Architecture Pattern: Hexagonal (Ports and Adapters)

The project is divided into four strict layers. Never violate the dependency direction.

```
API Layer  →  Application Layer  →  Domain Layer  ←  Infrastructure Layer
```

**Domain Layer** is the innermost layer. It has **zero dependencies** on Spring, Temporal, PostgreSQL, or Solace. It only depends on the Kotlin standard library and Jackson annotations.

**Application Layer** depends on the Domain Layer only. It uses domain ports (interfaces) to communicate with infrastructure.

**Infrastructure Layer** implements domain ports. It knows about Spring, JDBC, Temporal SDK, Solace.

**API Layer** depends on the Application Layer only. Controllers never call repositories or domain objects directly.

### Inbound Ports (Primary Adapters)

The domain exposes two inbound port interfaces that define what the application can do:

- `OrderCommandUseCase` — all state-changing operations (create, add item, confirm, cancel, record Temporal callbacks)
- `OrderQueryUseCase` — all read operations (get order, history, timeline, workflow status, list orders)

`OrderCommandService` implements `OrderCommandUseCase`. `OrderQueryService` implements `OrderQueryUseCase`. Controllers inject the **interfaces**, never the concrete service classes.

### Strict Rules

- Never put business logic in controllers
- Never put business logic in repositories
- Never call `EventStoreAdapter` from a controller
- Never import Temporal classes in the domain or application packages
- Never import Spring annotations in the domain package (exception: `@JsonTypeInfo` on events is acceptable)
- Domain classes are `internal`/public plain Kotlin — never annotate domain types with `@Component`/`@Service`
- Application services coordinate, they do not decide
- **Controllers inject `OrderCommandUseCase` and `OrderQueryUseCase` interfaces — never `OrderCommandService` or `OrderQueryService` directly**

---

## Event Sourcing Rules

### The Aggregate Contract

The `Order` aggregate is the single source of truth for order state. These rules are non-negotiable:

1. **State is never mutated directly.** Every state change MUST go through a domain event.
2. **Commands raise events. Events change state.** The `apply(DomainEvent)` method is the ONLY place that mutates fields.
3. **`apply()` methods must be side-effect free.** No DB calls, no HTTP calls, no logging.
4. **Events are immutable data classes.** Never add setters (mutable `var` properties) to event data classes.
5. **The `pendingEvents` list is drained by the repository, not the service.** Services call `save(order)`, not `appendEvents(order.drainPendingEvents())`.

### Adding a New Domain Event

When adding a new event type, these steps MUST all be done together:

1. Create the new `data class` in `domain/event/`, implementing the `DomainEvent` sealed interface
2. Kotlin sealed interfaces have no `permits` clause — the new data class just needs to live in the same module as `DomainEvent` to be picked up as a variant automatically
3. Add `@JsonSubTypes.Type` entry to `DomainEvent`
4. Add an `is NewEvent -> applyNewEvent(it)` branch in the `when` expression inside `Order.apply()`
5. Implement the `private fun applyNewEvent(e: NewEvent)` method
6. Add a corresponding command handler method on `Order`
7. Add a factory function (`create(...)`) on the new event data class, e.g. as a companion object function

The compiler will fail if step 4 is missing (a non-exhaustive `when` over a sealed interface is a compile error, same guarantee Java's exhaustive `switch` gave) — this is intentional.

### Event Store Rules

- The `event_store` table is **append-only**. No UPDATE or DELETE SQL is ever acceptable.
- Optimistic locking is enforced at two levels: application check + `UNIQUE(aggregate_id, version)` DB constraint.
- Events are stored as JSONB. The `@JsonTypeInfo` type discriminator in the payload enables polymorphic deserialization.
- When loading an aggregate: always check for a snapshot first. Load snapshot → load events after snapshot version → replay.

### Snapshot Rules

- Snapshots are taken automatically when `order.getVersion() % SNAPSHOT_THRESHOLD == 0` (threshold = 50).
- Snapshots are expendable. They can be deleted and regenerated by replaying all events.
- Old snapshot formats must remain deserializable. Use `@JsonProperty` defaults or Optional fields for schema evolution.
- `FAIL_ON_UNKNOWN_PROPERTIES = false` is set globally — never change this.

---

## Temporal Rules

### Workflow Determinism (Critical)

Workflow code in `OrderFulfillmentWorkflowImpl` MUST be deterministic. These are absolute prohibitions:

| ❌ NEVER use | ✅ Use instead |
|-------------|--------------|
| `System.currentTimeMillis()` | `Workflow.currentTimeMillis()` |
| `UUID.randomUUID()` | `Workflow.newRandom().nextLong()` |
| `Random()` | `Workflow.newRandom()` |
| `Thread.sleep()` | `Workflow.sleep(Duration)` |
| Direct HTTP/DB calls | Activity stubs |
| `System.getenv()` | Pass as workflow parameters |

If you violate determinism, Temporal's replay will produce non-deterministic results and workflows will fail on crash recovery.

### Temporal + Kotlin Data Classes (Critical)

Temporal does **not** use the Spring-managed `ObjectMapper`. Its `DefaultDataConverter` builds its own internal Jackson instance, completely independent of any `jackson-module-kotlin` registration Spring Boot auto-detects for MVC/REST. Any Kotlin data class used as a workflow or activity parameter/return type must still deserialize correctly through Temporal's converter, or the failure only surfaces later as a broken workflow history replay, not a compile error.

- `TemporalConfig` MUST build an explicit `DataConverter` (a Jackson-based payload converter backed by a kotlin-module-registered `ObjectMapper`) and set the **same instance** on both `WorkflowClientOptions` and the worker registration path. Never rely on Temporal's default converter once any workflow/activity signature uses a Kotlin data class.

### Activity Rules

- Activities are the **side-effect boundary**. All HTTP calls, DB calls, and Solace publishes go here.
- All `record*()` methods in activity implementations MUST be idempotent — catch exceptions and log, never rethrow.
- Activity implementations are annotated `@ActivityImpl(taskQueues = "ORDER_FULFILLMENT_QUEUE")`.
- The task queue name is a constant: `WorkflowPortAdapter.TASK_QUEUE = "ORDER_FULFILLMENT_QUEUE"`.
- Non-retryable exceptions (`InsufficientFundsException`, `CardDeclinedException`) must be listed in the activity stub's `doNotRetry` list using their **simple class name** (not fully qualified — Temporal uses simple names for type matching).

### Saga Compensation

Every saga step that has a side effect MUST have a compensating action:

| Forward Step | Compensation |
|--------------|-------------|
| `reserveInventory()` | `releaseInventory()` |
| `processPayment()` | `refundPayment()` |
| `createShipment()` | *(none needed — no side effect to undo before delivery)* |

Compensation methods must be called in **reverse order** of the forward steps. Compensation failures are logged but do not abort the compensation chain — always attempt all steps.

### Signals and Queries

- `@SignalMethod` handlers must only set primitive flags or simple values. They must NOT call activities.
- `@QueryMethod` handlers must be read-only. They must NOT modify any state.
- Use `Workflow.await(duration, condition)` to pause a workflow waiting for a signal.

---

## CQRS Rules

- `OrderCommandService` implements `OrderCommandUseCase`. Handles all state-changing operations. Always `@Transactional`.
- `OrderQueryService` implements `OrderQueryUseCase`. Handles all reads. Always `@Transactional(readOnly = true)`. Never modifies state.
- Commands return `void` or the new aggregate's ID only. They do not return aggregate state.
- Queries never accept command objects as parameters.

### Read Model Projection (order_summary)

Event sourcing builds state by replaying events, which makes list/filter queries impossible without replaying every aggregate. The `order_summary` table is the CQRS read model that solves this:

- **`OrderProjectionUpdater`** (`infrastructure/projection/`) listens for every domain event via `@TransactionalEventListener(AFTER_COMMIT)` and updates the `order_summary` row in a separate `@Transactional(REQUIRES_NEW)` transaction. This means the write transaction commits first — even if the projection update fails, the event is already durable and can be replayed.
- **`OrderSummaryRepository`** reads from and writes to `order_summary` using `NamedParameterJdbcTemplate`. It is the only component that touches this table.
- **`GET /orders`** queries the projection with optional `status`, `customerId`, and pagination filters. Returns `Page<OrderSummaryResponse>`.

**Rule:** Never query `event_store` for list/filter operations. Always use the `order_summary` projection for queries that are not single-aggregate lookups. Single-order GET (`/orders/{id}`) still uses event replay — this preserves the audit trail and avoids eventual consistency for reads where the client just wrote.

**Projection rebuild:** If `order_summary` gets out of sync, replay all events through `OrderProjectionUpdater`. The event store is the source of truth; the projection is derived.

---

## Domain Model Rules

### Value Objects

All value objects are Kotlin `data class` types. They are immutable by definition (`val` properties only — never add `var`).

- `Money` — use `BigDecimal`, never `double` or `float` for financial amounts.
- Operations on `Money` return new instances. Never mutate `amount` in place.
- `OrderId` and `CustomerId` are distinct types. Never use raw `UUID` for identifiers in domain code.
- `OrderItem` is a value object (no identity). Two items with same productId, qty, and price are equivalent.

### Exceptions

- Domain exceptions extend `DomainException`.
- Throw `InvalidStateTransitionException` for invalid state machine transitions.
- Throw `OptimisticLockingException` for concurrent modification conflicts.
- Throw `OrderNotFoundException` for missing aggregates (404).
- Never throw checked exceptions from domain code.

---

## API Layer Rules

- Controllers validate input using Bean Validation (`@Valid`, `@NotNull`, etc.). Never validate in the service.
- All error responses use RFC 7807 `ProblemDetail` format via `GlobalExceptionHandler`.
- Controllers map HTTP verbs semantically: POST for commands, GET for queries, DELETE for removal.
- Use `202 Accepted` for operations that trigger async workflows (confirm, cancel).
- Use `201 Created` with `Location` header for resource creation.
- Every controller class must have `@Tag` annotation for Swagger grouping.
- Every endpoint must have `@Operation` and `@ApiResponses` annotations.

---

## Solace Rules (Planned Microservices)

- Use the **Outbox Pattern**: persist the event to DB first, publish to Solace in `@TransactionalEventListener(AFTER_COMMIT)`.
- Messaging is via **Solace JMS** (`solace-jms-spring-boot-starter`, `JmsTemplate`/`@JmsListener`) — not raw JCSMP.
- Each Kafka-style "topic + consumer group" pair becomes: one Solace **Topic** for publish, plus one durable **Queue per consumer group** with a Topic Subscription to that topic. A `@JmsListener`'s `destination` is the **queue name** (the group name), never the topic name — this is the one mental shift from Kafka.
- Every consumer group that must load-balance across multiple instances (Kafka's `concurrency` setting) relies on Solace Queue semantics (competing consumers) — never a Topic Subscription alone, which is exclusive-by-default and won't spread load.
- Queues and their topic subscriptions are **self-provisioned idempotently at service startup** via the Solace JMS client's own admin extensions (`SolJmsUtility`/Solace-specific `Session` casts — these are Solace-proprietary, not portable `jakarta.jms` API). This preserves the "just works on `docker compose up`" experience Kafka's topic auto-create gave us.
- Every Solace consumer MUST be idempotent — deduplicate by `eventId` (UUID) before processing.
- **DLQ**: keep in-app retry (2 retries, 2s apart, non-retryable exception types skip straight through) wrapped around the `@JmsListener` body; only the final exhausted-retry rethrow should reach Solace's native DMQ (Dead Message Queue), configured with max-redelivery-count = 1 since retries already happened in-app. Provision **one DMQ per topic** (not per queue) — multiple consumer-group queues on the same topic share a DMQ.
- Queue names follow the pattern: `{service-name}-group` (unchanged from the Kafka consumer-group naming).
- Topic names (unchanged from the Kafka topic naming): `order.events`, `inventory.events`, `payment.events`, `shipping.events`, `notification.requests`.
- All Solace message payloads use DTOs from `shared-contracts` module, not domain objects.

---

## Kong API Gateway Rules (Planned Microservices)

- Client traffic flows through Kong. Service-to-service traffic (Temporal activity HTTP calls) bypasses Kong.
- All routes registered in Kong must have `rate-limiting` plugin.
- The `correlation-id` plugin is applied globally — do NOT add `CorrelationIdFilter` to individual services once Kong is active.
- Kong uses DB-Full mode. Configuration managed via Konga UI or Admin API.
- Sensitive routes (admin, internal) must never be exposed through Kong.

---

## Infrastructure Configuration

### Failure Simulation

```yaml
simulation:
  inventory-failure-rate: 0.20   # 0.0 = never fail, 1.0 = always fail
  payment-failure-rate: 0.30
  shipping-failure-rate: 0.10
```

To force a specific path for testing:
- Set `payment-failure-rate=1.0` to always trigger saga compensation
- Set all rates to `0.0` for a clean happy-path run

### Key Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/orderdb` | Event store DB |
| `TEMPORAL_SERVICE_ADDRESS` | `localhost:7233` | Temporal gRPC endpoint |
| `SIMULATION_PAYMENT_FAILURE_RATE` | `0.30` | Payment failure probability |
| `SIMULATION_INVENTORY_FAILURE_RATE` | `0.20` | Inventory failure probability |

---

## Naming Conventions

| Artifact | Convention | Example |
|----------|-----------|---------|
| Domain events | `{Entity}{PastTense}Event` | `OrderConfirmedEvent` |
| Commands | `{Verb}{Entity}Command` | `ConfirmOrderCommand` |
| Repositories | `{Entity}Repository` (port) | `OrderRepository` |
| Adapters | `{Entity}RepositoryAdapter` (impl) | `OrderRepositoryAdapter` |
| Activities | `{Domain}Activity` (interface) | `InventoryActivity` |
| Activity impls | `{Domain}ActivityImpl` | `InventoryActivityImpl` |
| Application services | `Order{Command|Query}Service` | `OrderCommandService` |
| DTOs | `{Entity}{Request|Response}` | `CreateOrderRequest` |
| Solace topics | `{domain}.events` | `order.events` |

---

## Database Conventions

- Flyway migration files: `V{N}__{description}.sql` (two underscores)
- Never modify existing migration files. Create new ones for changes.
- Current order-service migrations: `V1__create_event_store.sql`, `V2__create_snapshot_store.sql`, `V3__create_order_projections.sql` (creates `order_summary` table).
- The `event_store` table is append-only. If you find yourself writing UPDATE or DELETE SQL against it, reconsider the approach.
- All timestamps use `TIMESTAMPTZ` (timezone-aware). Never use `TIMESTAMP WITHOUT TIME ZONE`.
- Event payloads use `JSONB`, not `TEXT` or `JSON` — enables indexing and querying.
- Foreign keys between microservice databases are forbidden. Reference by ID only.

---

## Testing Conventions

| Test type | Class suffix | Infrastructure needed | Speed |
|-----------|-------------|----------------------|-------|
| Domain unit | `*Test` | None | <1s |
| Application unit | `*Test` | Mockito mocks | <1s |
| Temporal workflow | `*Test` | `TestWorkflowEnvironment` | ~5s |
| Integration | `*IntegrationTest` | Testcontainers PostgreSQL | ~30s |

- Use `TestWorkflowEnvironment` for all Temporal tests — never require a running Temporal server.
- Activity test doubles MUST be plain Kotlin classes implementing the activity interface. Do NOT use Mockito mocks for Temporal activities (Temporal inspects `@ActivityMethod` annotations and fails on proxies).
- Integration tests use `@DynamicPropertySource` with Testcontainers to inject the real DB URL.

---

## What NOT to Do

These anti-patterns are explicitly prohibited:

- **Shared database between services** — each microservice owns its data
- **Calling OrderRepository from a controller** — always go through application service
- **Business logic in event apply() methods** — apply() only mutates state, no validation
- **Direct state mutation on Order** — all changes go through command methods which raise events
- **`System.currentTimeMillis()` in workflow code** — non-deterministic, breaks replay
- **Catching `OptimisticLockingException` silently** — expose it to the caller to retry
- **Storing the same event twice** — `event_id` has a UNIQUE index; idempotency is handled by catching `DuplicateKeyException`
- **Publishing to Solace inside a DB transaction** — use `@TransactionalEventListener(AFTER_COMMIT)` to ensure DB commit happens first

---

## Diagrams

Architecture diagrams are in `docs/diagrams/`:

- `HLD-microservices-architecture.drawio` — full system view with Kong, Temporal, Solace PubSub+, all services
- `LLD-detailed-design.drawio` — 4-page internal design:
  - Page 1: Order Service Hexagonal layers
  - Page 2: Temporal Workflow saga flow
  - Page 3: Event Sourcing write/read flows + Solace topics
  - Page 4: All microservices internals + Kong config

Open with draw.io (app.diagrams.net) or VS Code draw.io extension.
