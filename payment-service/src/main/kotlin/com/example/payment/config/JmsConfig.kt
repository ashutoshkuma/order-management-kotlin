package com.example.payment.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jms.support.converter.MappingJackson2MessageConverter
import org.springframework.jms.support.converter.MessageConverter
import org.springframework.jms.support.converter.MessageType

/**
 * Configuration: JmsConfig
 *
 * Spring's auto-configured `JmsTemplate` defaults to `SimpleMessageConverter`,
 * which can only convert a payload that is a String, byte[], Map<String,?>, or
 * a `java.io.Serializable` object. Our message payloads (`OrderEventMessage`,
 * `PaymentEventMessage`, etc. from shared-contracts) are plain Kotlin data
 * classes that do NOT implement `Serializable` — under the old Kafka setup
 * this was a non-issue because `KafkaTemplate` used a Jackson-based
 * `JsonSerializer` that works via reflection, not Java serialization. JMS has
 * no equivalent default, so publishing silently throws
 * `MessageConversionException` unless we register a Jackson-based converter
 * ourselves. `@ConditionalOnMissingBean` in Spring Boot's JMS autoconfiguration
 * means defining this bean here is enough for the auto-configured
 * `JmsTemplate`/listener containers to pick it up automatically.
 */
@Configuration
class JmsConfig {

    @Bean
    fun jmsMessageConverter(): MessageConverter {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val converter = MappingJackson2MessageConverter()
        converter.setObjectMapper(mapper)
        // Text (JSON string), not a JMS BytesMessage — matches the plain-JSON
        // shape the old Kafka JsonSerializer produced.
        converter.setTargetType(MessageType.TEXT)
        return converter
    }
}
