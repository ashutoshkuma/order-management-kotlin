# Order Management System
### Temporal Workflows + Event Sourcing + Microservices + Kong + Solace PubSub+

A production-quality learning project demonstrating how enterprise patterns work together in a real system. Five Kotlin/Spring Boot microservices, orchestrated by Temporal, communicating over Solace PubSub+, fronted by Kong — the whole stack runs locally with Docker Compose.

---

## What This Demonstrates

| Concept | Where to look |
|---------|--------------|
| Temporal Workflows | `order-service` → `OrderFulfillmentWorkflowImpl.kt` |
| Temporal Signals | `cancelOrder()`, `retryPayment()` signal handlers |
| Temporal Queries | `GET /workflows/{workflowId}` (live in-memory state) |
| Saga Pattern | Compensation paths in `OrderFulfillmentWorkflowImpl` |
| Event Sourcing | `Order.kt` aggregate + `EventStoreAdapter.kt` (in `order-service`) |
| Hexagonal Architecture | Inbound ports `OrderCommandUseCase`/`OrderQueryUseCase` in `domain/port/inbound/` |
| CQRS | `OrderCommandService` (write) vs `OrderQueryService` + `order_summary` projection (read) |
| Optimistic Locking | `UNIQUE(aggregate_id, version)` in V1 migration |
| Snapshotting | `OrderSnapshot` + `SnapshotStoreAdapter` |
| Idempotency | `record*()` methods in activity implementations; Redis-backed dedup in `notification-service` |
| Event Replay | `Order.reconstitute(events)` |
| DDD Aggregates | `Order.kt` — no setters, events-only state mutation |
| Value Objects | `Money`, `OrderId`, `CustomerId`, `OrderItem` |
| Microservices | 5 independently deployable Spring Boot services, one Maven module each |
| Kong API Gateway | `docker/docker-compose.yml` — proxy, admin, and manager UI |
| Solace Outbox Pattern | `@TransactionalEventListener(AFTER_COMMIT)` + a `@Scheduled` relay sweep in every service |

---

## Architecture Overview

```
┌─────────────┐    ┌──────────────────────────────────┐
│   Browser   │    │         Kong API Gateway         │
│   / curl    │───▶│  :8100 proxy  :8001 admin        │
└─────────────┘    │  :8002 manager UI                │
                    └──────────────┬───────────────────┘
                                   │ routes
       ┌───────────────────┬───────┼───────────┬────────────────────┐
       ▼                   ▼       ▼           ▼                    ▼
 order-service:8080  inventory:8081  payment:8082  shipping:8083  notification:8084
       │                   │              │              │              │
       │ gRPC :7233        └──────────────┴──────────────┴──────────────┘
       ▼                                  │
┌─────────────────┐              ┌──────────────────┐        ┌───────┐
│ Temporal Server  │              │ Solace PubSub+   │        │ Redis │
│ OrderFulfillment │              │  order.events    │        │ (dedup)│
│ Workflow (Saga)  │              │  payment.events  │        └───────┘
└─────────────────┘               │  inventory.events│
                                   │  shipping.events │
                                   └──────────────────┘
```

Each of the five services is a separate Spring Boot application with its own PostgreSQL database (except `notification-service`, which is stateless + Redis-backed). `order-service` owns the Temporal workflow and calls the other four services' REST APIs synchronously from Temporal activities; each service also publishes its domain events asynchronously to Solace via the outbox pattern, which `notification-service` consumes.

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.0.21 (JVM 21 target) |
| Framework | Spring Boot 3.2.5 |
| Workflow Orchestration | Temporal Java SDK 1.25.2 |
| Persistence | Spring Data JDBC + PostgreSQL 16 (one DB per service) |
| Schema Migration | Flyway |
| Message Broker | Solace PubSub+ (JMS) |
| API Gateway | Kong 3.6 |
| Serialization | Jackson (+ `jackson-module-kotlin`) |
| API Docs | springdoc-openapi 2.5.0 (Swagger UI per service + aggregator) |
| Observability | Micrometer + Prometheus + Grafana + Jaeger (OTLP tracing) |
| Idempotency store | Redis 7 (`notification-service`) |
| Testing | JUnit 5 + Testcontainers + Temporal `TestWorkflowEnvironment` |
| Build | Maven multi-module (6 modules) |
| Containers | Docker Compose (21 services) |

---

## Repository Layout

