package com.example.ordermanagement.config

import com.example.contracts.messaging.OrderEventMessage
import com.example.contracts.messaging.ShippingEventMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.solacesystems.jcsmp.EndpointProperties
import com.solacesystems.jcsmp.JCSMPErrorResponseException
import com.solacesystems.jcsmp.JCSMPErrorResponseSubcode
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPProperties
import com.solacesystems.jcsmp.JCSMPSession
import jakarta.annotation.PostConstruct
import jakarta.jms.ConnectionFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jms.annotation.EnableJms
import org.springframework.jms.config.DefaultJmsListenerContainerFactory
import org.springframework.jms.support.converter.MappingJackson2MessageConverter
import org.springframework.jms.support.converter.MessageConverter
import org.springframework.jms.support.converter.MessageType

/**
 * Configuration: JmsConsumerConfig (formerly KafkaConsumerConfig)
 *
 * ═══════════════════════════════════════════════════════════════════
 * KAFKA → SOLACE PUBSUB+ (JMS)
 * ═══════════════════════════════════════════════════════════════════
 * order-service/pom.xml no longer has spring-kafka on the classpath at
 * all — the original KafkaConsumerConfig (ConcurrentKafkaListenerContainerFactory,
 * DefaultErrorHandler + DeadLetterPublishingRecoverer + FixedBackOff)
 * would not even compile against the current dependency set. This class
 * replaces it with the Solace/JMS equivalent, per architecture.md's
 * "Solace Rules" section, and has two responsibilities:
 *
 *   1. Provide the `DefaultJmsListenerContainerFactory` beans that the
 *      `@JmsListener`-annotated consumers in infrastructure/messaging
 *      (`ShippingEventConsumer`, `OrderProjectionConsumer` — converted by
 *      a sibling agent) reference by name: "shippingListenerContainerFactory"
 *      and "projectionListenerContainerFactory". Concurrency on the
 *      shipping factory mirrors the old Kafka factory's
 *      `setConcurrency(3)`.
 *
 *   2. Self-provision, idempotently at startup, the durable queues those
 *      listeners consume from plus their topic subscriptions — this is
 *      the "// TODO(config-agent): provision durable queue ... here" work
 *      those sibling-owned consumer files flagged directly above their
 *      `@JmsListener` methods. Kafka auto-created topics/consumer-groups
 *      on first use; Solace queues do not, so this reproduces that
 *      "just works on docker compose up" experience using the Solace JMS
 *      client's JCSMP admin extensions — plain `jakarta.jms` has no
 *      provisioning calls (see architecture.md Solace Rules).
 *
 * DLQ / DMQ NOTE: per architecture.md, in-app retry (2 attempts, 2s apart)
 * already happens inside each `@JmsListener` body; only the final
 * exhausted-retry exception reaches Solace. Each provisioned queue here
 * sets `maxMsgRedelivery = 1`, so one more broker-side redelivery is
 * attempted before the message is retired to the message-VPN's Dead
 * Message Queue. Routing exhausted messages to a **topic-scoped** custom
 * DMQ (rather than the VPN-wide default `#DEAD_MSG_QUEUE`) requires a
 * SEMPv2 admin call that isn't exposed by the JCSMP/JMS client APIs used
 * here, so it is intentionally not attempted — flagged rather than faked.
 *
 * Connection properties reuse the exact `solace.jms.*` keys the
 * `solace-jms-spring-boot-starter` autoconfiguration itself binds
 * (`SolaceJmsProperties`), so this admin session always targets the same
 * broker/VPN/credentials as the messaging connection factory — one source
 * of truth, no drift.
 */
