package com.example.notification.solace

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jms.core.JmsTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Publishes messages that exhausted in-app retries (or could not even be parsed) to
 * their topic's Dead Message Queue.
 *
 * WHY THIS IS DONE EXPLICITLY AT THE APPLICATION LAYER:
 * Solace's native dead-message routing targets a single, VPN-wide system DMQ
 * (`#DEAD_MSG_QUEUE`) — there is no broker primitive for a custom, per-topic DMQ name
 * reachable from the JCSMP/JMS client APIs (that level of routing is a SEMPv2 broker
 * admin concern). architecture.md's "one DMQ per topic" is therefore implemented here:
 * on exhaustion, the consumer hands the message to this publisher instead of
 * rethrowing, so the original queue message is still acknowledged normally and lands
 * on a queue we name and provision ourselves (see MessagingConfig). The main queue's
 * maxMsgRedelivery=1 (also set in MessagingConfig) remains as a broker-level backstop
 * for the case where a consumer instance crashes mid-processing before this hand-off
 * runs — those messages fall through to Solace's system DMQ instead, which is an
 * accepted gap for this PoC.
 */
@Component
class DlqPublisher(
    private val jmsTemplate: JmsTemplate,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(DlqPublisher::class.java)
    }

    /** In-app retries exhausted on a message that parsed successfully. */
    fun publish(dmqName: String, originalTopic: String, eventId: UUID, payload: Any, error: Throwable) {
        publishText(dmqName, originalTopic, eventId.toString(), objectMapper.writeValueAsString(payload), error)
    }

    /**
     * The message body could not even be parsed into a known contract type — the
     * Solace-era analog of Kafka's DeserializationException, which skipped retries
     * entirely and went straight to the DLT.
     */
    fun publishRaw(dmqName: String, originalTopic: String, rawBody: String, error: Throwable) {
        publishText(dmqName, originalTopic, "n/a", rawBody, error)
    }

    private fun publishText(dmqName: String, originalTopic: String, eventId: String, body: String, error: Throwable) {
        try {
            jmsTemplate.send(dmqName) { session ->
                session.createTextMessage(body).apply {
                    setStringProperty("dmqOriginalTopic", originalTopic)
                    setStringProperty("dmqExceptionClass", error.javaClass.name)
                    setStringProperty("dmqExceptionMessage", (error.message ?: "n/a").take(500))
                }
            }
        } catch (e: Exception) {
            log.error(
                "Failed to publish exhausted/unparseable message (eventId={}) to {} — message dropped",
                eventId, dmqName, e,
            )
        }
    }
}