```
.
├── pom.xml                      # Parent POM — module list, dependency versions
├── shared-contracts/            # Messaging + HTTP API DTOs shared by every service (no Spring)
├── order-service/               # Owns the saga: domain, event sourcing, Temporal workflow
├── inventory-service/           # Stock reservation / release
├── payment-service/             # Charge / refund simulation
├── shipping-service/            # Shipment creation + delivery simulation
├── notification-service/        # Consumes order/payment events, idempotent via Redis
└── docker/
    ├── docker-compose.yml       # Full stack: 5 services + 5 Postgres + Temporal + Kong + Solace + observability
    ├── dynamicconfig/           # Temporal dynamic config
    ├── kong-init.sh             # Registers routes/plugins with Kong's Admin API
    └── prometheus.yml / grafana-dashboard.json
```

`order-service` is the most involved module — it owns the domain model, event store, and the Temporal saga:

```
order-service/src/main/kotlin/com/example/ordermanagement/
├── api/                          # Controllers, request/response DTOs, MapStruct mapper
├── application/service/          # OrderCommandService (writes) / OrderQueryService (reads)
├── domain/                       # Aggregate, events, commands, value objects — zero framework deps
│   ├── aggregate/Order.kt            Event-sourced aggregate root
│   ├── event/DomainEvent.kt          Sealed interface, exhaustive `when` in apply()
│   └── port/outbound/                Interfaces the infrastructure layer implements
├── infrastructure/
│   ├── persistence/                  Event store (JSONB) + snapshot store adapters
│   ├── projection/ + messaging/OrderProjectionConsumer.kt   CQRS read model
│   └── temporal/
│       ├── activity/                 HTTP clients calling the other 4 services
│       └── workflow/OrderFulfillmentWorkflowImpl.kt   The saga
└── config/                       # Temporal, Jackson, CORS, OpenAPI config
```

Each of the other four services follows a lighter hexagonal layout (`api/` → `application/` → `domain/` → `infrastructure/`), with its own `infrastructure/outbox/` package implementing the outbox pattern for publishing to Solace.

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
Shipment created (UPS)           v7: ShipmentCreatedEvent
Delivered                        v8: ShipmentDeliveredEvent
```

`GET /orders/{id}` replays all events and returns current state.
`GET /orders/{id}/history` shows every event with its full payload.
`GET /orders/{id}/timeline` shows human-readable descriptions.

**Snapshot optimization:** After every 50 events, state is snapshotted. Future loads restore from the snapshot and replay only the events after it.

---

## Temporal Workflow — The Saga

```
fulfill(orderId)
│
├─ STEP 1: reserveInventory()        ← calls inventory-service (retries + backoff)
│   SUCCESS → reservationId stored, recordInventoryReserved()
│   FAILURE → recordInventoryReservationFailed(), cancel order
│
├─ STEP 2: processPayment()          ← calls payment-service
│   SUCCESS → transactionId stored, recordPaymentCompleted()
│   TRANSIENT FAIL → Workflow.await(retryPayment signal)
│   NON-RETRYABLE / EXHAUSTED → releaseInventory + cancel
│
├─ STEP 3: createShipment()          ← calls shipping-service
│   SUCCESS → shipmentId stored, recordShipmentCreated()
│   FAILURE → refundPayment + releaseInventory + cancel
│
├─ STEP 4: confirmDelivery() → recordShipmentDelivered()
│
└─ STEP 5: sendOrderDeliveredNotification() → COMPLETED
```

A `cancelOrder(reason)` signal can be sent at any point while the workflow is running; a `retryPayment()` signal unblocks a workflow waiting on a transient payment failure. `GET /workflows/{workflowId}` runs a Temporal **Query** — it reads live in-memory workflow state, no DB hit.

Each step between activities (and each outbox relay sweep) is artificially slowed via `SIMULATION_STEP_DELAY_MS` in `docker-compose.yml` so you can actually watch a saga progress through the Temporal UI instead of it completing instantly.

---

## Setup & Run

### Prerequisites

- **JDK 21**, available at a known path (not just whatever `java` resolves to — see the gotcha below)
- **Maven 3.9+**
- **Docker Desktop**, running, with enough resources for ~21 containers (5 services, 5 Postgres, Temporal + UI, Kong + its Postgres, Solace, Redis, Prometheus, Grafana, Jaeger, Swagger UI, pgAdmin)

> **Gotcha:** the Kotlin 2.0.21 compiler can't parse the JDK version string on newer JDK builds (e.g. JDK 25) and fails with `IllegalArgumentException: 25.0.2`. Point `JAVA_HOME` at an actual JDK 21 install when building, even if a newer JDK is your shell default:
> ```bash
> # find an installed JDK 21, e.g.:
> /usr/libexec/java_home -v 21        # macOS
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)
> export PATH="$JAVA_HOME/bin:$PATH"
> ```

### 1. Build all modules

```bash
mvn clean package -DskipTests
```

This builds `shared-contracts` first, then the five services, producing a Spring Boot fat JAR per service under `*/target/*.jar`. Each service's `Dockerfile` just copies that prebuilt JAR — Docker Compose does **not** build Java/Kotlin for you.

### 2. Start the full stack

```bash
cd docker
docker compose up -d --build
```

First run builds all five service images (fast — they just layer the already-built JAR) and starts everything: databases, Temporal, Solace, Kong, Redis, the observability stack, and the five services in dependency order.

Check status:

```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}"
```

Everything should settle to `healthy` within about a minute. If a service never goes healthy, check its logs: `docker compose logs <service-name> --tail 100`.

### 3. Rebuild a single service after a code change

```bash
mvn -pl shared-contracts,inventory-service -am clean package -DskipTests   # -am also rebuilds shared-contracts if it changed
cd docker && docker compose up -d --build inventory-service
```

### 4. Tear down

```bash
cd docker
docker compose down          # stop + remove containers, keep data volumes
docker compose down -v       # also wipe all database/queue data
```

### Access Points

| URL | What it is |
|-----|-----------|
| http://localhost:8080 | **order-service** — owns the saga |
| http://localhost:8081 | inventory-service |
| http://localhost:8082 | payment-service |
| http://localhost:8083 | shipping-service |
| http://localhost:8084 | notification-service |
| http://localhost:8080/swagger-ui.html (and `:8081`–`:8084`) | Swagger UI per service |
| http://localhost:8089 | **Aggregated Swagger UI** — all five APIs in one place |
| http://localhost:8088 | **Temporal UI** — workflow visualizer |
| http://localhost:8100 | Kong proxy (routes to all services) |
| http://localhost:8001 / :8002 | Kong Admin API / Kong Manager UI |
| http://localhost:5050 | pgAdmin (`admin@admin.com` / `admin`) — all 5 Postgres instances pre-registered |
| http://localhost:9090 | Prometheus |
| http://localhost:3000 | Grafana (`admin` / `admin`) |
| http://localhost:16686 | Jaeger UI (distributed tracing) |
| http://localhost:8085 | Solace PubSub+ Manager (SEMP, `admin`/`admin`) |
| `localhost:6379` | Redis (notification-service idempotency store) |
| `*/actuator/health` | Health check, any service |

---

## Demo Walkthrough

The order flow needs a **real product** that exists in inventory-service's catalog — ordering a made-up `productId` fails with `InsufficientStockException`, which is correct behavior, not a bug. List the seeded catalog first:

```bash
curl -s http://localhost:8081/inventory | python3 -m json.tool
```

Seeded products (same UUIDs across runs via Flyway seed data):

| productId | name |
|---|---|
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11` | Laptop Pro 15in |
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12` | Wireless Mouse |
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13` | USB-C Hub |
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14` | Mechanical Keyboard |
| `a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15` | Monitor 27in |

### Happy path

```bash
# 1. Create order (customerId must be a UUID)
CUST_ID=$(uuidgen)
ORDER_ID=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"$CUST_ID\", \"shippingAddress\": \"1 Infinite Loop, Cupertino\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])")
echo "ORDER_ID=$ORDER_ID"

