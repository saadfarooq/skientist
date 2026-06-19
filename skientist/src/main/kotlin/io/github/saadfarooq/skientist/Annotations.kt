package io.github.saadfarooq.skientist

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class Experiment(
    val name: String,
    val config: KClass<out ExperimentConfigBlock<*>>,
    val windowSize: Int = 100,
    val windowTimeoutMs: Long = 300_000,
)

@Target(AnnotationTarget.CLASS)
annotation class Control

@Target(AnnotationTarget.CLASS)
annotation class Candidate
