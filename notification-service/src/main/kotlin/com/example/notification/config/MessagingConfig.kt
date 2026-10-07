package com.example.notification.config

import com.example.notification.solace.SolaceDestinations
import com.solacesystems.jcsmp.EndpointProperties
import com.solacesystems.jcsmp.JCSMPFactory
import com.solacesystems.jcsmp.JCSMPProperties
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jms.SolConnectionFactory
import jakarta.jms.ConnectionFactory
import jakarta.jms.Session
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jms.annotation.EnableJms
import org.springframework.jms.config.DefaultJmsListenerContainerFactory

/**
 * Solace JMS wiring for notification-service.
 *
 * Everything is hand-built here rather than left to spring.jms.* properties, mirroring
 * the original KafkaConfig.java — this service never had spring.kafka.producer/consumer
 * blocks in application.yml either; connection details (host/vpn/credentials) come from
 * SOLACE_JMS_* env vars picked up by the solace-jms-spring-boot-starter's own
 * autoconfiguration, everything else (queues, DMQs, listener container factories) is
 * built and self-provisioned here.
 */
@EnableJms
@Configuration
class MessagingConfig {

    companion object {
        private val log = LoggerFactory.getLogger(MessagingConfig::class.java)

        // Mirrors the Kafka-era concurrency=3 — competing consumers on a non-exclusive queue.
        private const val NOTIFICATION_CONCURRENCY = "3"
        private const val DMQ_CONCURRENCY = "1"
    }

    // ── Listener container factories ────────────────────────────────────

    @Bean
    fun notificationListenerContainerFactory(
        connectionFactory: ConnectionFactory,
    ): DefaultJmsListenerContainerFactory =
        DefaultJmsListenerContainerFactory().apply {
            setConnectionFactory(connectionFactory)
            // Listener methods take the raw jakarta.jms.Message, so no MessageConverter
            // is configured here — NotificationQueueListener deserializes explicitly via
            // ObjectMapper once it knows which contract type the origin topic implies.
            setConcurrency(NOTIFICATION_CONCURRENCY)
            setSessionTransacted(false)
            setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE)
        }

    @Bean
    fun dmqListenerContainerFactory(
        connectionFactory: ConnectionFactory,
    ): DefaultJmsListenerContainerFactory =
        DefaultJmsListenerContainerFactory().apply {
            setConnectionFactory(connectionFactory)
            // DLQ consumer: single consumer, no in-app retry, must never recurse into
            // another DLQ — mirrors the original's dlqListenerContainerFactory.
            setConcurrency(DMQ_CONCURRENCY)
            setSessionTransacted(false)
            setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE)
        }

    // ── Startup self-provisioning (idempotent) ─────────────────────────

    @Bean
    fun provisionSolaceQueues(connectionFactory: SolConnectionFactory): CommandLineRunner =
        CommandLineRunner { provision(connectionFactory) }

    /**
     * Self-provisions the notification-service-group queue (with topic subscriptions to
     * both order.events and payment.events) and the two per-topic DMQs, idempotently, at
     * startup — the "just works on docker compose up" experience Kafka's topic
     * auto-create gave us (architecture.md).
     *
     * The jakarta.jms.Session API exposes no admin/provisioning surface at all — per
     * architecture.md's "Solace-specific Session casts, not portable jakarta.jms API",
     * we reach for the Solace client's own admin extensions. In this client library
     * that surface lives in the bundled JCSMP layer (com.solacesystems.jcsmp), not on
     * the JMS Session type itself, so a short-lived admin JCSMPSession is built here
     * from the same connection details as the autoconfigured JMS SolConnectionFactory,
     * used only for provisioning, and closed immediately after. Actual publish/consume
     * for real traffic still goes exclusively through JmsTemplate / @JmsListener.
     */
    private fun provision(connectionFactory: SolConnectionFactory) {
        val adminProps = JCSMPProperties().apply {
            setProperty(JCSMPProperties.HOST, connectionFactory.host)
            setProperty(JCSMPProperties.VPN_NAME, connectionFactory.vpn)
            setProperty(JCSMPProperties.USERNAME, connectionFactory.username)
            setProperty(JCSMPProperties.PASSWORD, connectionFactory.password)
        }

        var session: JCSMPSession? = null
        try {
            session = JCSMPFactory.onlyInstance().createSession(adminProps)
            session.connect()

            val queueProps = EndpointProperties().apply {
                accessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE // competing consumers
                permission = EndpointProperties.PERMISSION_CONSUME
                maxMsgRedelivery = 1 // in-app retry already happened; one broker redelivery as a backstop
            }
            val queue = JCSMPFactory.onlyInstance().createQueue(SolaceDestinations.NOTIFICATION_QUEUE)
            session.provision(queue, queueProps, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS.toLong())

            // addSubscription(Endpoint, Subscription, int) only accepts 0 or
            // WAIT_FOR_CONFIRM (verified against the JCSMP bytecode) —
            // FLAG_IGNORE_ALREADY_EXISTS is only valid for provision(), not here,
            // and OR-ing it in throws "Invalid subscribe flags". Unlike
            // provision(), the broker does NOT silently no-op a duplicate
            // subscription — it throws JCSMPErrorResponseException with subcode
            // SUBSCRIPTION_ALREADY_PRESENT (verified against a real broker on
            // container restart), so idempotency is handled in
            // addSubscriptionIdempotent() below by catching that subcode.
            addSubscriptionIdempotent(session, queue, SolaceDestinations.ORDER_EVENTS_TOPIC)
            addSubscriptionIdempotent(session, queue, SolaceDestinations.PAYMENT_EVENTS_TOPIC)

            val dmqProps = EndpointProperties().apply {
                accessType = EndpointProperties.ACCESSTYPE_NONEXCLUSIVE
                permission = EndpointProperties.PERMISSION_CONSUME
            }
            listOf(SolaceDestinations.ORDER_EVENTS_DMQ, SolaceDestinations.PAYMENT_EVENTS_DMQ).forEach { name ->
                session.provision(
                    JCSMPFactory.onlyInstance().createQueue(name),
                    dmqProps,
                    JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS.toLong(),
                )
            }

            log.info(
                "Solace provisioning complete: queue={} (topics=[{}, {}]), dmqs=[{}, {}]",
                SolaceDestinations.NOTIFICATION_QUEUE,
                SolaceDestinations.ORDER_EVENTS_TOPIC, SolaceDestinations.PAYMENT_EVENTS_TOPIC,
                SolaceDestinations.ORDER_EVENTS_DMQ, SolaceDestinations.PAYMENT_EVENTS_DMQ,
            )
        } catch (e: Exception) {
            log.error("Solace queue/DMQ self-provisioning failed — check broker connectivity/permissions", e)
        } finally {
            session?.closeSession()
        }
    }

    private fun addSubscriptionIdempotent(session: JCSMPSession, queue: com.solacesystems.jcsmp.Queue, topicName: String) {
        try {
            session.addSubscription(queue, JCSMPFactory.onlyInstance().createTopic(topicName), JCSMPSession.WAIT_FOR_CONFIRM)
        } catch (e: com.solacesystems.jcsmp.JCSMPErrorResponseException) {
            if (e.subcode != com.solacesystems.jcsmp.JCSMPErrorResponseSubcode.SUBSCRIPTION_ALREADY_PRESENT) throw e
        }
    }
}