# 2. Add a real, seeded item
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/items \
  -H "Content-Type: application/json" \
  -d '{"productId": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12", "productName": "Wireless Mouse", "quantity": 2, "unitPrice": 29.99}'

# 3. Confirm → starts the Temporal workflow (async)
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/confirm

# 4. Watch it progress (each saga step is slowed ~10s so you can see it move)
watch -n2 "curl -s http://localhost:8080/orders/\$ORDER_ID | python3 -m json.tool"

# 5. See the full event trail once it settles
curl -s http://localhost:8080/orders/$ORDER_ID/timeline | python3 -m json.tool
```

A full run takes roughly 30–60 seconds and ends either `DELIVERED` (happy path) or `CANCELLED` (see below) — watch it unfold live in the Temporal UI at http://localhost:8088.

### Simulated failures (saga compensation)

Every downstream service rolls dice on each call, configured via env vars in `docker-compose.yml`:

| Service | Env var | Default |
|---|---|---|
| inventory-service | `SIMULATION_INVENTORY_FAILURE_RATE` | `0.10` |
| payment-service | `SIMULATION_PAYMENT_FAILURE_RATE` / `SIMULATION_PAYMENT_TRANSIENT_RATIO` | `0.00` / `0.30` |
| shipping-service | `SIMULATION_SHIPPING_FAILURE_RATE` | `0.10` |

So roughly 1 in 10 runs will hit a simulated inventory or shipping failure and you'll see the saga correctly compensate: `InventoryReservationFailedEvent` → `OrderCancelledEvent` (cancelled by `SYSTEM_COMPENSATION`) in `GET /orders/{id}/timeline`. To force this reliably for a demo, bump the relevant rate to `1.0` in `docker-compose.yml` and `docker compose up -d <service>`; set it to `0.0` to guarantee the happy path.

### Cancel mid-flight / retry a stuck payment

```bash
WORKFLOW_ID="order-fulfillment-$ORDER_ID"

