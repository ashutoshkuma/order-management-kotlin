package com.example.ordermanagement.infrastructure.temporal.workflow.model

data class WorkflowDefinition(
    val name: String,
    val steps: List<WorkflowStep>,
)
