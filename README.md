# Order Management System
### Temporal Workflows + Event Sourcing + Microservices + Kong + Solace PubSub+

A production-quality learning project demonstrating how enterprise patterns work together in a real system. Built with Kotlin (JVM 21 target), Spring Boot 3.x, and a full observability stack.

---

## What This Demonstrates

| Concept | Where to look |
|---------|--------------|
| Temporal Workflows | `OrderFulfillmentWorkflowImpl.kt` |
| Temporal Signals | `cancelOrder()`, `retryPayment()` signal handlers |
| Temporal Queries | `getCurrentStatus()`, `getProgress()` query methods |
| Saga Pattern | Compensation paths in `OrderFulfillmentWorkflowImpl` |
| Event Sourcing | `Order.kt` aggregate + `EventStoreAdapter.kt` |
| Hexagonal Architecture | Inbound ports `OrderCommandUseCase`/`OrderQueryUseCase` in `domain/port/inbound/` |
| CQRS | `OrderCommandService` (write) vs `OrderQueryService` + `order_summary` projection (read) |
| Optimistic Locking | `UNIQUE(aggregate_id, version)` in V1 migration |
| Snapshotting | `OrderSnapshot` + `SnapshotStoreAdapter` |
| Idempotency | `record*()` methods in all activity implementations |
| Event Replay | `Order.reconstitute(events)` |
| DDD Aggregates | `Order.kt` — no setters, events-only state mutation |
| Value Objects | `Money`, `OrderId`, `CustomerId`, `OrderItem` |
| Kong API Gateway | `docker/docker-compose.yml` |
| Solace Outbox Pattern | `@TransactionalEventListener(AFTER_COMMIT)` |

---

## Architecture Overview

```
┌─────────────┐    ┌──────────────────────────────────┐
│   Browser   │    │         Kong API Gateway         │
│   / curl    │───▶│  :8000 proxy  :8001 admin        │
└─────────────┘    │  Plugins: rate-limit, cache,     │
                   │  correlation-id, prometheus      │
                   └──────────────┬───────────────────┘
                                  │ routes
              ┌───────────────────┼──────────────────────┐
              ▼                   ▼                        ▼
     order-service:8080   inventory-service:8081   payment-service:8082
     shipping-service:8083   notification-service:8084
              │
              │ gRPC :7233
              ▼
     ┌─────────────────┐         ┌──────────────────┐
     │ Temporal Server │         │ Solace PubSub+   │
     │ OrderFulfillment│         │  order.events    │
     │ Workflow (Saga) │         │  payment.events  │
     └─────────────────┘         │  shipping.events │
                                 └──────────────────┘
```

**Current state of the repository:** Monolith (single Spring Boot app). The microservices architecture is designed (HLD + LLD diagrams exist) and ready for implementation. The domain, workflow, and event sourcing layers are already structured for the split.

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| Runtime | Kotlin (JVM 21 target) |
| Framework | Spring Boot 3.2.x |
| Workflow Orchestration | Temporal Java SDK 1.25.2 |
| Persistence | Spring Data JDBC + PostgreSQL 16 |
| Schema Migration | Flyway |
| Message Broker | Solace PubSub+ (planned microservices) |
| API Gateway | Kong (planned microservices) |
| Serialization | Jackson (JSONB in PostgreSQL) |
| DTO Mapping | MapStruct (compile-time) |
| API Docs | springdoc-openapi 2.5.0 (Swagger UI) |
| Observability | Micrometer + Prometheus |
| Testing | JUnit 5 + Testcontainers + Temporal TestWorkflowEnvironment |
| Build | Maven multi-module (planned) |
| Containers | Docker Compose |

---

## Project Structure

