package com.example.ordermanagement.infrastructure.temporal.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Activity Interface: NotificationActivity
 *
 * Sends notifications to customers at various workflow stages.
 * Notifications are best-effort — failures don't abort the workflow.
 */
@ActivityInterface
interface NotificationActivity {

    @ActivityMethod
    fun sendOrderConfirmedNotification(orderId: String)

    @ActivityMethod
    fun sendOrderShippedNotification(orderId: String, trackingNumber: String)

    @ActivityMethod
    fun sendOrderDeliveredNotification(orderId: String)

    @ActivityMethod
    fun sendOrderCancelledNotification(orderId: String, reason: String)

    @ActivityMethod
    fun sendPaymentFailedNotification(orderId: String, reason: String)
}
