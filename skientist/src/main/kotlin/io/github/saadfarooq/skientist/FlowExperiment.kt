package io.github.saadfarooq.skientist

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

// ── Types ──────────────────────────────────────────────

data class FlowExperimentResult<T>(
    val experimentName: String,
    val methodName: String,
    val itemCount: Int,
    val matched: Boolean,
    val controlSnapshot: List<T>,
    val candidateSnapshot: List<T>?,
    val candidateError: Throwable?,
    val windowDurationMs: Long,
    val timestamp: Instant,
)

class FlowExperimentScope<T> {
    internal var name: String = ""
    internal var methodName: String = ""
    internal var controlSource: (() -> Flow<T>)? = null
    internal var candidateSource: (() -> Flow<T>)? = null
    internal var enabledFn: () -> Boolean = { true }
    internal var compareFn: (T, T) -> Boolean = { a, b -> a == b }
    internal var publishFn: (FlowExperimentResult<T>) -> Unit = {}
    internal var windowSize: Int = 100
    internal var windowTimeout: Duration = Duration.parse("5m")
}

fun <T> FlowExperimentScope<T>.control(block: () -> Flow<T>): FlowExperimentScope<T> {
    controlSource = block; return this
}
fun <T> FlowExperimentScope<T>.candidate(block: () -> Flow<T>): FlowExperimentScope<T> {
    candidateSource = block; return this
}
fun <T> FlowExperimentScope<T>.enabled(block: () -> Boolean): FlowExperimentScope<T> {
    enabledFn = block; return this
}
fun <T> FlowExperimentScope<T>.compareWith(block: (T, T) -> Boolean): FlowExperimentScope<T> {
    compareFn = block; return this
}
fun <T> FlowExperimentScope<T>.publish(block: (FlowExperimentResult<T>) -> Unit): FlowExperimentScope<T> {
    publishFn = block; return this
}
fun <T> FlowExperimentScope<T>.window(size: Int, timeout: Duration): FlowExperimentScope<T> {
    windowSize = size; windowTimeout = timeout; return this
}
fun <T> FlowExperimentScope<T>.methodName(name: String): FlowExperimentScope<T> {
    methodName = name; return this
}
fun <T> FlowExperimentScope<T>.window(size: Int, timeoutMs: Long): FlowExperimentScope<T> {
    windowSize = size; windowTimeout = timeoutMs.toDuration(DurationUnit.MILLISECONDS); return this
}

// ── DSL entry ─────────────────────────────────────────

fun <T> experimentFlow(
    name: String,
    block: FlowExperimentScope<T>.() -> Unit,
): Flow<T> {
    val config = FlowExperimentScope<T>().apply {
        this.name = name
        block()
    }
    return experimentFlowRun(config, Dispatchers.Default)
}

// ── Chain entry ───────────────────────────────────────

fun <T> experimentFlow(name: String): FlowExperimentScope<T> =
    FlowExperimentScope<T>().apply { this.name = name }

fun <T> FlowExperimentScope<T>.run(
    candidateDispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<T> = experimentFlowRun(this, candidateDispatcher)

// ── Internal runner ───────────────────────────────────

internal fun <T> experimentFlowRun(
    config: FlowExperimentScope<T>,
    candidateDispatcher: CoroutineDispatcher,
): Flow<T> = kotlinx.coroutines.flow.flow {
    coroutineScope {
        val controlSource = config.controlSource
            ?: throw IllegalArgumentException("control {} is required for '${config.name}'")
        val candidateSource = config.candidateSource
            ?: throw IllegalArgumentException("candidate {} is required for '${config.name}'")

        // ponytail: collect candidate into buffer on background dispatcher,
        // drain per window. Works for hot and cold.
        val candidateBuffer = mutableListOf<T>()
        var candidateError: Throwable? = null
        val candidateJob = if (config.enabledFn()) {
            launch(candidateDispatcher) {
                try {
                    candidateSource().collect { candidateBuffer += it }
                } catch (e: Throwable) {
                    candidateError = e
                }
            }
        } else null

        val window = mutableListOf<T>()

        suspend fun flushWindow() {
            if (window.isEmpty() || candidateJob == null) return
            val snapshot: List<T> = window.toList()
            window.clear()
            val count = snapshot.size

            val start = System.nanoTime()

            // Take next N from candidate buffer
            val candidateSnapshot: List<T> =
                if (candidateBuffer.size >= count) {
                    val taken = candidateBuffer.take(count)
                    repeat(count) { candidateBuffer.removeFirst() }
                    taken
                } else {
                    val taken = candidateBuffer.toList()
                    candidateBuffer.clear()
                    taken
                }

            val matched: Boolean = candidateError == null
                && snapshot.size == candidateSnapshot.size
                && snapshot.zip(candidateSnapshot).all { (c, d) -> config.compareFn(c, d) }

            config.publishFn(
                FlowExperimentResult(
                    experimentName = config.name,
                    methodName = config.methodName,
                    itemCount = count,
                    matched = matched,
                    controlSnapshot = snapshot,
                    candidateSnapshot = candidateSnapshot,
                    candidateError = candidateError,
                    windowDurationMs = (System.nanoTime() - start) / 1_000_000,
                    timestamp = Instant.now(),
                )
            )
        }

        controlSource().collect { value: T ->
            emit(value)
            window += value

            if (window.size >= config.windowSize) {
                flushWindow()
            }
        }

        flushWindow()
        candidateJob?.cancel()
    }
}