```
src/main/kotlin/com/example/ordermanagement/
│
├── api/                          # HTTP layer — no business logic
│   ├── controller/
│   │   ├── OrderController.kt         All order endpoints
│   │   ├── WorkflowController.kt      Temporal signal/query endpoints
│   │   └── GlobalExceptionHandler.kt    RFC 7807 error responses
│   ├── dto/request/                   Validated request data classes
│   ├── dto/response/
│   │   ├── OrderResponse.kt               Single-order event-replay response
│   │   ├── OrderSummaryResponse.kt        CQRS list-query response (from projection)
│   │   └── EventHistoryResponse.kt        Raw event history entry
│   └── mapper/OrderMapper.kt          MapStruct DTO ↔ domain mapping
│
├── application/                  # Use cases — coordinates domain + infra
│   └── service/
│       ├── OrderCommandService.kt     17 command methods (@Transactional)
│       └── OrderQueryService.kt       Read-only queries (readOnly=true)
│
├── domain/                       # Core business logic — zero framework deps
│   ├── aggregate/
│   │   ├── Order.kt                   Aggregate root (event sourcing)
│   │   └── OrderSnapshot.kt           Performance optimization (every 50 events)
│   ├── command/                       Intent objects (can be rejected)
│   ├── event/
│   │   ├── DomainEvent.kt             Sealed interface (13 permitted types)
│   │   └── *Event.kt                  Immutable data classes (facts, never rejected)
│   ├── exception/                     Domain rule violations
│   ├── model/                         Enums: OrderStatus, PaymentStatus, ShipmentStatus
│   ├── port/outbound/                 Interfaces (domain defines, infra implements)
│   │   ├── EventStore.kt
│   │   ├── SnapshotStore.kt
│   │   ├── OrderRepository.kt
│   │   └── WorkflowPort.kt
│   └── valueobject/                   Money, OrderId, CustomerId, OrderItem
│
├── infrastructure/               # Adapters — implements domain ports
│   ├── config/SimulationProperties.kt     Configurable failure rates
│   ├── observability/CorrelationIdFilter.kt
│   ├── persistence/
│   │   ├── EventStoreAdapter.kt       Append-only JSONB event store
│   │   ├── SnapshotStoreAdapter.kt    Upsert snapshot (latest only)
│   │   └── OrderRepositoryAdapter.kt    Load snapshot + replay
│   ├── projection/
│   │   ├── OrderSummaryRepository.kt    CQRS read model (order_summary table)
│   │   └── OrderProjectionUpdater.kt    Updates projection after each domain event
│   └── temporal/
│       ├── WorkflowPortAdapter.kt     Bridges domain port → Temporal SDK
│       ├── activity/                  4 interfaces + 4 implementations
│       ├── worker/TemporalWorkerSetup.kt
│       └── workflow/
│           ├── OrderFulfillmentWorkflow.kt       Interface
│           └── OrderFulfillmentWorkflowImpl.kt   Implementation (saga)
│
└── config/
    ├── JacksonConfig.kt           JavaTimeModule, FAIL_ON_UNKNOWN=false
    ├── OpenApiConfig.kt           Swagger configuration
    └── TemporalConfig.kt          WorkflowServiceStubs, WorkflowClient

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__create_event_store.sql          Append-only event table + indexes
    ├── V2__create_snapshot_store.sql       Snapshot table
    └── V3__create_order_projections.sql    CQRS read model (order_summary)
```

---

## Event Sourcing — How State Works

**Traditional CRUD:** Save current state → overwrite → lose history.

**This system:** Every change is an immutable event appended to `event_store`. State is rebuilt by replaying events.

```
Order created                    v1: OrderCreatedEvent
Item added (Keyboard $89.99)     v2: ItemAddedEvent
Item added (USB-C Hub $34.99)    v3: ItemAddedEvent
Order confirmed                  v4: OrderConfirmedEvent  ← workflow starts here
Inventory reserved               v5: InventoryReservedEvent
Payment completed                v6: PaymentCompletedEvent
Shipment created (FedEx)         v7: ShipmentCreatedEvent
Delivered                        v8: ShipmentDeliveredEvent
```

GET `/orders/{id}` replays all 8 events and returns current state.
GET `/orders/{id}/history` shows every event with full payload.
GET `/orders/{id}/timeline` shows human-readable descriptions.

**Snapshot optimization:** After every 50 events, state is snapshotted. Future loads restore from snapshot (v50) + replay only events 51–N. Worst-case: 50 events replayed regardless of total history size.

---

