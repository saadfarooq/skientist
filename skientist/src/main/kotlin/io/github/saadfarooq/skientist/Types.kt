package io.github.saadfarooq.skientist

import java.lang.management.ManagementFactory
import java.time.Instant

// ── Types ──────────────────────────────────────────────

data class Observation<T>(
    val name: String,
    val value: T?,
    val error: Throwable?,
    val durationNs: Long,
    val cpuDurationNs: Long?,
    val isControl: Boolean,
)

data class ExperimentResult<T>(
    val experimentName: String,
    val methodName: String,
    val control: Observation<T>,
    val candidate: Observation<T>?,
    val matched: Boolean,
    val context: Map<String, Any?>,
    val timestamp: Instant,
)

fun interface Publisher {
    fun publish(result: ExperimentResult<*>)
}

// ── Config block (for KSP) ────────────────────────────

interface ExperimentConfigBlock<T> {
    fun enabled(): Boolean = true
    fun compareWith(a: @UnsafeVariance T, b: @UnsafeVariance T): Boolean = a == b
    fun publish(result: ExperimentResult<*>) {}
}

// ── Scope ──────────────────────────────────────────────

class ExperimentScope<T> {
    internal var name: String = ""
    internal var methodName: String = ""
    internal var enabledFn: () -> Boolean = { true }
    internal var compareFn: (T, T) -> Boolean = { a, b -> a == b }
    internal var publishFn: (ExperimentResult<T>) -> Unit = {}
    val context = mutableMapOf<String, Any?>()

    internal var controlBlock: (suspend () -> T)? = null
    internal var candidateBlock: (suspend () -> T)? = null
}

// ponytail: all setters return this — works as DSL inside lambda, enables chaining.

fun <T> ExperimentScope<T>.enabled(block: () -> Boolean): ExperimentScope<T> {
    enabledFn = block; return this
}
fun <T> ExperimentScope<T>.compareWith(block: (T, T) -> Boolean): ExperimentScope<T> {
    compareFn = block; return this
}
fun <T> ExperimentScope<T>.publish(block: (ExperimentResult<T>) -> Unit): ExperimentScope<T> {
    publishFn = block; return this
}
fun <T> ExperimentScope<T>.control(block: suspend () -> T): ExperimentScope<T> {
    controlBlock = block; return this
}
fun <T> ExperimentScope<T>.candidate(block: suspend () -> T): ExperimentScope<T> {
    candidateBlock = block; return this
}
fun <T> ExperimentScope<T>.context(key: String, value: Any?): ExperimentScope<T> {
    context[key] = value; return this
}
fun <T> ExperimentScope<T>.methodName(name: String): ExperimentScope<T> {
    methodName = name; return this
}

// ── Internal helpers ───────────────────────────────────

// ponytail: ThreadMXBean for CPU time. null on platforms that don't support it.
internal fun threadCpuTimeNs(): Long? {
    val bean = ManagementFactory.getThreadMXBean()
    return if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime else null
}
