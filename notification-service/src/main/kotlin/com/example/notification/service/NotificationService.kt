package com.example.notification.service

import com.example.contracts.messaging.OrderEventMessage
import com.example.contracts.messaging.PaymentEventMessage
import org.springframework.stereotype.Service

/**
 * NotificationService — core business logic for sending notifications.
 *
 * In reality, this would call:
 *   - Email service (AWS SES, SendGrid, Mailgun)
 *   - SMS service (Twilio, AWS SNS)
 *   - Push notification service
 *   - In-app notification database
 *
 * For this PoC: simulates sending by logging.
 * Idempotency is handled by the Solace consumers (deduplication by eventId).
 */
@Service
class NotificationService {

    /**
     * Send welcome email when order is created.
     */
    fun notifyOrderCreated(event: OrderEventMessage.OrderCreatedMessage) {
        sendEmail(
            event.orderId,
            "Order Created",
            """
            Welcome! Your order has been created.
            Order ID: ${event.orderId}
            Shipping Address: ${event.shippingAddress}
            """.trimIndent(),
        )
    }

    /**
     * Send confirmation email when order is confirmed and workflow starts.
     */
    fun notifyOrderConfirmed(event: OrderEventMessage.OrderConfirmedMessage) {
        sendEmail(
            event.orderId,
            "Order Confirmed",
            """
            Your order has been confirmed!
            Order ID: ${event.orderId}
            Total: $${"%.2f".format(event.totalAmount)}
            Workflow ID: ${event.workflowId}
            Your items will be processed shortly.
            """.trimIndent(),
        )
    }

    /**
     * Send cancellation email when order is cancelled.
     */
    fun notifyOrderCancelled(event: OrderEventMessage.OrderCancelledMessage) {
        sendEmail(
            event.orderId,
            "Order Cancelled",
            """
            Your order has been cancelled.
            Order ID: ${event.orderId}
            Reason: ${event.reason}
            Cancelled By: ${event.cancelledBy}
            """.trimIndent(),
        )
    }

    /**
     * Send receipt email when payment completes.
     */
    fun notifyPaymentCompleted(event: OrderEventMessage.PaymentCompletedMessage) {
        sendEmail(
            event.orderId,
            "Payment Receipt",
            """
            Payment confirmed!
            Order ID: ${event.orderId}
            Amount: $${"%.2f".format(event.amount)}
            Transaction ID: ${event.transactionId}
            """.trimIndent(),
        )
    }

    /**
     * Send alert email when payment fails.
     */
    fun notifyPaymentFailed(event: OrderEventMessage.PaymentFailedMessage) {
        sendEmail(
            event.orderId,
            "Payment Failed - Action Required",
            """
            We were unable to process your payment.
            Order ID: ${event.orderId}
            Reason: ${event.reason}
            Retryable: ${event.retryable}

            Please update your payment method.
            """.trimIndent(),
        )
    }

    /**
     * Send tracking email when shipment is created.
     */
    fun notifyShipmentCreated(event: OrderEventMessage.ShipmentCreatedMessage) {
        sendEmail(
            event.orderId,
            "Your Package is on the Way!",
            """
            Your order is being shipped!
            Order ID: ${event.orderId}
            Shipment ID: ${event.shipmentId}
            Tracking Number: ${event.trackingNumber}
            Carrier: ${event.carrier}

            Track your package: https://carrier.example.com/track?tracking=${event.trackingNumber}
            """.trimIndent(),
        )
    }

    /**
     * Send delivery confirmation email.
     */
    fun notifyShipmentDelivered(event: OrderEventMessage.ShipmentDeliveredMessage) {
        sendEmail(
            event.orderId,
            "Your Package Has Arrived!",
            """
            Your order has been delivered!
            Order ID: ${event.orderId}
            Shipment ID: ${event.shipmentId}
            Delivered At: ${event.deliveredAt}

            Thank you for your order. We hope you enjoy your purchase!
            """.trimIndent(),
        )
    }

    /**
     * Send receipt when payment-service confirms a charge (from payment.events).
     */
    fun notifyPaymentCharged(event: PaymentEventMessage.PaymentChargedMessage) {
        sendEmail(
            event.orderId,
            "Payment Confirmed",
            """
            Payment confirmed!
            Order ID: ${event.orderId}
            Amount: ${event.amount} ${event.currency}
            Transaction ID: ${event.transactionId}
            """.trimIndent(),
        )
    }

    /**
     * Send alert when a payment charge fails (from payment.events).
     */
    fun notifyPaymentChargeFailed(event: PaymentEventMessage.PaymentFailedMessage) {
        sendEmail(
            event.orderId,
            "Payment Failed - Action Required",
            """
            We were unable to process your payment.
            Order ID: ${event.orderId}
            Reason: ${event.reason}

            Please update your payment method or contact support.
            """.trimIndent(),
        )
    }

    /**
     * Send refund confirmation (from payment.events — not published on order.events).
     */
    fun notifyPaymentRefunded(event: PaymentEventMessage.PaymentRefundedMessage) {
        sendEmail(
            event.orderId,
            "Refund Processed",
            """
            Your refund has been processed.
            Order ID: ${event.orderId}
            Refund ID: ${event.refundTransactionId}
            Amount: ${event.amount}
            Original Transaction: ${event.originalTransactionId}

            Funds will appear in your account within 3-5 business days.
            """.trimIndent(),
        )
    }

    /**
     * Send SMS alert for order status changes (optional).
     */
    fun sendSms(orderId: String, phone: String, message: String) {
        // In reality: call Twilio API or AWS SNS
    }

    /**
     * Send email notification.
     * In reality: call SendGrid, AWS SES, or similar.
     */
    private fun sendEmail(orderId: String, subject: String, body: String) {
        // In reality: call email service API
        // emailClient.send(Email.to(customer.email).subject(subject).body(body))
    }

    /**
     * Persist notification to database for audit trail / resend support.
     */
    private fun persistNotification(orderId: String, type: String, recipient: String, message: String) {
        // In reality: save to notifications table
    }
}
