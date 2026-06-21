package io.github.saadfarooq.skientist.sample

import io.github.saadfarooq.skientist.ExperimentConfigBlock
import io.github.saadfarooq.skientist.ExperimentResult
import io.github.saadfarooq.skientist.Control
import io.github.saadfarooq.skientist.Experiment
import io.github.saadfarooq.skientist.Candidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

// ── 1. Interface ───────────────────────────────────────

@Experiment(name = "user-repo", config = UserRepoConfig::class)
interface UserRepository {
    suspend fun getUser(id: String): String
    suspend fun getAllUsers(): List<String>
    fun observeUsers(): Flow<String>
}

// ── 2. Config ──────────────────────────────────────────

object UserRepoConfig : ExperimentConfigBlock<Any?> {
    override fun compareWith(a: Any?, b: Any?): Boolean = a == b
    override fun enabled(): Boolean = true
    override fun publish(result: ExperimentResult<*>) {
        println("[${result.methodName}] matched=${result.matched} " +
                "ctrl=${result.control.durationNs/1_000_000}ms " +
                "cand=${result.candidate?.durationNs?.div(1_000_000)}ms")
    }
}

// ── 3. Implementations ─────────────────────────────────

class DatastoreUserRepository : UserRepository {
    override suspend fun getUser(id: String): String {
        delay(10)
        return "user-$id-from-datastore"
    }
    override suspend fun getAllUsers(): List<String> {
        delay(15)
        return listOf("user-a-from-datastore", "user-b-from-datastore")
    }
    override fun observeUsers(): Flow<String> = flow {
        emit("user-a-from-datastore")
        emit("user-b-from-datastore")
    }
}

class RoomUserRepository : UserRepository {
    override suspend fun getUser(id: String): String {
        delay(5)
        return "user-$id-from-room"
    }
    override suspend fun getAllUsers(): List<String> {
        delay(8)
        return listOf("user-a-from-room", "user-b-from-room")
    }
    override fun observeUsers(): Flow<String> = flow {
        emit("user-a-from-room")
        emit("user-b-from-room")
    }
}

// ── 4. Run ─────────────────────────────────────────────

fun main() = runBlocking {
    // The generated proxy — KSP creates ExperimentingUserRepository at compile time
    val repo: UserRepository = ExperimentingUserRepository(
        userRepositoryControl = DatastoreUserRepository(),
        userRepositoryCandidate = RoomUserRepository(),
        userRepositoryPublish = { methodResult ->
            when (methodResult) {
                is UserRepositoryMethodResult.GetUser ->
                    println("[getUser] matched=${methodResult.result.matched} " +
                            "ctrl=${methodResult.result.control.durationNs/1_000_000}ms")
                is UserRepositoryMethodResult.GetAllUsers ->
                    println("[getAllUsers] matched=${methodResult.result.matched} " +
                            "ctrl=${methodResult.result.control.durationNs/1_000_000}ms")
                is UserRepositoryMethodResult.ObserveUsers ->
                    println("[observeUsers] matched=${methodResult.result.matched} " +
                            "items=${methodResult.result.itemCount}")
            }
        },
    )

    val user = repo.getUser("42")
    println("Got: $user")
    println("---")

    val users = repo.getAllUsers()
    println("Got: $users")
    println("---")

    // Flow: experimentFlow generated automatically
    val stream = repo.observeUsers()
    stream.collect { println("Stream got: $it") }
}
