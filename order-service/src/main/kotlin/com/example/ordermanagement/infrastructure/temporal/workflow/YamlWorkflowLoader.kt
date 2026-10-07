package com.example.ordermanagement.infrastructure.temporal.workflow

import com.example.ordermanagement.infrastructure.temporal.workflow.model.WorkflowDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class YamlWorkflowLoader {

    // Registers the Kotlin module so Jackson can construct the WorkflowDefinition/
    // WorkflowStep Kotlin data classes (primary-constructor binding, default
    // parameter values) when reading the YAML workflow definition. This is a
    // plain, local ObjectMapper unrelated to Temporal's DataConverter.
    private val mapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun loadWorkflow(resourcePath: String): WorkflowDefinition {
        try {
            ClassPathResource(resourcePath).inputStream.use { inputStream ->
                return mapper.readValue(inputStream, WorkflowDefinition::class.java)
            }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load workflow definition from YAML: $resourcePath", e)
        }
    }
}
