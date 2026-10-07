package com.example.ordermanagement.infrastructure.temporal.activity

/**
 * Non-retryable exception: payment failed due to insufficient funds.
 * Temporal will NOT retry activities that throw this.
 */
class InsufficientFundsException(message: String) : RuntimeException(message) {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(InsufficientFundsException::class.java)
    }
}
