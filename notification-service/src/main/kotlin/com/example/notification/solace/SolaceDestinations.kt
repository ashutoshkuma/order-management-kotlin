package com.example.notification.solace

/**
 * Well-known Solace destination names for notification-service.
 *
 * Queue name follows the {service-name}-group convention (unchanged from the Kafka
 * consumer-group naming). Topic names are unchanged from the Kafka topic naming.
 * DMQ names follow {topic}.dmq — one DMQ per topic, per architecture.md.
 */
object SolaceDestinations {
    const val NOTIFICATION_QUEUE = "notification-service-group"

    const val ORDER_EVENTS_TOPIC = "order.events"
    const val PAYMENT_EVENTS_TOPIC = "payment.events"

    const val ORDER_EVENTS_DMQ = "order.events.dmq"
    const val PAYMENT_EVENTS_DMQ = "payment.events.dmq"
}
