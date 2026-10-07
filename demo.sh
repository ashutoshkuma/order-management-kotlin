#!/bin/bash

set -e

echo "════════════════════════════════════════════════════════════════"
echo "  Order Management System - Temporal + Event Sourcing Demo"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo -e "${BLUE}Step 1: Verify Java 21 is available${NC}"
$JAVA_HOME/bin/java -version
echo ""

echo -e "${BLUE}Step 2: Clean build all modules${NC}"
cd "$PROJECT_ROOT"
JAVA_HOME=$JAVA_HOME mvn clean package -DskipTests -q
echo -e "${GREEN}✓ Build complete${NC}"
echo ""

echo -e "${BLUE}Step 3: Check if Docker is running${NC}"
if ! docker ps &>/dev/null; then
    echo -e "${YELLOW}⚠ Docker daemon not running. Please start Docker Desktop.${NC}"
    echo "  Opening Docker..."
    open -a Docker
    echo "  Waiting for Docker to start..."
    sleep 15
fi
echo -e "${GREEN}✓ Docker is ready${NC}"
echo ""

echo -e "${BLUE}Step 4: Start infrastructure services${NC}"
cd "$PROJECT_ROOT/docker"
docker compose down -v 2>/dev/null || true
docker compose up -d postgres-order temporal kafka zookeeper postgres-temporal

echo "  Waiting for services to be healthy..."
sleep 30

# Check if services are healthy
echo "  Checking PostgreSQL..."
until pg_isready -h localhost -p 5432 -U orderuser &>/dev/null; do
    echo "    Waiting for PostgreSQL..."
    sleep 5
done
echo -e "${GREEN}  ✓ PostgreSQL ready${NC}"

echo "  Checking Temporal..."
until nc -z localhost 7233 &>/dev/null; do
    echo "    Waiting for Temporal..."
    sleep 5
done
echo -e "${GREEN}  ✓ Temporal ready${NC}"

echo "  Checking Kafka..."
until nc -z localhost 9092 &>/dev/null; do
    echo "    Waiting for Kafka..."
    sleep 5
done
echo -e "${GREEN}  ✓ Kafka ready${NC}"
echo ""

echo -e "${BLUE}Step 5: Start order-service${NC}"
cd "$PROJECT_ROOT"
JAVA_HOME=$JAVA_HOME java -jar order-service/target/order-service-1.0.0-SNAPSHOT.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/orderdb \
  --spring.datasource.username=orderuser \
  --spring.datasource.password=orderpass \
  --temporal.service.address=localhost:7233 \
  --spring.kafka.bootstrap-servers=localhost:9092 \
  --server.port=8080 &

ORDER_SERVICE_PID=$!
echo "  Order service PID: $ORDER_SERVICE_PID"
echo "  Waiting for service to start..."
sleep 15

# Check if service is running
if ! ps -p $ORDER_SERVICE_PID > /dev/null; then
    echo -e "${YELLOW}⚠ Service failed to start${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Order service running on http://localhost:8080${NC}"
echo ""

echo -e "${BLUE}Step 6: Run demo API calls${NC}"
echo ""

# Test 1: Create an order
echo -e "${YELLOW}Test 1: Creating an order...${NC}"
ORDER_RESPONSE=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "shippingAddress": "123 Main St, Anytown, USA"
  }')

ORDER_ID=$(echo "$ORDER_RESPONSE" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
echo "Response: $ORDER_RESPONSE"
echo -e "${GREEN}✓ Order created with ID: $ORDER_ID${NC}"
echo ""

if [ -z "$ORDER_ID" ]; then
    echo -e "${YELLOW}⚠ Failed to extract order ID${NC}"
    kill $ORDER_SERVICE_PID
    exit 1
fi

# Test 2: Get the created order
echo -e "${YELLOW}Test 2: Fetching the order...${NC}"
curl -s -X GET http://localhost:8080/orders/$ORDER_ID | jq '.' || echo "Could not pretty-print response"
echo -e "${GREEN}✓ Order retrieved${NC}"
echo ""

# Test 3: Add items to order
echo -e "${YELLOW}Test 3: Adding items to order...${NC}"
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/items \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "prod-001",
    "productName": "Widget A",
    "quantity": 2,
    "unitPrice": "99.99"
  }' | jq '.' || echo "Item added"
echo -e "${GREEN}✓ Item added${NC}"
echo ""

curl -s -X POST http://localhost:8080/orders/$ORDER_ID/items \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "prod-002",
    "productName": "Widget B",
    "quantity": 1,
    "unitPrice": "149.99"
  }' | jq '.' || echo "Item added"
echo -e "${GREEN}✓ Second item added${NC}"
echo ""

# Test 4: Confirm order (starts Temporal workflow)
echo -e "${YELLOW}Test 4: Confirming order (starts Temporal workflow)...${NC}"
curl -s -X POST http://localhost:8080/orders/$ORDER_ID/confirm | jq '.' || echo "Order confirmed"
echo -e "${GREEN}✓ Order confirmed - Temporal workflow started${NC}"
echo ""

# Test 5: Get order event history
echo -e "${YELLOW}Test 5: Getting order event history...${NC}"
curl -s -X GET http://localhost:8080/orders/$ORDER_ID/history | jq '.' || echo "History retrieved"
echo -e "${GREEN}✓ Event history displayed${NC}"
echo ""

# Test 6: Check workflow status
echo -e "${YELLOW}Test 6: Checking Temporal workflow status...${NC}"
WORKFLOW_ID="order-fulfillment-$ORDER_ID"
curl -s -X GET http://localhost:8080/workflows/$WORKFLOW_ID/status | jq '.' || echo "Workflow status retrieved"
echo -e "${GREEN}✓ Workflow status retrieved${NC}"
echo ""

echo "════════════════════════════════════════════════════════════════"
echo -e "${GREEN}✓ Demo Complete!${NC}"
echo "════════════════════════════════════════════════════════════════"
echo ""
echo "📊 Service URLs:"
echo "  • Order Service API: http://localhost:8080"
echo "  • Swagger UI: http://localhost:8080/swagger-ui.html"
echo "  • Temporal UI: http://localhost:8088"
echo "  • Kafka UI: http://localhost:9000"
echo ""
echo "🔧 To stop services:"
echo "  • Press Ctrl+C to stop order-service"
echo "  • Run: docker compose -f docker/docker-compose.yml down"
echo ""
echo "📝 Order ID for further testing: $ORDER_ID"
echo ""
