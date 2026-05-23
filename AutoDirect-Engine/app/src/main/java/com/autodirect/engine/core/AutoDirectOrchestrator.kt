package com.autodirect.engine.core

import com.autodirect.engine.architecture.AgentState
import com.autodirect.engine.architecture.AgentStateMachine
import com.autodirect.engine.architecture.HippocampusMemory
import com.autodirect.engine.architecture.StateTransition
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

data class AgentTask(
    val id: String,
    val agentName: String,
    val prompt: String,
    val priority: Int = 0,
    val dependencies: Set<String> = emptySet()
)

data class ThoughtStep(
    val agentName: String,
    val reasoning: String,
    val output: String,
    val confidence: Double
)

data class OrchestratorResult(
    val taskId: String,
    val thoughtChain: List<ThoughtStep>,
    val finalOutput: String,
    val totalLatencyMs: Long
)

interface AgentExecutor {
    val name: String
    suspend fun execute(prompt: String, context: List<ThoughtStep>): ThoughtStep
}

class AutoDirectOrchestrator(
    private val memory: HippocampusMemory = HippocampusMemory(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxConcurrency: Int = 4
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executors = ConcurrentHashMap<String, AgentExecutor>()
    private val pendingTasks = ConcurrentHashMap<String, AgentTask>()
    private val completedResults = ConcurrentHashMap<String, OrchestratorResult>()
    private val taskChannel = Channel<AgentTask>(Channel.UNLIMITED)

    private val _orchestrationState = MutableStateFlow<OrchestrationState>(
        OrchestrationState.Idle
    )
    val orchestrationState: StateFlow<OrchestrationState> = _orchestrationState.asStateFlow()

    sealed class OrchestrationState {
        object Idle : OrchestrationState()
        data class Running(val activeTasks: Int) : OrchestrationState()
        data class Completed(val results: List<OrchestratorResult>) : OrchestrationState()
        data class Failed(val error: Throwable) : OrchestrationState()
    }

    fun registerExecutor(executor: AgentExecutor) {
        executors[executor.name] = executor
    }

    fun unregisterExecutor(name: String) {
        executors.remove(name)
    }

    suspend fun submitTask(task: AgentTask) {
        pendingTasks[task.id] = task
        taskChannel.send(task)
    }

    suspend fun orchestrate(tasks: List<AgentTask>): List<OrchestratorResult> {
        _orchestrationState.value = OrchestrationState.Running(tasks.size)

        val stateMachine = AgentStateMachine(memory) { state, transition ->
            memory.store(
                com.autodirect.engine.architecture.MemoryEntry(
                    state = state,
                    transition = transition,
                    context = mapOf("taskCount" to tasks.size)
                )
            )
        }

        stateMachine.transition(StateTransition.ToPlanning("orchestrating ${tasks.size} tasks"))

        val dependencyGraph = buildDependencyGraph(tasks)
        val executionOrder = topologicalSort(dependencyGraph)

        val results = mutableListOf<OrchestratorResult>()
        val thoughtContext = ConcurrentHashMap<String, List<ThoughtStep>>()

        for (batch in executionOrder) {
            val batchResults = batch.map { task ->
                scope.async {
                    executeWithChainOfThought(task, thoughtContext)
                }
            }.awaitAll()

            results.addAll(batchResults)
            batchResults.forEach { result ->
                completedResults[result.taskId] = result
                thoughtContext[result.taskId] = result.thoughtChain
            }
        }

        stateMachine.transition(StateTransition.ToCompleted("all tasks completed"))
        _orchestrationState.value = OrchestrationState.Completed(results)

        return results
    }

    private suspend fun executeWithChainOfThought(
        task: AgentTask,
        sharedContext: ConcurrentHashMap<String, List<ThoughtStep>>
    ): OrchestratorResult {
        val startTime = System.currentTimeMillis()
        val thoughtChain = mutableListOf<ThoughtStep>()
        val executor = executors[task.agentName]
            ?: throw IllegalArgumentException("No executor registered for agent: ${task.agentName}")

        val depContext = task.dependencies.flatMap { depId ->
            sharedContext[depId] ?: emptyList()
        }

        val step = executor.execute(task.prompt, depContext + thoughtChain)
        thoughtChain.add(step)

        if (step.confidence < 0.7) {
            val reflectionPrompt = "Reflect and refine: ${step.output}"
            val reflectionStep = executor.execute(reflectionPrompt, depContext + thoughtChain)
            thoughtChain.add(reflectionStep)
        }

        val latency = System.currentTimeMillis() - startTime
        pendingTasks.remove(task.id)

        return OrchestratorResult(
            taskId = task.id,
            thoughtChain = thoughtChain,
            finalOutput = thoughtChain.last().output,
            totalLatencyMs = latency
        )
    }

    private fun buildDependencyGraph(tasks: List<AgentTask>): Map<String, Set<String>> {
        return tasks.associate { task ->
            task.id to task.dependencies
        }
    }

    private fun topologicalSort(graph: Map<String, Set<String>>): List<List<String>> {
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        graph.keys.forEach { node ->
            inDegree[node] = 0
            adjacency[node] = mutableListOf()
        }

        graph.forEach { (node, deps) ->
            inDegree[node] = deps.count { graph.containsKey(it) }
            deps.forEach { dep ->
                if (graph.containsKey(dep)) {
                    adjacency[dep]?.add(node)
                }
            }
        }

        val batches = mutableListOf<List<String>>()
        val queue = ArrayDeque<String>()

        while (true) {
            queue.clear()
            inDegree.filter { it.value == 0 }.keys.forEach { queue.add(it) }

            if (queue.isEmpty()) break

            val batch = queue.toList()
            batches.add(batch)

            batch.forEach { node ->
                inDegree.remove(node)
                adjacency[node]?.forEach { neighbor ->
                    inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                }
            }
        }

        if (inDegree.isNotEmpty()) {
            throw IllegalStateException("Circular dependency detected in task graph")
        }

        return batches
    }

    fun shutdown() {
        taskChannel.close()
        scope.cancel()
    }
}
