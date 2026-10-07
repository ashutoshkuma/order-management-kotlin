package com.example.ordermanagement.infrastructure.temporal.workflow.model

/**
 * Converted from a Java record with a compact constructor that defaulted
 * null timeoutSeconds/maxAttempts to 30/3. Kotlin default parameter values
 * reproduce that behaviour both for direct construction and for Jackson
 * deserialization (missing YAML keys fall back to the defaults below).
 */
data class WorkflowStep(
    val name: String,
    val activityName: String? = null,
    val methodName: String? = null,
    val compensationActivity: String? = null,
    val compensationMethod: String? = null,
    val compensationStep: String? = null,
    val timeoutSeconds: Int = 30,
    val maxAttempts: Int = 3,
)
