package io.github.saadfarooq.skientist

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant

// ── DSL entry point ────────────────────────────────────

/**
 * DSL: `experiment<T>("name") { control { ... }; candidate { ... } }`
 * Runs immediately and returns the control value.
 */
suspend fun <T> experiment(
    name: String,
    candidateDispatcher: CoroutineDispatcher = Dispatchers.Default,
    block: ExperimentScope<T>.() -> Unit,
): T {
    val scope = ExperimentScope<T>().apply {
        this.name = name
        block()
    }
    return runExperiment(scope, candidateDispatcher)
}

// ── Chain entry point ──────────────────────────────────

/**
 * Chain: `experiment<T>("name").enabled{}.control{}.candidate{}.run()`
 * Returns a builder. Call [run] to execute.
 */
fun <T> experiment(
    name: String,
    candidateDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ExperimentScope<T> = ExperimentScope<T>().apply { this.name = name }

/**
 * Execute a chained experiment.
 */
suspend fun <T> ExperimentScope<T>.run(
    candidateDispatcher: CoroutineDispatcher = Dispatchers.Default,
): T = runExperiment(this, candidateDispatcher)

// ── Internal runner ────────────────────────────────────

internal suspend fun <T> runExperiment(
    scope: ExperimentScope<T>,
    candidateDispatcher: CoroutineDispatcher,
): T = coroutineScope {
    val controlBlock = scope.controlBlock
        ?: throw IllegalArgumentException("control {} is required for experiment '${scope.name}'")
    val candidateBlock = scope.candidateBlock

    if (candidateBlock == null || !scope.enabledFn()) {
        val cpuStart = threadCpuTimeNs()
        val wallStart = System.nanoTime()
        val value = controlBlock()
        val controlObs = Observation(
            name = "control",
            value = value,
            error = null,
            durationNs = System.nanoTime() - wallStart,
            cpuDurationNs = cpuStart?.let { s -> threadCpuTimeNs()?.let { it - s } },
            isControl = true,
        )
        scope.publishFn(
            ExperimentResult(
                experimentName = scope.name,
                methodName = scope.methodName,
                control = controlObs,
                candidate = null,
                matched = true,
                context = scope.context.toMap(),
                timestamp = Instant.now(),
            )
        )
        return@coroutineScope value
    }

    // ponytail: control on caller's dispatcher, candidate on candidateDispatcher
    val controlDeferred = async { runObservation("control", controlBlock, isControl = true) }
    val candidateDeferred = async(candidateDispatcher) { runObservation("candidate", candidateBlock, isControl = false) }

    val controlObs = controlDeferred.await()
    val candidateObs = candidateDeferred.await()

    val matched = candidateObs.error == null &&
        scope.compareFn(controlObs.value as T, candidateObs.value as T)

    scope.publishFn(
        ExperimentResult(
            experimentName = scope.name,
            methodName = scope.methodName,
            control = controlObs,
            candidate = candidateObs,
            matched = matched,
            context = scope.context.toMap(),
            timestamp = Instant.now(),
        )
    )

    controlObs.error?.let { throw it }

    @Suppress("UNCHECKED_CAST")
    controlObs.value as T
}

// ── Observation helper ─────────────────────────────────

private suspend fun <T> runObservation(
    name: String,
    block: suspend () -> T,
    isControl: Boolean,
): Observation<T> {
    val cpuStart = threadCpuTimeNs()
    val wallStart = System.nanoTime()
    return try {
        val value = block()
        Observation(
            name = name,
            value = value,
            error = null,
            durationNs = System.nanoTime() - wallStart,
            cpuDurationNs = cpuStart?.let { s -> threadCpuTimeNs()?.let { it - s } },
            isControl = isControl,
        )
    } catch (e: Throwable) {
        Observation(
            name = name,
            value = null,
            error = e,
            durationNs = System.nanoTime() - wallStart,
            cpuDurationNs = cpuStart?.let { s -> threadCpuTimeNs()?.let { it - s } },
            isControl = isControl,
        )
    }
}
