# AutoDirect-Engine

**Multi-Agent Driven 3D Short-Drama Generation Engine — Core Module**

AutoDirect-Engine is the core runtime engine for AI-driven 3D short-drama production. It orchestrates multiple intelligent agents through a Chain of Thought (CoT) scheduling pipeline, leveraging Kotlin coroutines for concurrent execution and a finite state machine for agent lifecycle management. The engine bridges Android native code with Unity's rendering pipeline for real-time glTF/GLB model loading and scene composition.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   AutoDirect-Engine                      │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Architecture  │  │    Core      │  │    Bridge     │  │
│  │              │  │              │  │              │  │
│  │ Hippocampus  │  │ Orchestrator │  │ UnityRender  │  │
│  │ Memory       │──│ (Coroutine   │──│ Bridge       │  │
│  │              │  │  Scheduler)  │  │              │  │
│  │ AgentState   │  │              │  │ glTF/GLB     │  │
│  │ StateMachine │  │ CoT Pipeline │  │ Loading      │  │
│  └──────────────┘  └──────────────┘  └──────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## Core Modules

### 1. Architecture — `engine.architecture`

The foundational layer defines the cognitive memory model and agent state machine:

- **HippocampusMemory** — A thread-safe, capacity-bounded episodic memory store inspired by the hippocampal function in neuroscience. It indexes `MemoryEntry` records by `AgentState`, enabling efficient recall of historical transitions. Concurrency safety is guaranteed by Kotlin's `Mutex`.

- **AgentStateMachine** — A finite state machine governing the lifecycle of each agent through six states: `IDLE → PLANNING → EXECUTING → REFLECTING → COMPLETED/FAILED → IDLE`. Invalid transitions are rejected at runtime, ensuring deterministic behavior. Every state change is persisted to `HippocampusMemory` for auditability.

### 2. Core — `engine.core`

The scheduling layer implements multi-agent Chain of Thought orchestration:

- **AutoDirectOrchestrator** — Accepts a list of `AgentTask` objects with dependency declarations, builds a DAG, and performs topological sort to determine parallelizable execution batches. Each batch runs concurrently via Kotlin coroutines (`async`/`awaitAll`). Agents whose confidence falls below a threshold (default 0.7) automatically trigger a reflection step for self-correction.

- **AgentExecutor** — A pluggable interface that each domain-specific agent implements. The orchestrator calls `execute(prompt, context)` with accumulated thought chains from upstream agents, enabling progressive reasoning.

- **OrchestrationState** — Exposed as a Kotlin `StateFlow` for reactive UI observation: `Idle → Running → Completed/Failed`.

### 3. Bridge — `engine.bridge`

The rendering bridge connects the Android native layer to Unity:

- **UnityRenderBridge** — A singleton that wraps `UnityPlayer.UnitySendMessage` to dispatch JSON-encoded commands for glTF/GLB model loading, animation playback, camera transforms, and lighting configuration. Commands are queued when Unity is not yet attached and flushed automatically upon `attachUnityPlayer()`.

---

## Key Technical Decisions

| Decision | Rationale |
|---|---|
| **Kotlin Coroutines** over thread pools | Structured concurrency with `SupervisorJob` provides automatic cancellation propagation and exception isolation across agent tasks |
| **State Machine** for agent lifecycle | Eliminates race conditions in state transitions; invalid transitions fail fast with descriptive errors |
| **HippocampusMemory** with state indexing | O(1) recall by state enables efficient post-hoc analysis of agent behavior patterns |
| **Topological sort** for task scheduling | Maximizes parallelism while respecting dependency constraints — critical for multi-agent CoT pipelines |
| **Confidence-based reflection** | Agents with low confidence (< 0.7) self-trigger a reflection pass, improving output quality without manual intervention |
| **Command queue** in Unity bridge | Decouples engine computation from rendering availability; commands are never lost |

---

## Statistical Data Validation

AutoDirect-Engine incorporates **statistics-based validation** throughout the pipeline:

- **Confidence Scoring**: Each `ThoughtStep` carries a `confidence` value (0.0–1.0). The orchestrator uses this as a threshold trigger for reflection loops.
- **Latency Tracking**: `OrchestratorResult.totalLatencyMs` records end-to-end execution time per task, enabling statistical analysis of scheduling efficiency.
- **Memory Recall Analytics**: `HippocampusMemory.recall(state)` enables aggregation of transition frequencies, state dwell times, and failure rate computation across runs.
- **Hypothesis Testing Framework**: The architecture supports plugging in statistical tests (e.g., chi-squared for state transition distribution, t-test for latency regression detection) to validate that engine modifications do not degrade performance.

---

## Project Structure

```
AutoDirect-Engine/
├── app/src/main/java/com/autodirect/engine/
│   ├── architecture/
│   │   └── AgentArchitecture.kt      # HippocampusMemory & AgentStateMachine
│   ├── core/
│   │   └── AutoDirectOrchestrator.kt # Multi-agent CoT coroutine scheduler
│   └── bridge/
│       └── UnityRenderBridge.kt      # Android-Unity glTF/GLB bridge
└── README.md
```

---

## Getting Started

### Prerequisites

- Kotlin 1.9+
- Android SDK (API 24+)
- Unity 2022.3+ (with Android build target)
- Kotlin Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-core`)

### Quick Start

```kotlin
val memory = HippocampusMemory()
val orchestrator = AutoDirectOrchestrator(memory)

orchestrator.registerExecutor(object : AgentExecutor {
    override val name = "scene-planner"
    override suspend fun execute(prompt: String, context: List<ThoughtStep>): ThoughtStep {
        // Your agent logic here
    }
})

val results = orchestrator.orchestrate(
    listOf(
        AgentTask(id = "t1", agentName = "scene-planner", prompt = "Generate a sunset scene")
    )
)
```

---

## License

This project is licensed under the Apache License 2.0.
