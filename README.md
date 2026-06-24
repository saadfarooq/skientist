# Skientist

[![CI](https://github.com/saadfarooq/skientist/actions/workflows/ci.yml/badge.svg)](https://github.com/saadfarooq/skientist/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.saadfarooq/skientist)](https://central.sonatype.com/artifact/io.github.saadfarooq/skientist)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE.txt)

A Kotlin library for carefully refactoring critical paths — run old and new code side-by-side in production, compare results, measure timing, and publish outcomes. Always returns the control value. The candidate is invisible to the user.

```kotlin
// DSL
val user = experiment<User>("fetch-user") {
    enabled { remoteConfig.getBoolean("experiment_room") }
    compareWith { a, b -> a.id == b.id && a.name == b.name }
    publish { result -> analytics.track("experiment", result.toMap()) }
    control { datastore.getUser(id) }
    candidate { room.getUser(id) }
}

// Chain API
val user = experiment<User>("fetch-user")
    .enabled { remoteConfig.getBoolean("experiment_room") }
    .compareWith { a, b -> a.id == b.id }
    .publish { result -> analytics.track("experiment", result.toMap()) }
    .control { datastore.getUser(id) }
    .candidate { room.getUser(id) }
    .run()
```

### Streaming / Flow

```kotlin
// Compare every 100 events (or on flow completion)
val userStream: Flow<User> = experimentFlow<User>("user-stream") {
    enabled { remoteConfig.getBoolean("experiment_room") }
    control { datastore.observeUsers() }
    candidate { room.observeUsers() }
    window(size = 100, timeout = 5.minutes)
    compareWith { a, b -> a.id == b.id }
    publish { window -> analytics.track("window_compare", window.toMap()) }
}

// Chain API
val userStream = experimentFlow<User>("user-stream")
    .enabled { remoteConfig.getBoolean("experiment_room") }
    .compareWith { a, b -> a.id == b.id }
    .publish { result -> analytics.track("window_compare", result.toMap()) }
    .control { datastore.observeUsers() }
    .candidate { room.observeUsers() }
    .window(size = 100, timeout = 5.minutes)
    .run()
```

### KSP Code Generation

Mark your interface with `@Experiment`, the two implementations with `@Control` and `@Candidate`. KSP generates a type-safe sealed result hierarchy and a proxy that delegates each method to `experiment()` or `experimentFlow()`.

```kotlin
@Experiment(name = "user-repo", config = UserRepoConfig::class)
interface UserRepository {
    suspend fun getUser(id: String): User
    suspend fun getAllUsers(): List<User>
}

@Control
class DatastoreUserRepository @Inject constructor(
    private val datastore: DataStore<UserPreferences>
) : UserRepository { ... }

@Candidate
class RoomUserRepository @Inject constructor(
    private val db: UserDatabase
) : UserRepository { ... }

object UserRepoConfig : ExperimentConfigBlock<Any?> {
    override fun compareWith(a: Any?, b: Any?) = a == b
    override fun enabled() = remoteConfig.getBoolean("experiment_room")
}
```

Wire the generated proxy with your DI framework. The publish lambda gets compiler-enforced exhaustive `when` branches — every method is covered.

```kotlin
// Hilt / Dagger
@Provides
fun provideUserRepository(
    @Named("datastore") control: UserRepository,
    @Named("room") candidate: UserRepository,
): UserRepository = ExperimentingUserRepository(control, candidate) { result ->
    when (result) {
        is UserRepositoryMethodResult.GetUser ->
            analytics.track("getUser", result.result)
        is UserRepositoryMethodResult.GetAllUsers ->
            analytics.track("getAllUsers", result.result)
    }
}

// Koin
single<UserRepository> {
    ExperimentingUserRepository(
        userRepositoryControl = get(named("datastore")),
        userRepositoryCandidate = get(named("room")),
    ) { result ->
        when (result) {
            is UserRepositoryMethodResult.GetUser -> analytics.track("getUser", result.result)
            is UserRepositoryMethodResult.GetAllUsers -> analytics.track("getAllUsers", result.result)
        }
    }
}
```

When the experiment proves out, swap the DI binding to `RoomUserRepository` — no other code changes.

## Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.saadfarooq:skientist:0.1.0")
    ksp("io.github.saadfarooq:skientist-ksp:0.1.0")
}
```

## License

```
Copyright 2026 Saad Farooq

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