@Configuration
@EnableJms
class JmsConsumerConfig(
    @Value("\${solace.jms.host:smf://localhost:55555}") private val host: String,
    @Value("\${solace.jms.msg-vpn:default}") private val vpnName: String,
    @Value("\${solace.jms.client-username:default}") private val username: String,
    @Value("\${solace.jms.client-password:default}") private val password: String
) {

    companion object {
        private val log = LoggerFactory.getLogger(JmsConsumerConfig::class.java)

        private const val SHIPPING_TOPIC = "shipping.events"
        private const val SHIPPING_QUEUE = "order-service-group"

        private const val PROJECTION_TOPIC = "order.events"
        private const val PROJECTION_QUEUE = "order-projection-group"

        // In-app retry (2 attempts, 2s apart) already happened inside the
        // @JmsListener body by the time a message reaches the broker as a
        // redelivery candidate — one more broker-side attempt before the
        // message is retired to the VPN's Dead Message Queue.
        private const val MAX_MSG_REDELIVERY = 1
    }

    // ═══════════════════════════════════════════════════════════════════
    // Listener container factories
    // ═══════════════════════════════════════════════════════════════════

    // Spring's default SimpleMessageConverter only handles String/byte[]/Map/
    // Serializable payloads — our shared-contracts message types are plain
    // Kotlin data classes (not Serializable). This bean is what makes
    // JmsTemplate.convertAndSend(...) and these listener factories actually
    // serialize/deserialize them as JSON, the way the old Kafka JsonSerializer
    // did. Spring Boot's own JmsTemplate autoconfiguration picks this up
    // automatically (@ConditionalOnMissingBean); the two manually-built
    // factories below need it wired in explicitly.
    @Bean
    fun jmsMessageConverter(): MessageConverter {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val converter = MappingJackson2MessageConverter()
        converter.setObjectMapper(mapper)
        converter.setTargetType(MessageType.TEXT)
        return converter
    }

    // Spring's JMS listener adapter calls the non-hint MessageConverter.fromMessage(Message)
    // overload (not the SmartMessageConverter hint-aware one), so
    // MappingJackson2MessageConverter falls back to reading a type-id JMS
    // message property that we never set on the producer side (Jackson's own
    // @JsonTypeInfo in the message body already handles picking the right
    // sealed-interface variant — we don't need a second, JMS-property-based
    // type mechanism). With no property name configured, Solace's SolMessage
    // NPEs instead of returning null the way some other JMS providers would
    // ("name supplied is null" — verified against a live broker). Since each
    // listener here always expects exactly one declared type, the fix is to
    // skip that lookup entirely and always resolve to a fixed type.
    private fun fixedTypeConverter(mapper: ObjectMapper, targetType: Class<*>): MappingJackson2MessageConverter {
        val converter = object : MappingJackson2MessageConverter() {
            override fun getJavaTypeForMessage(message: jakarta.jms.Message) =
                mapper.typeFactory.constructType(targetType)
        }
        converter.setObjectMapper(mapper)
        converter.setTargetType(MessageType.TEXT)
        return converter
    }

    @Bean
    fun shippingListenerContainerFactory(
        connectionFactory: ConnectionFactory,
    ): DefaultJmsListenerContainerFactory {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val factory = DefaultJmsListenerContainerFactory()
        factory.setConnectionFactory(connectionFactory)
        factory.setMessageConverter(fixedTypeConverter(mapper, ShippingEventMessage::class.java))
        // Mirrors the old Kafka factory's setConcurrency(3) — competing
        // consumers on the durable queue provisioned below give the same
        // load-balancing behaviour a Kafka consumer group would.
        factory.setConcurrency("3-3")
        factory.setSessionTransacted(true)
        return factory
    }

    @Bean
    fun projectionListenerContainerFactory(
        connectionFactory: ConnectionFactory,
    ): DefaultJmsListenerContainerFactory {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val factory = DefaultJmsListenerContainerFactory()
        factory.setConnectionFactory(connectionFactory)
        factory.setMessageConverter(fixedTypeConverter(mapper, OrderEventMessage::class.java))
        factory.setSessionTransacted(true)
        return factory
    }

    // ═══════════════════════════════════════════════════════════════════
    // Idempotent queue + topic-subscription provisioning
    // ═══════════════════════════════════════════════════════════════════

    @PostConstruct
    fun provisionQueues() {
        val session = openAdminSession()
        try {
            provisionQueue(session, SHIPPING_QUEUE, SHIPPING_TOPIC)
            provisionQueue(session, PROJECTION_QUEUE, PROJECTION_TOPIC)
        } finally {
            session.closeSession()
        }
    }

    private fun openAdminSession(): JCSMPSession {
        val properties = JCSMPProperties()
        // `solace.jms.host` uses the JMS-layer "smf://" scheme (per Solace's JMS
        // autoconfiguration docs), but raw JCSMP's HOST property doesn't recognize
        // that scheme — it wants "tcp://"/"tcps://" for plain/TLS SMF. Both denote
        // the same underlying SMF transport, so this is a safe scheme swap, not a
        // protocol change.
        properties.setProperty(JCSMPProperties.HOST, host.replaceFirst("smf://", "tcp://"))
        properties.setProperty(JCSMPProperties.VPN_NAME, vpnName)
        properties.setProperty(JCSMPProperties.USERNAME, username)
        properties.setProperty(JCSMPProperties.PASSWORD, password)

        val session = JCSMPFactory.onlyInstance().createSession(properties)
        session.connect()
        return session
    }

    private fun provisionQueue(session: JCSMPSession, queueName: String, topicName: String) {
        val queue = JCSMPFactory.onlyInstance().createQueue(queueName)

        val endpointProperties = EndpointProperties()
        // Non-exclusive = competing consumers, so this queue load-balances
        // across multiple order-service instances the way a Kafka consumer
        // group with concurrency > 1 would — never a bare Topic Subscription,
        // which is exclusive-by-default (architecture.md Solace Rules).
        endpointProperties.accessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE
        endpointProperties.permission = EndpointProperties.PERMISSION_CONSUME
        endpointProperties.maxMsgRedelivery = MAX_MSG_REDELIVERY

        // FLAG_IGNORE_ALREADY_EXISTS makes this idempotent across restarts
        // and across multiple service instances racing to provision on boot.
        session.provision(queue, endpointProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS.toLong())

        val topic = JCSMPFactory.onlyInstance().createTopic(topicName)
        // addSubscription(Endpoint, Subscription, int) only accepts 0 or
        // WAIT_FOR_CONFIRM as its flags argument (verified against the JCSMP
        // bytecode) — FLAG_IGNORE_ALREADY_EXISTS is only valid for provision()
        // above, not here. Unlike provision(), the broker does NOT silently
        // no-op a duplicate subscription — it throws JCSMPErrorResponseException
        // with subcode SUBSCRIPTION_ALREADY_PRESENT (verified against a real
        // broker on container restart), so idempotency has to be handled here
        // by catching that specific subcode and treating it as success.
        try {
            session.addSubscription(queue, topic, JCSMPSession.WAIT_FOR_CONFIRM)
        } catch (e: JCSMPErrorResponseException) {
            if (e.subcode != JCSMPErrorResponseSubcode.SUBSCRIPTION_ALREADY_PRESENT) throw e
        }

        log.info("Provisioned durable queue '{}' subscribed to topic '{}'", queueName, topicName)
    }
}