curl -s -X POST http://localhost:8080/workflows/$WORKFLOW_ID/signal/cancel \
  -H "Content-Type: application/json" -d '{"reason":"Customer changed mind"}'

curl -s -X POST http://localhost:8080/workflows/$WORKFLOW_ID/signal/retry-payment
```

---

## All Endpoints (order-service)

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
| `GET` | `/workflows/{workflowId}` | Temporal Query (live workflow state) |
| `POST` | `/workflows/{id}/signal/cancel` | Cancel signal |
| `POST` | `/workflows/{id}/signal/retry-payment` | Retry signal |
| `GET` | `/actuator/health` | Health |
| `GET` | `/actuator/prometheus` | Prometheus scrape |

`inventory-service`, `payment-service`, `shipping-service`, and `notification-service` each expose their own narrower REST API — see their Swagger UI (ports 8081–8084) or the aggregator at http://localhost:8089.

---

## Running Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # same JDK-21 pin as building

# A single service's tests
mvn -pl order-service -am test

# Everything (unit + Testcontainers-backed integration tests where present)
mvn test
```

`order-service` has the deepest test suite: domain aggregate tests, `OrderCommandService` tests, a `TestWorkflowEnvironment`-based workflow test, a live integration test, and an event-store integration test (Testcontainers, requires Docker).

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
Commands change state through the aggregate (write side). Queries use two paths: single-order `GET /orders/{id}` replays events from the event store (full audit trail, always consistent), while `GET /orders` queries the `order_summary` read model — a denormalized projection updated by `OrderProjectionConsumer` on every domain event. This means list queries with status/customerId filters work with plain SQL and return in sub-milliseconds regardless of how many events an order has accumulated.

**Why microservices + an outbox per service instead of one shared publisher?**
Each service owns its own data and its own failure domain. The outbox pattern (write to DB, then relay to Solace after commit) is duplicated per service rather than shared, which keeps each service deployable and testable independently — the tradeoff explicitly accepted for this project.

---

## Observability

| Tool | URL | What for |
|---|---|---|
| Temporal UI | :8088 | Per-workflow execution history, retries, signals |
| Prometheus | :9090 | Raw metrics (`orders.created`, `payments.failed`, JVM/HTTP metrics, etc.) |
| Grafana | :3000 | Dashboards on top of Prometheus |
| Jaeger | :16686 | Distributed traces across all five services (OTLP) |

All log lines include a correlation ID (`CorrelationIdFilter` in `order-service`, extracted from `X-Correlation-ID` or generated) so a single request can be traced across log lines and, via Jaeger, across service boundaries.

---

## Learning Checkpoints

After studying this codebase you should be able to answer:

1. Why does `Order.kt` have no setters? *(All state changes go through events — the aggregate exposes only `val` properties and command methods, never mutable `var` setters)*
2. What happens when a Temporal worker crashes mid-activity? *(Temporal replays from the last checkpoint on restart — try it: `docker compose stop order-service` mid-saga, then `docker compose start order-service` and watch the workflow resume.)*
3. How does optimistic locking work without DB locks? *(`UNIQUE(aggregate_id, version)` + a version check on write)*
4. What's the difference between a Temporal Query and `GET /orders/{id}`? *(Query: live in-memory workflow state; GET: DB event replay)*
5. What is the Saga pattern here? *(Each forward step has a compensating step; failures trigger undo in reverse — see `OrderFulfillmentWorkflowImpl`)*
6. Why are domain events `sealed`? *(`DomainEvent` is a Kotlin `sealed interface` implemented by immutable `data class` events — the compiler enforces exhaustive handling in the `when` expression inside `apply()`)*
7. What is the Outbox Pattern and why does it matter? *(Persist to DB first, publish to Solace after commit — prevents the dual-write problem; each service has its own `infrastructure/outbox/` relay)*
8. Why does `notification-service` deduplicate via Redis instead of relying on at-most-once delivery? *(JMS/Solace redelivery on consumer failure can deliver the same message twice; `EventIdempotencyService` keys on `eventId` with a 24h TTL)*
9. When would you take a snapshot and when wouldn't you? *(High-event aggregates benefit; if most aggregates have <20 events the overhead isn't worth it)*
10. Why does `GET /orders/{id}` use event replay while `GET /orders` uses the projection? *(`/orders/{id}` needs the full audit trail and strong consistency for the caller who just wrote; list queries need filtering/pagination across all orders, which would require replaying every aggregate)*
11. What happens if the `order_summary` projection misses an update? *(The event store is still the source of truth — the projection can be rebuilt by replaying all events through `OrderProjectionConsumer`.)*
