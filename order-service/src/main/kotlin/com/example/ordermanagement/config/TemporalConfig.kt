package com.example.ordermanagement.config

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration: TemporalConfig
 *
 * ═══════════════════════════════════════════════════════════════════
 * TEMPORAL CONNECTION SETUP
 * ═══════════════════════════════════════════════════════════════════
 *
 * WorkflowServiceStubs: Low-level gRPC connection to Temporal server.
 *   Think of this as the JDBC DataSource equivalent for Temporal.
 *
 * WorkflowClient: High-level client for starting workflows, sending signals,
 *   and running queries. Uses WorkflowServiceStubs under the hood.
 *
 * Note: We're using Temporal's Spring Boot auto-configuration (@ActivityImpl)
 * for worker registration. This auto-configures the worker with all beans
 * annotated with @ActivityImpl and all @WorkflowInterface implementations.
 *
 * The WorkflowClient bean is used by WorkflowPortAdapter to start workflows
 * and interact with running workflow instances.
 *
 * ═══════════════════════════════════════════════════════════════════
 * CRITICAL — KOTLIN-AWARE DataConverter (Temporal + Kotlin data classes)
 * ═══════════════════════════════════════════════════════════════════
 * Temporal's `DefaultDataConverter` builds its OWN internal Jackson
 * `ObjectMapper`, completely independent of any Spring-managed
 * `ObjectMapper` (see JacksonConfig) and independent of the
 * `jackson-module-kotlin` registration Spring Boot auto-detects for
 * MVC/REST. Registering the Kotlin module on the Spring bean does NOT
 * reach Temporal.
 *
 * Now that this migration has converted the workflow/activity payload
 * types to Kotlin `data class`es — e.g. `WorkflowDefinition`/`WorkflowStep`
 * (infrastructure/temporal/workflow/model), and the small result records
 * nested in the activity interfaces such as
 * `PaymentActivity.PaymentResult`, `InventoryActivity.ReservationResult`,
 * `ShippingActivity.ShipmentResult` — every one of those types is
 * serialized/deserialized by Temporal's converter whenever a workflow or
 * activity method is invoked, and again on every workflow history replay.
 * Without a Kotlin-aware Jackson instance backing that converter, a
 * `data class` with no no-arg constructor fails to deserialize. This does
 * NOT show up as a compile error — it surfaces later as a broken workflow
 * history replay (e.g. after a worker restart), which is why this is
 * called out as the single most important correctness fix in this
 * migration.
 *
 * THE FIX:
 *   1. Start from Temporal's own default payload ObjectMapper
 *      (`JacksonJsonPayloadConverter.newDefaultObjectMapper()`) so we keep
 *      whatever Temporal itself relies on, then layer `registerKotlinModule()`
 *      on top so Kotlin `data class` primary constructors deserialize
 *      correctly.
 *   2. Wrap that ObjectMapper in a `JacksonJsonPayloadConverter` and swap it
 *      into `DefaultDataConverter`'s standard payload converter chain via
 *      `withPayloadConverterOverrides(...)` (this replaces only the JSON
 *      converter — the binary/proto/null converters are untouched).
 *   3. Set the resulting `DataConverter` on `WorkflowClientOptions`
 *      (client side).
 *   4. The worker side automatically uses the SAME instance: Temporal's
 *      `WorkerFactory` has no separate `setDataConverter` hook on
 *      `WorkerFactoryOptions` — a `WorkerFactory` is built FROM a
 *      `WorkflowClient` (`WorkerFactory.newInstance(workflowClient)`) and
 *      derives its converter from that client's options. So as long as
 *      the same `workflowClient` bean feeds both `WorkflowPortAdapter`
 *      (client side, infrastructure/temporal) and `workerFactory` (worker
 *      side, infrastructure/temporal/worker — TemporalWorkerSetup), client
 *      and worker are guaranteed to agree. NEVER construct a second,
 *      independently-configured `WorkflowClient` for the worker path —
 *      that would desync the converters and silently corrupt replay.
 *
 * The `DataConverter` is also exposed as its own `@Bean` in case any other
 * component (tests, a future adapter) needs to hand-construct Temporal
 * stubs and must use this exact instance rather than Temporal's built-in
 * default.
 */
@Configuration
class TemporalConfig(
    @Value("\${temporal.service-address:localhost:7233}") private val temporalServiceAddress: String,
    @Value("\${temporal.namespace:default}") private val namespace: String
) {

    /**
     * Kotlin-aware DataConverter shared by the WorkflowClient bean below and
     * every Worker built from it (see class-level doc for why this instance
     * must not be duplicated).
     */
    @Bean
    fun temporalDataConverter(): DataConverter {
        val kotlinAwareMapper = JacksonJsonPayloadConverter.newDefaultObjectMapper()
            .registerKotlinModule()

        return DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(JacksonJsonPayloadConverter(kotlinAwareMapper))
    }

    /**
     * gRPC stub to the Temporal server.
     * In production: use TLS, health checks, and connection pooling.
     */
    @Bean
    fun workflowServiceStubs(): WorkflowServiceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalServiceAddress)
                .build()
        )

    /**
     * Temporal WorkflowClient.
     * Namespace defaults to "default" — in production, use separate namespaces
     * per environment (dev, staging, prod) for isolation.
     */
    @Bean
    fun workflowClient(stubs: WorkflowServiceStubs, dataConverter: DataConverter): WorkflowClient =
        WorkflowClient.newInstance(
            stubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .setDataConverter(dataConverter)
                .build()
        )

    /**
     * WorkerFactory creates and manages Temporal workers.
     * Workers are the processes that execute workflow and activity code.
     * The Spring Boot Temporal integration auto-configures workers from
     * @ActivityImpl and WorkflowInterface implementations.
     *
     * Built FROM workflowClient (not from workflowServiceStubs directly) so
     * it inherits the same Kotlin-aware DataConverter — see class-level doc.
     */
    @Bean
    fun workerFactory(workflowClient: WorkflowClient): WorkerFactory =
        WorkerFactory.newInstance(workflowClient)
}