## Temporal Workflow — The Saga

```
fulfill(orderId)
│
├─ shouldCancel? → compensateAndCancel()
│
├─ STEP 1: reserveInventory()        ← InventoryActivity (3 retries, 2x backoff)
│   SUCCESS → reservationId stored, recordInventoryReserved()
│   FAILURE → recordInventoryReservationFailed(), cancel order
│
├─ shouldCancel? → releaseInventory + cancel
│
├─ STEP 2: processPayment()          ← PaymentActivity (2 retries)
│   SUCCESS → transactionId stored, recordPaymentCompleted()
│   TRANSIENT FAIL → Workflow.await(5min, retryPayment signal)
│   NON-RETRYABLE → releaseInventory + cancel
│   EXHAUSTED → releaseInventory + cancel
│
├─ shouldCancel? → refundPayment + releaseInventory + cancel
│
├─ STEP 3: createShipment()          ← ShippingActivity (3 retries)
│   SUCCESS → shipmentId stored, recordShipmentCreated()
│   FAILURE → refundPayment + releaseInventory + cancel
│
├─ STEP 4: confirmDelivery() → recordShipmentDelivered()
│
└─ STEP 5: sendOrderDeliveredNotification() → COMPLETED
```

**Signals:** `cancelOrder(reason)` and `retryPayment()` can be sent at any point while the workflow is running.

**Queries:** `getCurrentStatus()` and `getProgress()` read live in-memory workflow state — no DB hit.

---

## Running the Project

### Prerequisites
- JDK 21 (`java -version`) — Kotlin compiles to JVM 21 bytecode
- Maven 3.9+ (`mvn -version`)
- Docker Desktop running

### Start Infrastructure

```bash
cd docker
docker compose up -d postgres temporal-postgres temporal temporal-ui
# Wait ~20s for Temporal to initialize
nc -z localhost 7233 && echo "Temporal ready"
```

### Run the Application

```bash
# Success path (no failures)
java -jar target/order-management-1.0.0-SNAPSHOT.jar \
  --simulation.inventory-failure-rate=0.0 \
  --simulation.payment-failure-rate=0.0 \
  --simulation.shipping-failure-rate=0.0

# Or with Maven
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run

# Force payment failures to demo saga compensation
java -jar target/*.jar --simulation.payment-failure-rate=1.0
```

### Access Points

| URL | What it is |
|-----|-----------|
| http://localhost:8080/swagger-ui.html | **Swagger UI** — try every endpoint |
| http://localhost:8080/v3/api-docs | OpenAPI 3.0 JSON spec |
| http://localhost:8088 | **Temporal UI** — workflow visualizer |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/prometheus | Prometheus metrics |

---

## API Quick Reference

### Happy Path Demo

```bash
# 1. Create order
ORDER_ID=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000",
       "shippingAddress":"123 Main St, New York, NY 10001"}' | jq -r '.orderId')

# 2. Add items
curl -X POST http://localhost:8080/orders/$ORDER_ID/items \
  -H "Content-Type: application/json" \
  -d '{"productId":"660e8400-e29b-41d4-a716-446655440001",
       "productName":"Wireless Keyboard","quantity":1,"unitPrice":89.99}'

# 3. Confirm → starts Temporal workflow (async)
curl -X POST http://localhost:8080/orders/$ORDER_ID/confirm

# 4. Watch it progress
watch -n1 "curl -s http://localhost:8080/orders/$ORDER_ID | jq .status"

# 5. See the full event trail
curl http://localhost:8080/orders/$ORDER_ID/timeline | jq .

# 6. Query Temporal workflow state directly
curl http://localhost:8080/workflows/order-fulfillment-$ORDER_ID | jq .

# 7. List all delivered orders (CQRS projection — no event replay)
curl "http://localhost:8080/orders?status=DELIVERED" | jq .

# 8. List orders for a specific customer with pagination
curl "http://localhost:8080/orders?customerId=550e8400-e29b-41d4-a716-446655440000&page=0&size=10" | jq .
```

### Cancel Mid-Flight

