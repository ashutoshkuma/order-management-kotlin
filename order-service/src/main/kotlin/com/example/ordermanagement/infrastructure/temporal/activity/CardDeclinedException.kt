package com.example.ordermanagement.infrastructure.temporal.activity

/**
 * Non-retryable exception: card was declined by the issuing bank.
 * Temporal will NOT retry activities that throw this.
 */
class CardDeclinedException(message: String) : RuntimeException(message) {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CardDeclinedException::class.java)
    }
}
