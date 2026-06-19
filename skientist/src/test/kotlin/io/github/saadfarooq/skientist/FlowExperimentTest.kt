@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.saadfarooq.skientist

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class FlowExperimentTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // ── Forwarding ─────────────────────────────────────

    @Test
    fun `forwards every control event to caller`() = runTest {
        experimentFlow<String>("test") {
            control { flow { emit("a"); emit("b"); emit("c") } }
            candidate { emptyFlow() }
            window(size = 10, timeout = 10.seconds)
        }.test {
            assertThat(awaitItem()).isEqualTo("a")
            assertThat(awaitItem()).isEqualTo("b")
            assertThat(awaitItem()).isEqualTo("c")
            awaitComplete()
        }
    }

    @Test
    fun `forwards from hot SharedFlow without losing events`() = runTest {
        val shared = MutableSharedFlow<String>(extraBufferCapacity = 8)

        experimentFlow<String>("test") {
            control { shared }
            candidate { emptyFlow() }
            window(size = 10, timeout = 10.seconds)
        }.test {
            backgroundScope.launch {
                shared.emit("x")
                shared.emit("y")
                shared.emit("z")
            }
            assertThat(awaitItem()).isEqualTo("x")
            assertThat(awaitItem()).isEqualTo("y")
            assertThat(awaitItem()).isEqualTo("z")
        }
    }

    // ── Windowing ──────────────────────────────────────

    @Test
    fun `publishes window when item count threshold reached`() = runTest {
        val published = mutableListOf<FlowExperimentResult<Int>>()

        experimentFlow<Int>("test")
            .control { flow { repeat(5) { emit(it) } } }
            .candidate { flow { repeat(5) { emit(it) } } }
            .window(size = 3, timeout = 10.seconds)
            .publish { published += it }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(5) { awaitItem() }
                awaitComplete()
            }

        assertThat(published).hasSize(2)
        assertThat(published[0].itemCount).isEqualTo(3)
        assertThat(published[0].matched).isTrue()
        assertThat(published[1].itemCount).isEqualTo(2)
        assertThat(published[1].matched).isTrue()
    }

    @Test
    fun `flushes remaining items on flow completion`() = runTest {
        val published = mutableListOf<FlowExperimentResult<Int>>()

        experimentFlow<Int>("test")
            .control { flow { emit(1) } }
            .candidate { flow { emit(1) } }
            .window(size = 10, timeout = 10.seconds)
            .publish { published += it }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                awaitItem()
                awaitComplete()
            }

        assertThat(published).hasSize(1)
        assertThat(published[0].itemCount).isEqualTo(1)
    }

    // ── Comparison ─────────────────────────────────────

    @Test
    fun `window matched=true when all items compare equal`() = runTest {
        var matched: Boolean? = null

        experimentFlow<Int>("test")
            .control { flow { emit(1); emit(2); emit(3) } }
            .candidate { flow { emit(1); emit(2); emit(3) } }
            .window(size = 3, timeout = 10.seconds)
            .publish { matched = it.matched }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(3) { awaitItem() }
                awaitComplete()
            }

        assertThat(matched).isTrue()
    }

    @Test
    fun `window matched=false when any item differs`() = runTest {
        var matched: Boolean? = null

        experimentFlow<Int>("test")
            .control { flow { emit(1); emit(2); emit(3) } }
            .candidate { flow { emit(1); emit(99); emit(3) } }
            .window(size = 3, timeout = 10.seconds)
            .publish { matched = it.matched }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(3) { awaitItem() }
                awaitComplete()
            }

        assertThat(matched).isFalse()
    }

    @Test
    fun `window matched=false when candidate produces fewer items`() = runTest {
        var matched: Boolean? = null

        experimentFlow<Int>("test")
            .control { flow { emit(1); emit(2) } }
            .candidate { flow { emit(1) } }
            .window(size = 2, timeout = 10.seconds)
            .publish { matched = it.matched }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(2) { awaitItem() }
                awaitComplete()
            }

        assertThat(matched).isFalse()
    }

    // ── Custom comparison ──────────────────────────────

    @Test
    fun `uses custom comparator`() = runTest {
        var matched: Boolean? = null

        experimentFlow<String>("test")
            .control { flow { emit("hello"); emit("hey") } }
            .candidate { flow { emit("world"); emit("you") } }
            .compareWith { a, b -> a.length == b.length }
            .window(size = 2, timeout = 10.seconds)
            .publish { matched = it.matched }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(2) { awaitItem() }
                awaitComplete()
            }

        assertThat(matched).isTrue()
    }

    // ── Candidate errors ───────────────────────────────

    @Test
    fun `control continues when candidate throws`() = runTest {
        var candidateError: Throwable? = null

        experimentFlow<Int>("test")
            .control { flow { emit(1); emit(2) } }
            .candidate { flow { throw RuntimeException("database down") } }
            .window(size = 1, timeout = 10.seconds)
            .publish { candidateError = it.candidateError }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                assertThat(awaitItem()).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(2)
                awaitComplete()
            }

        assertThat(candidateError).hasMessageThat().isEqualTo("database down")
    }

    // ── Enabled gating ─────────────────────────────────

    @Test
    fun `skips candidate and publish when enabled is false`() = runTest {
        var published = false

        experimentFlow<Int>("test") {
            enabled { false }
            control { flow { emit(1) } }
            candidate { flow { emit(99) } }
            window(size = 1, timeout = 10.seconds)
            publish { published = true }
        }.test {
            assertThat(awaitItem()).isEqualTo(1)
            awaitComplete()
        }

        assertThat(published).isFalse()
    }

    // ── Snapshot integrity ─────────────────────────────

    @Test
    fun `window contains complete control and candidate snapshots`() = runTest {
        var window: FlowExperimentResult<String>? = null

        experimentFlow<String>("test")
            .control { flow { emit("a"); emit("b") } }
            .candidate { flow { emit("a"); emit("b") } }
            .window(size = 2, timeout = 10.seconds)
            .publish { window = it }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                repeat(2) { awaitItem() }
                awaitComplete()
            }

        assertThat(window!!.experimentName).isEqualTo("test")
        assertThat(window!!.controlSnapshot).containsExactly("a", "b").inOrder()
        assertThat(window!!.candidateSnapshot).containsExactly("a", "b").inOrder()
        assertThat(window!!.windowDurationMs).isAtLeast(0)
    }

    // ── Chain + DSL equivalence ────────────────────────

    @Test
    fun `chain API with explicit run works same as DSL`() = runTest {
        // DSL
        var dslMatched: Boolean? = null
        experimentFlow<Int>("dsl-test") {
            control { flow { emit(1) } }
            candidate { flow { emit(1) } }
            window(size = 1, timeout = 10.seconds)
            publish { dslMatched = it.matched }
        }.test {
            awaitItem()
            awaitComplete()
        }

        // Chain
        var chainMatched: Boolean? = null
        experimentFlow<Int>("chain-test")
            .control { flow { emit(1) } }
            .candidate { flow { emit(1) } }
            .window(size = 1, timeout = 10.seconds)
            .publish { chainMatched = it.matched }
            .run(candidateDispatcher = UnconfinedTestDispatcher(testScheduler))
            .test {
                awaitItem()
                awaitComplete()
            }

        assertThat(dslMatched).isTrue()
        assertThat(chainMatched).isTrue()
    }

    // ── Temperature transparency ───────────────────────

    @Test
    fun `hot SharedFlow subscriber count is 1 via wrapper`() = runTest {
        val shared = MutableSharedFlow<String>(extraBufferCapacity = 8)

        experimentFlow<String>("test") {
            control { shared }
            candidate { emptyFlow() }
            window(size = 10, timeout = 10.seconds)
        }.test {
            backgroundScope.launch { shared.emit("ping") }
            awaitItem()

            assertThat(shared.subscriptionCount.value).isEqualTo(1)
        }
    }

    @Test
    fun `cold flow re-triggers producer per collector`() = runTest {
        var produceCount = 0
        val cold = flow<Int> { produceCount++; emit(1) }

        experimentFlow<Int>("a") {
            control { cold }
            candidate { emptyFlow() }
            window(size = 1, timeout = 10.seconds)
        }.test {
            awaitItem()
            awaitComplete()
        }
        assertThat(produceCount).isEqualTo(1)

        experimentFlow<Int>("b") {
            control { cold }
            candidate { emptyFlow() }
            window(size = 1, timeout = 10.seconds)
        }.test {
            awaitItem()
            awaitComplete()
        }
        assertThat(produceCount).isEqualTo(2)
    }
}
