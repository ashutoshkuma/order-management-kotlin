package com.example.ordermanagement.api.controller

import com.example.ordermanagement.domain.exception.DomainException
import com.example.ordermanagement.domain.exception.InvalidStateTransitionException
import com.example.ordermanagement.domain.exception.OptimisticLockingException
import com.example.ordermanagement.domain.exception.OrderNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

/**
 * Global Exception Handler
 *
 * Translates domain exceptions to RFC 7807 Problem Details responses.
 * This keeps error handling centralized and out of controllers.
 *
 * Uses Spring's ProblemDetail (Spring 6+) for standardized error format.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(OrderNotFoundException::class)
    fun handleNotFound(ex: OrderNotFoundException): ResponseEntity<ProblemDetail> {
        log.warn("Order not found: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message)
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem)
    }

    @ExceptionHandler(InvalidStateTransitionException::class)
    fun handleInvalidTransition(ex: InvalidStateTransitionException): ResponseEntity<ProblemDetail> {
        log.warn("Invalid state transition: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.message)
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }

    @ExceptionHandler(OptimisticLockingException::class)
    fun handleOptimisticLocking(ex: OptimisticLockingException): ResponseEntity<ProblemDetail> {
        log.warn("Optimistic locking conflict: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Concurrent modification detected. Please retry."
        )
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<ProblemDetail> {
        log.warn("Domain rule violation: {}", ex.message)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message)
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val details = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        log.warn("Validation failed: {}", details)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details)
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unexpected error: {}", ex.message, ex)
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "${ex.javaClass.simpleName}: ${ex.message}"
        )
        problem.setProperty("timestamp", Instant.now())
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }
}
