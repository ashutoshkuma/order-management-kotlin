#!/bin/sh
# Kong Admin API bootstrap — registers services, routes, and plugins.
# Runs once after Kong is healthy. All operations are idempotent (|| true).

KONG_ADMIN="http://kong:8001"

echo "Bootstrapping Kong..."

# ── order-service ────────────────────────────────────────────────────
curl -sf -o /dev/null -X PUT "$KONG_ADMIN/services/order-service" \
  -d "url=http://order-service:8080" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/order-api" \
  -d "service.name=order-service" \
  -d "paths[]=/orders" \
  -d "strip_path=false" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/workflow-api" \
  -d "service.name=order-service" \
  -d "paths[]=/workflows" \
  -d "strip_path=false" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/order-swagger" \
  -d "service.name=order-service" \
  -d "paths[]=/order-service" \
  -d "strip_path=true" || true

# Rate-limiting on all order-service traffic
curl -sf -o /dev/null -X POST "$KONG_ADMIN/services/order-service/plugins" \
  -d "name=rate-limiting" \
  -d "config.minute=60" \
  -d "config.hour=1000" \
  -d "config.policy=local" || true

# ── inventory-service ────────────────────────────────────────────────
curl -sf -o /dev/null -X PUT "$KONG_ADMIN/services/inventory-service" \
  -d "url=http://inventory-service:8081" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/inventory-api" \
  -d "service.name=inventory-service" \
  -d "paths[]=/inventory" \
  -d "paths[]=/reserve" \
  -d "strip_path=false" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/inventory-swagger" \
  -d "service.name=inventory-service" \
  -d "paths[]=/inventory-service" \
  -d "strip_path=true" || true

# ── payment-service ──────────────────────────────────────────────────
curl -sf -o /dev/null -X PUT "$KONG_ADMIN/services/payment-service" \
  -d "url=http://payment-service:8082" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/payment-api" \
  -d "service.name=payment-service" \
  -d "paths[]=/charge" \
  -d "paths[]=/refund" \
  -d "strip_path=false" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/payment-swagger" \
  -d "service.name=payment-service" \
  -d "paths[]=/payment-service" \
  -d "strip_path=true" || true

# ── shipping-service ─────────────────────────────────────────────────
curl -sf -o /dev/null -X PUT "$KONG_ADMIN/services/shipping-service" \
  -d "url=http://shipping-service:8083" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/shipping-api" \
  -d "service.name=shipping-service" \
  -d "paths[]=/shipments" \
  -d "paths[]=/deliveries" \
  -d "strip_path=false" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/shipping-swagger" \
  -d "service.name=shipping-service" \
  -d "paths[]=/shipping-service" \
  -d "strip_path=true" || true

# ── notification-service ─────────────────────────────────────────────
curl -sf -o /dev/null -X PUT "$KONG_ADMIN/services/notification-service" \
  -d "url=http://notification-service:8084" || true

curl -sf -o /dev/null -X PUT "$KONG_ADMIN/routes/notification-swagger" \
  -d "service.name=notification-service" \
  -d "paths[]=/notification-service" \
  -d "strip_path=true" || true

# ── global plugins ───────────────────────────────────────────────────
# Correlation ID plugin
curl -sf -o /dev/null -X POST "$KONG_ADMIN/plugins" \
  -d "name=correlation-id" \
  -d "config.header_name=X-Correlation-Id" \
  -d "config.generator=uuid#counter" \
  -d "config.echo_downstream=true" || true

# CORS plugin to allow Swagger UI (running on other ports/origins) to make requests
curl -sf -o /dev/null -X POST "$KONG_ADMIN/plugins" \
  -d "name=cors" \
  -d "config.origins=*" \
  -d "config.methods=GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH" \
  -d "config.headers=Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, Api-Version, Authorization, X-Correlation-Id" \
  -d "config.exposed_headers=X-Correlation-Id" \
  -d "config.credentials=true" || true

echo "Kong bootstrap complete."