```bash
WORKFLOW_ID="order-fulfillment-$ORDER_ID"
curl -X POST http://localhost:8080/workflows/$WORKFLOW_ID/signal/cancel \
  -H "Content-Type: application/json" \
  -d '{"reason":"Customer changed mind"}'
```

### Retry Payment

```bash
curl -X POST http://localhost:8080/workflows/$WORKFLOW_ID/signal/retry-payment
```

---

## All Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/orders` | Create order (DRAFT) |
| `POST` | `/orders/{id}/items` | Add item |
| `DELETE` | `/orders/{id}/items/{productId}` | Remove item |
| `POST` | `/orders/{id}/confirm` | Confirm → starts workflow |
| `POST` | `/orders/{id}/cancel` | Cancel → sends signal |
| `POST` | `/orders/{id}/payment` | Record payment (webhook) |
| `POST` | `/orders/{id}/retry-payment` | Send retry signal |
| `GET` | `/orders` | List orders — paginated, filterable by `status` / `customerId` (CQRS projection) |
| `GET` | `/orders/{id}` | Current state (event replay) |
| `GET` | `/orders/{id}/history` | Raw event store |
| `GET` | `/orders/{id}/timeline` | Human-readable timeline |
| `GET` | `/workflows/{workflowId}` | Temporal Query (live state) |
| `POST` | `/workflows/{id}/signal/cancel` | Cancel signal |
| `POST` | `/workflows/{id}/signal/retry-payment` | Retry signal |
| `GET` | `/actuator/health` | Health |
| `GET` | `/actuator/metrics` | Available metrics |
| `GET` | `/actuator/prometheus` | Prometheus scrape |

---

## Worker Crash Recovery Demo

This demonstrates Temporal's durability guarantee.

```bash
# 1. Start with slow shipping to have time to crash
java -jar target/*.jar --simulation.shipping-failure-rate=0.0

# 2. Create and confirm an order in another terminal
ORDER_ID=... # create and confirm order

# 3. While workflow is running (in CREATING_SHIPMENT step):
#    Kill the application — Ctrl+C or kill the process

# 4. Temporal holds the workflow state in its PostgreSQL DB

# 5. Restart the application
java -jar target/*.jar

# 6. Temporal automatically reschedules the pending activity
#    Workflow continues from CREATING_SHIPMENT — no data loss
curl http://localhost:8080/orders/$ORDER_ID/timeline | jq .
```

---

## Running Tests

```bash
# All domain unit tests (no Docker, <5 seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test \
  -Dtest=OrderAggregateTest,OrderCommandServiceTest

# Temporal workflow tests (no Docker, ~20 seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test \
  -Dtest=OrderFulfillmentWorkflowTest

# Event store integration tests (Docker required, ~60 seconds)
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test \
  -Dtest=EventStoreIntegrationTest

# All tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn verify
```

**Test count:** 34 tests across 3 test classes. All pass.

---

## Key Design Decisions

**Why Event Sourcing instead of CRUD?**
Every state change is a permanent, immutable fact. You can see exactly what happened, when, and why — `PaymentFailedEvent` shows the failure reason, `InventoryReleasedEvent` shows the compensation happened. CRUD would just show "CANCELLED" with no history.

**Why Temporal instead of queues?**
A queue-based saga requires: retry tables, dead letter queues, reconciliation jobs, state machine persistence, distributed coordination. Temporal gives you all of that with sequential code that looks like a simple function. Crash recovery, retries, timeouts, and compensation are built-in.

