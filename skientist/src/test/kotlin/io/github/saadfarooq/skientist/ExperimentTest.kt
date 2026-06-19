@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.saadfarooq.skientist

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class ExperimentTest {

    // ── Control ────────────────────────────────────────

    @Test
    fun `returns control value when candidate matches`() = runTest {
        val result = experiment<String>("test") {
            control { "hello" }
            candidate { "hello" }
        }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `returns control value when candidate differs`() = runTest {
        val result = experiment<String>("test") {
            control { "hello" }
            candidate { "world" }
        }
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `returns control value when candidate errors`() = runTest {
        val result = experiment<String>("test") {
            control { "safe" }
            candidate { throw IOException("boom") }
        }
        assertThat(result).isEqualTo("safe")
    }

    @Test
    fun `throws when control errors`() = runTest {
        var candidateRan = false
        val error = try {
            experiment<String>("test") {
                control { throw IOException("control failed") }
                candidate { candidateRan = true; "candidate" }
            }
            null
        } catch (e: IOException) {
            e
        }
        assertThat(error).isNotNull()
        assertThat(error).hasMessageThat().isEqualTo("control failed")
        assertThat(candidateRan).isTrue()
    }

    // ── Enabled ─────────────────────────────────────────

    @Test
    fun `skips candidate when enabled is false`() = runTest {
        var candidateRan = false
        experiment<String>("test") {
            enabled { false }
            control { "control" }
            candidate { candidateRan = true; "candidate" }
        }
        assertThat(candidateRan).isFalse()
    }

    @Test
    fun `runs candidate when enabled is true`() = runTest {
        var candidateRan = false
        experiment<String>("test") {
            enabled { true }
            control { "control" }
            candidate { candidateRan = true; "candidate" }
        }
        assertThat(candidateRan).isTrue()
    }

    // ── Comparison ──────────────────────────────────────

    @Test
    fun `publishes matched=true when values equal`() = runTest {
        var matched: Boolean? = null
        experiment<String>("test") {
            publish { matched = it.matched }
            control { "hello" }
            candidate { "hello" }
        }
        assertThat(matched).isTrue()
    }

    @Test
    fun `publishes matched=false when values differ`() = runTest {
        var matched: Boolean? = null
        experiment<String>("test") {
            publish { matched = it.matched }
            control { "hello" }
            candidate { "world" }
        }
        assertThat(matched).isFalse()
    }

    @Test
    fun `publishes matched=false when candidate errors`() = runTest {
        var matched: Boolean? = null
        experiment<String>("test") {
            publish { matched = it.matched }
            control { "hello" }
            candidate { throw IOException("boom") }
        }
        assertThat(matched).isFalse()
    }

    @Test
    fun `uses custom comparator`() = runTest {
        var matched: Boolean? = null
        experiment<String>("test") {
            compareWith { a, b -> a.length == b.length }
            publish { matched = it.matched }
            control { "hey" }
            candidate { "you" }
        }
        assertThat(matched).isTrue()
    }

    // ── Publish ─────────────────────────────────────────

    @Test
    fun `publishes when candidate is skipped`() = runTest {
        var published = false
        experiment<String>("test") {
            enabled { false }
            publish { published = true }
            control { "only" }
        }
        assertThat(published).isTrue()
    }

    @Test
    fun `publishes correct experiment name`() = runTest {
        var name: String? = null
        experiment<String>("my-experiment") {
            publish { name = it.experimentName }
            control { "x" }
        }
        assertThat(name).isEqualTo("my-experiment")
    }

    // ── Result shape ────────────────────────────────────

    @Test
    fun `result has candidate=null when disabled`() = runTest {
        var candidate: Observation<String>? = Observation("x", "x", null, 0, null, false)
        experiment<String>("test") {
            enabled { false }
            publish { candidate = it.candidate }
            control { "x" }
        }
        assertThat(candidate).isNull()
    }

    @Test
    fun `result has candidate when enabled`() = runTest {
        var obs: Observation<String>? = null
        experiment<String>("test") {
            publish { obs = it.candidate }
            control { "x" }
            candidate { "y" }
        }
        val candidate = obs!!
        assertThat(candidate.name).isEqualTo("candidate")
        assertThat(candidate.value).isEqualTo("y")
    }

    @Test
    fun `observations have wall clock timing`() = runTest {
        var controlDur: Long? = null
        var candidateDur: Long? = null
        experiment<String>("test") {
            publish {
                controlDur = it.control.durationNs
                candidateDur = it.candidate?.durationNs
            }
            control { "x" }
            candidate { "y" }
        }
        assertThat(controlDur).isGreaterThan(0)
        assertThat(candidateDur).isGreaterThan(0)
    }

    // ── Context ─────────────────────────────────────────

    @Test
    fun `context is available in publish`() = runTest {
        var ctx: Map<String, Any?>? = null
        experiment<String>("test") {
            context("userId", "42")
            context("screen", "profile")
            publish { ctx = it.context }
            control { "x" }
        }
        assertThat(ctx?.get("userId")).isEqualTo("42")
        assertThat(ctx?.get("screen")).isEqualTo("profile")
    }

    @Test
    fun `context is available in enabled lambda`() = runTest {
        var candidateRan = false
        experiment<String>("test") {
            context("userId", "42")
            enabled { context["userId"] == "42" }
            publish { candidateRan = it.candidate != null }
            control { "x" }
            candidate { "y" }
        }
        assertThat(candidateRan).isTrue()
    }

    // ── Edge cases ──────────────────────────────────────

    @Test
    fun `candidate error contains exception details`() = runTest {
        var error: Throwable? = null
        experiment<String>("test") {
            publish { error = it.candidate?.error }
            control { "x" }
            candidate { throw IOException("disk full") }
        }
        assertThat(error).isInstanceOf(IOException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("disk full")
    }

    @Test
    fun `no candidate declared is a noop`() = runTest {
        val result = experiment<String>("test") {
            control { "only control" }
        }
        assertThat(result).isEqualTo("only control")
    }

    @Test
    fun `handles nullable types`() = runTest {
        val result = experiment<String?>("test") {
            control { null }
            candidate { null }
        }
        assertThat(result).isNull()
    }

    // ── DSL + chain equivalence ────────────────────────

    @Test
    fun `DSL and chain API produce same result`() = runTest {
        var dslMatched: Boolean? = null
        var chainMatched: Boolean? = null

        experiment<String>("dsl") {
            enabled { true }
            compareWith { a, b -> a == b }
            publish { dslMatched = it.matched }
            control { "a" }
            candidate { "b" }
        }

        experiment<String>("chain")
            .enabled { true }
            .compareWith { a, b -> a == b }
            .publish { chainMatched = it.matched }
            .control { "a" }
            .candidate { "b" }
            .run()

        assertThat(dslMatched).isFalse()   // "a" != "b"
        assertThat(chainMatched).isFalse() // same logic, same result
    }

    // ── Chain API ───────────────────────────────────────

    @Test
    fun `chain API returns control value`() = runTest {
        val result = experiment<String>("test-chain")
            .control { "hello" }
            .candidate { "world" }
            .run()
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `chain API respects enabled`() = runTest {
        var candidateRan = false
        experiment<String>("test-chain")
            .enabled { false }
            .control { "control" }
            .candidate { candidateRan = true; "candidate" }
            .run()
        assertThat(candidateRan).isFalse()
    }

    @Test
    fun `chain API publishes results`() = runTest {
        var matched: Boolean? = null
        experiment<String>("test-chain")
            .publish { matched = it.matched }
            .control { "x" }
            .candidate { "x" }
            .run()
        assertThat(matched).isTrue()
    }

    @Test
    fun `chain API with context`() = runTest {
        var ctx: Map<String, Any?>? = null
        experiment<String>("test-chain")
            .context("key", "val")
            .publish { ctx = it.context }
            .control { "x" }
            .run()
        assertThat(ctx?.get("key")).isEqualTo("val")
    }
}
