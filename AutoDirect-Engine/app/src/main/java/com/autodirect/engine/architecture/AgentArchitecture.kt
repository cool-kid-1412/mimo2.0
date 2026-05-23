package com.autodirect.engine.architecture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AgentState {
    IDLE,
    PLANNING,
    EXECUTING,
    REFLECTING,
    COMPLETED,
    FAILED
}

sealed class StateTransition {
    data class ToPlanning(val prompt: String) : StateTransition()
    data class ToExecuting(val plan: List<String>) : StateTransition()
    data class ToReflecting(val result: String) : StateTransition()
    data class ToCompleted(val output: String) : StateTransition()
    data class ToFailed(val error: Throwable) : StateTransition()
    object ToIdle : StateTransition()
}

data class MemoryEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val state: AgentState,
    val transition: StateTransition,
    val context: Map<String, Any> = emptyMap()
)

class HippocampusMemory(
    private val capacity: Int = 1024
) {
    private val mutex = Mutex()
    private val entries = mutableListOf<MemoryEntry>()
    private val indexByState = mutableMapOf<AgentState, MutableList<Int>>()

    suspend fun store(entry: MemoryEntry) {
        mutex.withLock {
            if (entries.size >= capacity) {
                val removed = entries.removeAt(0)
                indexByState[removed.state]?.remove(0)
            }
            val idx = entries.size
            entries.add(entry)
            indexByState.getOrPut(entry.state) { mutableListOf() }.add(idx)
        }
    }

    suspend fun recall(state: AgentState, limit: Int = 10): List<MemoryEntry> {
        return mutex.withLock {
            indexByState[state]
                ?.takeLast(limit)
                ?.map { entries[it] }
                ?: emptyList()
        }
    }

    suspend fun recallRecent(limit: Int = 10): List<MemoryEntry> {
        return mutex.withLock {
            entries.takeLast(limit)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            entries.clear()
            indexByState.clear()
        }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }
}

class AgentStateMachine(
    private val memory: HippocampusMemory,
    private val onTransition: suspend (AgentState, StateTransition) -> Unit = { _, _ -> }
) {
    private var currentState: AgentState = AgentState.IDLE
    private val mutex = Mutex()

    suspend fun currentState(): AgentState = mutex.withLock { currentState }

    suspend fun transition(transition: StateTransition): AgentState {
        mutex.withLock {
            val nextState = resolveNextState(currentState, transition)
            if (!isValidTransition(currentState, nextState)) {
                throw IllegalStateException(
                    "Invalid state transition: $currentState -> $nextState"
                )
            }
            currentState = nextState
        }
        memory.store(
            MemoryEntry(
                state = currentState,
                transition = transition
            )
        )
        onTransition(currentState, transition)
        return currentState
    }

    suspend fun reset() {
        mutex.withLock {
            currentState = AgentState.IDLE
        }
    }

    private fun resolveNextState(
        current: AgentState,
        transition: StateTransition
    ): AgentState = when (transition) {
        is StateTransition.ToPlanning -> AgentState.PLANNING
        is StateTransition.ToExecuting -> AgentState.EXECUTING
        is StateTransition.ToReflecting -> AgentState.REFLECTING
        is StateTransition.ToCompleted -> AgentState.COMPLETED
        is StateTransition.ToFailed -> AgentState.FAILED
        is StateTransition.ToIdle -> AgentState.IDLE
    }

    private fun isValidTransition(from: AgentState, to: AgentState): Boolean {
        if (from == to) return true
        return when (from) {
            AgentState.IDLE -> to == AgentState.PLANNING
            AgentState.PLANNING -> to in setOf(
                AgentState.EXECUTING,
                AgentState.FAILED,
                AgentState.IDLE
            )
            AgentState.EXECUTING -> to in setOf(
                AgentState.REFLECTING,
                AgentState.COMPLETED,
                AgentState.FAILED
            )
            AgentState.REFLECTING -> to in setOf(
                AgentState.PLANNING,
                AgentState.EXECUTING,
                AgentState.COMPLETED,
                AgentState.FAILED
            )
            AgentState.COMPLETED -> to == AgentState.IDLE
            AgentState.FAILED -> to == AgentState.IDLE
        }
    }
}
