# Skientist

A Kotlin library for carefully refactoring critical paths. Port of GitHub's [Scientist](https://github.com/github/scientist) pattern.

## Modules

- `skientist` — Core library: experiment DSL, comparison, publishing, coroutine support. JVM.
- `skientist-ksp` — KSP processor: generates experiment proxy classes from `@Experiment`, `@Control`, `@Candidate` annotations.
- `sample` — End-to-end demo: Datastore → Room migration with KSP-generated proxy.

## Build

```sh
./gradlew build
```

## Test

```sh
./gradlew test
```

Tests use [Turbine](https://github.com/cashapp/turbine) for Flow assertions and [Truth](https://github.com/google/truth) for value assertions. 41 tests total: 22 bounded experiment, 14 streaming/flow, 5 KSP integration.

## Release

```sh
./gradlew publish
./gradlew jreleaserFullRelease
```

Requires `mavenCentralUsername` and `mavenCentralPassword` in `~/.gradle/gradle.properties`.
CI runs tests on push/PR to `main` via `.github/workflows/ci.yml`.

## Package

`io.github.saadfarooq.skientist`

## API

### Bounded experiments

```kotlin
// DSL
experiment<User>("fetch-user") {
    enabled { remoteConfig.getBoolean("experiment_room") }
    compareWith { a, b -> a.id == b.id }
    publish { result -> analytics.track(result) }
    context("userId", id)
    control { datastore.getUser(id) }
    candidate { room.getUser(id) }
}

// Chain
experiment<User>("fetch-user")
    .enabled { ... }
    .control { ... }
    .candidate { ... }
    .run()
```

### Streaming experiments

```kotlin
// DSL
experimentFlow<User>("user-stream") {
    control { datastore.observeUsers() }
    candidate { room.observeUsers() }
    window(size = 100, timeout = 5.minutes)
    compareWith { a, b -> a.id == b.id }
    publish { window -> analytics.track(window) }
}

// Chain
experimentFlow<User>("user-stream")
    .control { ... }
    .candidate { ... }
    .window(size = 100, timeout = 5.minutes)
    .run()
```

### KSP code generation

```kotlin
@Experiment(name = "user-repo", config = UserRepoConfig::class)
interface UserRepository {
    suspend fun getUser(id: String): User          // bounded → experiment
    fun observeUsers(): Flow<User>                  // flow → experimentFlow
}

object UserRepoConfig : ExperimentConfigBlock<Any?> {
    override fun compareWith(a: Any?, b: Any?) = a == b
    override fun enabled() = remoteConfig.getBoolean("experiment_room")
    override fun publish(result: ExperimentResult<*>) = analytics.track(result)
}

// KSP generates: ExperimentingUserRepository(control, candidate)
// — getUser() delegates to experiment()
// — observeUsers() delegates to experimentFlow() with window()
```

## Design

### Core types (v0.1.0)

```
experiment<T>()          — bounded: runs control + candidate, returns control
experimentFlow<T>()      — streaming: wraps control flow, forwards, windows for comparison
ExperimentScope<T>       — DSL for bounded experiments (enabled, compareWith, publish, context, control, candidate)
FlowExperimentScope<T>   — DSL for flow experiments (control, candidate, window, compareWith, publish)
ExperimentConfigBlock<T> — config interface for KSP code generation
ExperimentResult<T>      — control observation, candidate observation, matched, context
FlowExperimentResult<T>  — per-window result: snapshots, match, error, timing
Observation<T>           — name, value?, error?, durationNs, cpuDurationNs?
Publisher                — fun interface: publish(ExperimentResult<*>)
```

### KSP

- Annotations: `@Experiment(name, config)`, `@Control`, `@Candidate` (all in core module)
- Processor reads `@Experiment` on interfaces, generates proxy that delegates each method to `experiment<T>()`
- Config via `ExperimentConfigBlock<T>` interface — user writes an `object`
- Generated proxy takes `control` and `candidate` constructor params (prefixed with camelCase interface name)

### What was skipped

- `clean` / `ignore` — `compareWith` covers both. Add when per-field mismatch reporting is needed.
- Multiple candidates — single candidate per experiment. Add when A/B/C testing is needed.
- KMP — JVM only. Add `expect PlatformClock` when first non-JVM user appears.
- Per-emission comparison in flow — window-level only. Add per-item results when needed.
- Time-based windowing in flow — size-based + flush on completion only. Add timer-based when needed.
- `@Control`/`@Candidate` compile-time verification — annotations exist but aren't enforced by KSP.
