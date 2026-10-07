package com.example.ordermanagement.domain.exception

/**
 * Thrown when two concurrent requests try to modify the same aggregate simultaneously.
 *
 * HOW IT WORKS:
 *   - Request A loads Order at version 5
 *   - Request B loads Order at version 5 simultaneously
 *   - Request A saves events at version 6 — succeeds
 *   - Request B tries to save events expecting version 5, finds version 6 — throws this
 *
 * The caller should retry the command after re-loading the aggregate.
 * Maps to HTTP 409 Conflict.
 */
class OptimisticLockingException(message: String) : DomainException(message)
