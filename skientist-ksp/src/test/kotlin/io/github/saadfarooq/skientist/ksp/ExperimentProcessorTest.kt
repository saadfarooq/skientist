@file:Suppress("OPT_IN_USAGE_ERROR")

package io.github.saadfarooq.skientist.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.symbolProcessorProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class ExperimentProcessorTest {

    @Test
    fun `generates proxy that compiles with control and candidate`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "TestRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment

                object TestConfig : ExperimentConfigBlock<String>

                @Experiment(name = "test-repo", config = TestConfig::class)
                interface TestRepository {
                    suspend fun fetch(): String
                }

                class ControlImpl : TestRepository {
                    override suspend fun fetch(): String = "control"
                }

                class CandidateImpl : TestRepository {
                    override suspend fun fetch(): String = "candidate"
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "Usage.kt", """
                package test

                import kotlinx.coroutines.runBlocking

                fun main() {
                    val proxy = ExperimentingTestRepository(ControlImpl(), CandidateImpl()) { println(it) }
                    val result = runBlocking { proxy.fetch() }
                    println(result)
                }
                """.trimIndent()
            ),
        )
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed: ${result.messages}")
    }

    @Test
    fun `generates proxy with multi-param method`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "ArgsRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment

                object ArgsConfig : ExperimentConfigBlock<String>

                @Experiment(name = "args-repo", config = ArgsConfig::class)
                interface ArgsRepository {
                    suspend fun find(id: String, limit: Int, filter: String): String
                }

                class ArgsControl : ArgsRepository {
                    override suspend fun find(id: String, limit: Int, filter: String): String =
                        id + limit + filter
                }

                class ArgsCandidate : ArgsRepository {
                    override suspend fun find(id: String, limit: Int, filter: String): String =
                        id + limit + filter
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "ArgsUsage.kt", """
                package test

                import kotlinx.coroutines.runBlocking

                fun main() {
                    val proxy = ExperimentingArgsRepository(ArgsControl(), ArgsCandidate()) { }
                    val r = runBlocking { proxy.find("42", 10, "x") }
                    println(r)
                }
                """.trimIndent()
            ),
        )
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed: ${result.messages}")
    }

    @Test
    fun `generates proxy for interface with multiple methods`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "MultiRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment

                object MultiConfig : ExperimentConfigBlock<String>

                @Experiment(name = "multi", config = MultiConfig::class)
                interface MultiRepository {
                    suspend fun getById(id: String): String
                    suspend fun getAll(): List<String>
                }

                class MultiControl : MultiRepository {
                    override suspend fun getById(id: String): String = id
                    override suspend fun getAll(): List<String> = listOf("a")
                }

                class MultiCandidate : MultiRepository {
                    override suspend fun getById(id: String): String = id
                    override suspend fun getAll(): List<String> = listOf("a")
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "MultiUsage.kt", """
                package test

                import kotlinx.coroutines.runBlocking

                fun main() {
                    val proxy = ExperimentingMultiRepository(MultiControl(), MultiCandidate()) { }
                    val r = runBlocking { proxy.getById("1") }
                    val l = runBlocking { proxy.getAll() }
                    println(r + l)
                }
                """.trimIndent()
            ),
        )
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed: ${result.messages}")
    }

    @Test
    fun `generates proxy for non-suspending method`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "SyncRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment

                object SyncConfig : ExperimentConfigBlock<Int>

                @Experiment(name = "sync", config = SyncConfig::class)
                interface SyncRepository {
                    fun getValue(): Int
                }

                class SyncControl : SyncRepository {
                    override fun getValue(): Int = 42
                }

                class SyncCandidate : SyncRepository {
                    override fun getValue(): Int = 43
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "SyncUsage.kt", """
                package test

                fun main() {
                    val proxy = ExperimentingSyncRepository(SyncControl(), SyncCandidate()) { }
                    val r = proxy.getValue()
                    println(r)
                }
                """.trimIndent()
            ),
        )
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed: ${result.messages}")
    }

    @Test
    fun `config with custom compareWith is referenced correctly`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "CustomRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment

                object CustomConfig : ExperimentConfigBlock<String> {
                    override fun compareWith(a: String, b: String): Boolean =
                        a.length == b.length
                    override fun enabled(): Boolean = false
                }

                @Experiment(name = "custom", config = CustomConfig::class)
                interface CustomRepository {
                    suspend fun fetch(): String
                }

                class CustomControl : CustomRepository {
                    override suspend fun fetch(): String = "hello"
                }

                class CustomCandidate : CustomRepository {
                    override suspend fun fetch(): String = "world"
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "CustomUsage.kt", """
                package test

                import kotlinx.coroutines.runBlocking

                fun main() {
                    val proxy = ExperimentingCustomRepository(CustomControl(), CustomCandidate()) { }
                    val r = runBlocking { proxy.fetch() }
                    println(r)
                }
                """.trimIndent()
            ),
        )
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, "compile failed: ${result.messages}")
    }

    @Test
    fun `generates experimentFlow for Flow return type`() {
        val compilation = newCompilation(
            SourceFile.kotlin(
                "FlowRepo.kt", """
                package test

                import io.github.saadfarooq.skientist.ExperimentConfigBlock
                import io.github.saadfarooq.skientist.ExperimentResult
                import io.github.saadfarooq.skientist.Experiment
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.flow

                object FlowConfig : ExperimentConfigBlock<String>

                @Experiment(name = "flow-repo", config = FlowConfig::class)
                interface FlowRepository {
                    suspend fun getOne(): String
                    fun stream(): Flow<String>
                }

                class FlowControl : FlowRepository {
                    override suspend fun getOne(): String = "a"
                    override fun stream(): Flow<String> = flow { emit("x") }
                }

                class FlowCandidate : FlowRepository {
                    override suspend fun getOne(): String = "a"
                    override fun stream(): Flow<String> = flow { emit("x") }
                }
                """.trimIndent()
            ),
            SourceFile.kotlin(
                "FlowUsage.kt", """
                package test

                import kotlinx.coroutines.runBlocking

                fun main() {
                    val proxy = ExperimentingFlowRepository(FlowControl(), FlowCandidate()) { }
                    val one = runBlocking { proxy.getOne() }
                    println(one)
                }
                """.trimIndent()
            ),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, compilation.compile().exitCode)
    }

    // ── Helpers ─────────────────────────────────────────

    private fun newCompilation(vararg sources: SourceFile): KotlinCompilation {
        return KotlinCompilation().apply {
            this.sources = sources.toList()
            symbolProcessorProviders = listOf(ExperimentProcessorProvider())
            classpaths = listOf(skientistClasses())
            inheritClassPath = true
            verbose = false
        }
    }

    private fun skientistClasses(): File {
        val dir = File("../skientist/build/classes/kotlin/main")
        if (dir.exists()) return dir
        val jvmDir = File("../skientist/build/classes/kotlin/jvm/main")
        if (jvmDir.exists()) return jvmDir
        error("No skientist classes found. Run :skientist:classes first: ${dir.absolutePath}")
    }
}
