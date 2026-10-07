package com.example.ordermanagement.infrastructure.temporal.worker

import com.example.ordermanagement.infrastructure.temporal.WorkflowPortAdapter
import com.example.ordermanagement.infrastructure.temporal.activity.InventoryActivityImpl
import com.example.ordermanagement.infrastructure.temporal.activity.NotificationActivityImpl
import com.example.ordermanagement.infrastructure.temporal.activity.PaymentActivityImpl
import com.example.ordermanagement.infrastructure.temporal.activity.ShippingActivityImpl
import com.example.ordermanagement.infrastructure.temporal.workflow.OrderFulfillmentWorkflowImpl
import io.temporal.worker.WorkerFactory
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Component: TemporalWorkerSetup
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHAT IS A TEMPORAL WORKER?
 * ═══════════════════════════════════════════════════════════════════
 * A Worker is a process that:
 *   1. Polls the Temporal task queue for workflow tasks and activity tasks
 *   2. Executes workflow code (deterministic replay + new steps)
 *   3. Executes activity code (actual side effects)
 *   4. Reports results back to Temporal server
 *
 * Multiple worker instances can poll the same task queue.
 * Temporal routes tasks to available workers (load balancing).
 *
 * ═══════════════════════════════════════════════════════════════════
 * WORKER CRASH RECOVERY DEMONSTRATION
 * ═══════════════════════════════════════════════════════════════════
 * When a worker crashes mid-activity:
 *   1. Temporal marks the activity task as scheduled (not completed)
 *   2. After heartbeat timeout, Temporal retries the activity
 *   3. The retry goes to ANY available worker
 *   4. If no workers available, task stays in queue
 *   5. When app restarts, worker picks up the pending task
 *   6. Workflow continues from where it left off
 *
 * This is why Temporal is superior to simple queues for long-running processes.
 *
 * ═══════════════════════════════════════════════════════════════════
 * WHY REGISTER WORKFLOWS AND ACTIVITIES SEPARATELY?
 * ═══════════════════════════════════════════════════════════════════
 * Workers can be split into dedicated workflow workers and activity workers.
 * In production:
 *   - Workflow workers: CPU-light (deterministic replay), many instances
 *   - Activity workers: CPU/IO heavy (HTTP calls, DB), scale independently
 *
 * Here we use one combined worker for simplicity.
 */
@Component
class TemporalWorkerSetup(
    private val workerFactory: WorkerFactory,
    private val inventoryActivity: InventoryActivityImpl,
    private val paymentActivity: PaymentActivityImpl,
    private val shippingActivity: ShippingActivityImpl,
    private val notificationActivity: NotificationActivityImpl,
) {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(TemporalWorkerSetup::class.java)
    }

    @PostConstruct
    fun createWorker() {
        log.info("Registering Temporal worker on task queue: {}", WorkflowPortAdapter.TASK_QUEUE)

        val worker = workerFactory.newWorker(WorkflowPortAdapter.TASK_QUEUE)

        // Register workflow implementations
        // Temporal instantiates a new WorkflowImpl per workflow execution
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl::class.java)

        // Register activity implementations (Spring singletons)
        worker.registerActivitiesImplementations(
            inventoryActivity,
            paymentActivity,
            shippingActivity,
            notificationActivity
        )

        // Start all workers (begins polling for tasks)
        workerFactory.start()

        log.info("Temporal worker started — polling for workflow and activity tasks")
    }
}
