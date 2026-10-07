package com.example.ordermanagement.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Configuration: JacksonConfig
 *
 * Configures Jackson ObjectMapper for event serialization/deserialization
 * (used by Spring MVC, and by the event store's JSONB (de)serialization).
 *
 * KEY SETTINGS:
 * - JavaTimeModule: Handles Java 8+ date/time types (Instant, LocalDate)
 *   Events contain Instant timestamps — requires this module.
 *
 * - registerKotlinModule(): Domain events, commands, and DTOs are now
 *   Kotlin data classes. Without this, Jackson falls back to reflection
 *   over the no-arg constructor it can't find and deserialization of
 *   incoming request bodies / replayed events fails at runtime.
 *   NOTE: this ObjectMapper is Spring's MVC/general-purpose bean only —
 *   Temporal's workflow DataConverter uses a completely separate Jackson
 *   instance and needs its own Kotlin registration (see TemporalConfig).
 *
 * - WRITE_DATES_AS_TIMESTAMPS: false → ISO-8601 string format in JSON
 *   Human-readable in event store, easier debugging.
 *
 * - FAIL_ON_UNKNOWN_PROPERTIES: false → Schema evolution compatibility
 *   Old events may not have new fields. We should load them gracefully.
 *
 * - ParameterNamesModule: Allows Jackson to use constructor parameter names
 *   for deserialization without @JsonProperty annotations on every field.
 */
@Configuration
class JacksonConfig {

    @Bean
    @Primary
    fun objectMapper(): ObjectMapper {
        val mapper = ObjectMapper()

        // Java time support (Instant in events)
        mapper.registerModule(JavaTimeModule())
        // Use constructor parameter names for deserialization
        mapper.registerModule(ParameterNamesModule())
        // Kotlin data class support (primary constructors, default params)
        mapper.registerKotlinModule()

        // Serialize Instant as "2024-01-15T14:30:00Z", not as epoch millis
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        // Critical for event evolution: new code reading old events with missing fields
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        // Indent output for readability in event store (optional — remove in prod for space)
        // mapper.enable(SerializationFeature.INDENT_OUTPUT)

        return mapper
    }
}
