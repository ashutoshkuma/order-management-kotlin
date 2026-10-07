# ============================================================
# Multi-stage Dockerfile for Order Management Application
# ============================================================
# Stage 1: Build with Maven
# Stage 2: Runtime with minimal JRE 21
# ============================================================

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
# Download dependencies separately for Docker layer caching
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -q
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -q

# Runtime stage — minimal JRE
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Add non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# JVM settings for containers:
# - XX:+UseContainerSupport: respect container memory limits
# - XX:MaxRAMPercentage: use 75% of container memory for heap
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