**Why separate the two?**
Temporal manages the *process* (which step we're on, when to retry, how to compensate). Event Sourcing manages the *state* (what happened to the domain). They're complementary — Temporal's activity results are recorded as domain events in the event store.

**Why Hexagonal Architecture?**
The domain knows nothing about Temporal, PostgreSQL, Solace, or Spring. It only knows about its own ports (interfaces). This means the domain unit tests run in milliseconds with no infrastructure. It also means swapping Temporal for another orchestrator only changes the infrastructure layer.

**Why CQRS?**
Commands change state through the aggregate (write side). Queries use two paths: single-order `GET /orders/{id}` replays events from the event store (full audit trail, always consistent), while `GET /orders` queries the `order_summary` read model — a denormalized projection updated by `OrderProjectionUpdater` on every domain event. This means list queries with status/customerId filters work with plain SQL and return in sub-milliseconds regardless of how many events an order has accumulated. Read models can evolve independently of the write model.

---

## Observability

### Prometheus Metrics (custom)

| Metric | Type | What it counts |
|--------|------|---------------|
| `orders.created` | Counter | Total orders created |
| `orders.confirmed` | Counter | Orders confirmed (workflows started) |
| `orders.delivered` | Counter | Successfully delivered orders |
| `orders.cancelled.customer` | Counter | Customer-initiated cancellations |
| `orders.cancelled.system` | Counter | Saga compensation cancellations |
| `payments.completed` | Counter | Successful payments |
| `payments.failed` | Counter | Payment failures |
| `inventory.reservation.failed` | Counter | Inventory failures |
| `orders.create.duration` | Timer | Order creation latency |

### Structured Logging
All log lines include `[correlationId=xxx]` in MDC. The correlation ID is extracted from `X-Correlation-ID` request header (or generated). Trace a full request across all log lines with a single ID.

---

## Microservices Roadmap

The current codebase is a well-structured monolith ready to be split. The architecture is already designed — see `docs/diagrams/`.

**Phase 1 — already done (this repo):**
- Domain, event sourcing, Temporal workflow, hexagonal architecture
- All interfaces defined as ports — activity impls are stub implementations

**Phase 2 — microservices split:**
- `order-service` keeps domain + workflow + event store
- `inventory-service`, `payment-service`, `shipping-service`, `notification-service` become separate Spring Boot apps
- Activity implementations change from in-process stubs to HTTP `RestClient` calls
- Solace PubSub+ added for async event publishing (outbox pattern)
- Kong added as API gateway with rate limiting, caching, correlation ID injection

**Diagrams:**
- `docs/diagrams/HLD-microservices-architecture.drawio` — system overview
- `docs/diagrams/LLD-detailed-design.drawio` — 4-page internal design

The workflow code (`OrderFulfillmentWorkflowImpl`) does **not change at all** in the microservices version. Only the activity implementations change.

---

## Learning Checkpoints

After studying this codebase you should be able to answer:

1. Why does `Order.kt` have no setters? *(All state changes go through events — the aggregate exposes only `val` properties and command methods, never mutable `var` setters)*
2. What happens when a Temporal worker crashes mid-activity? *(Temporal replays from last checkpoint on restart)*
3. How does optimistic locking work without DB locks? *(`UNIQUE(aggregate_id, version)` + version check)*
4. What's the difference between a Temporal Query and a `GET /orders/{id}`? *(Query: live in-memory workflow state; GET: DB event replay)*
5. Why does `drainPendingEvents()` exist? *(Separates event creation in aggregate from persistence in repository)*
6. What is the Saga pattern here? *(Each forward step has a compensating step; failures trigger undo in reverse)*
7. Why are domain events `sealed`? *(`DomainEvent` is a Kotlin `sealed interface` implemented by immutable `data class` events — the compiler enforces exhaustive handling in the `when` expression inside `apply()`, the same guarantee a Java sealed interface would give)*
8. What is the Outbox Pattern and why does it matter? *(Persist to DB first, publish to Solace after commit — prevents dual-write problem)*
9. Why does `NotificationActivity` publish to Solace instead of calling a service directly? *(Notifications are fire-and-forget; Solace decouples them; the workflow doesn't wait for notification delivery)*
10. When would you take a snapshot and when wouldn't you? *(High-event aggregates benefit; if most aggregates have <20 events the overhead isn't worth it)*
11. Why does `GET /orders/{id}` use event replay while `GET /orders` uses the projection? *(`/orders/{id}` needs the full audit trail and strong consistency for the caller who just wrote; list queries need filtering/pagination across all orders, which would require replaying every aggregate)*
12. What happens if the `order_summary` projection misses an update? *(The event store still has the event. The projection can be rebuilt by replaying all events through `OrderProjectionUpdater`. Events are the source of truth.)*
