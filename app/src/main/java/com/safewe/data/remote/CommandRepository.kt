package com.safewe.data.remote

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
    private val quota: com.safewe.transfer.QuotaTracker
) {
    private fun commands(childUid: String) = db.collection("devices").document(childUid).collection("commands")
    private fun results(childUid: String) = db.collection("devices").document(childUid).collection("results")

    // ---------------- Parent ----------------
    suspend fun requestLocation(childUid: String): String {
        quota.add(writes = 1)
        val id = UUID.randomUUID().toString() // unique ID -> idempotency + replay guard
        val now = System.currentTimeMillis()
        commands(childUid).document(id).set(
            Command(id, TYPE_GET_LOCATION, requireNotNull(auth.uid), now, now + COMMAND_TTL_MS)
        ).await()
        return id
    }

    fun observeResult(childUid: String, commandId: String): Flow<LocationResult?> = callbackFlow {
        val reg = results(childUid).document(commandId).addSnapshotListener { s, _ ->
            trySend(s?.toObject(LocationResult::class.java))
        }
        awaitClose { reg.remove() }
    }

    /** Delete after reading to stay inside Spark quotas. */
    suspend fun cleanup(childUid: String, commandId: String) {
        quota.add(deletes = 2)
        results(childUid).document(commandId).delete().await()
        commands(childUid).document(commandId).delete().await()
    }

    suspend fun requestHistory(childUid: String): String {
        quota.add(writes = 1)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        commands(childUid).document(id).set(
            Command(id, TYPE_GET_HISTORY, requireNotNull(auth.uid), now, now + COMMAND_TTL_MS)
        ).await()
        return id
    }

    fun observeHistory(childUid: String, commandId: String): Flow<HistoryResult?> = callbackFlow {
        val reg = results(childUid).document(commandId).addSnapshotListener { s, _ ->
            trySend(s?.toObject(HistoryResult::class.java))
        }
        awaitClose { reg.remove() }
    }

    suspend fun requestUsage(childUid: String, days: Int): String {
        quota.add(writes = 1)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        commands(childUid).document(id).set(
            Command(id, TYPE_GET_USAGE, requireNotNull(auth.uid), now, now + COMMAND_TTL_MS, param = days.coerceIn(1, 31))
        ).await()
        return id
    }

    fun observeUsage(childUid: String, commandId: String): Flow<UsageResult?> = callbackFlow {
        val reg = results(childUid).document(commandId).addSnapshotListener { s, _ ->
            trySend(s?.toObject(UsageResult::class.java))
        }
        awaitClose { reg.remove() }
    }

    // ---------------- Child ----------------
    /** Active listener: works only while the app/service process is alive. */
    fun observeIncoming(childUid: String): Flow<Command> = callbackFlow {
        val reg = commands(childUid).whereEqualTo("status", "REQUESTED")
            .addSnapshotListener { s, _ ->
                s?.documentChanges?.filter { it.type == DocumentChange.Type.ADDED }?.forEach {
                    trySend(it.document.toObject(Command::class.java))
                }
            }
        awaitClose { reg.remove() }
    }

    /** Manual cleanup (no paid TTL): expired commands, and results the parent never collected. */
    suspend fun sweepStale(childUid: String) {
        val now = System.currentTimeMillis()
        val old = commands(childUid).whereLessThan("expiresAt", now).get().await().documents
        val results = results(childUid).get().await().documents
        var deleted = 0L
        old.forEach { it.reference.delete().await(); deleted++ }
        results.forEach { d ->
            val t = listOf("timestamp", "createdAt", "generatedAt").maxOf { d.getLong(it) ?: 0L }
            if (now - t > 10 * 60_000L) { d.reference.delete().await(); deleted++ }
        }
        quota.add(reads = (old.size + results.size).toLong(), deletes = deleted)
    }

    suspend fun markStatus(childUid: String, id: String, status: String) {
        commands(childUid).document(id).update("status", status).await()
    }

    suspend fun publishResult(childUid: String, result: LocationResult) {
        quota.add(writes = 2)
        results(childUid).document(result.commandId).set(result).await()
        markStatus(childUid, result.commandId, "COMPLETED")
    }

    suspend fun publishHistory(childUid: String, result: HistoryResult) {
        quota.add(writes = 2)
        results(childUid).document(result.commandId).set(result).await()
        markStatus(childUid, result.commandId, "COMPLETED")
    }

    suspend fun publishUsage(childUid: String, result: UsageResult) {
        quota.add(writes = 2)
        results(childUid).document(result.commandId).set(result).await()
        markStatus(childUid, result.commandId, "COMPLETED")
    }

    companion object {
        const val TYPE_GET_USAGE = "GET_USAGE"
        const val TYPE_GET_HISTORY = "GET_HISTORY"
        const val TYPE_GET_LOCATION = "GET_LOCATION"
        const val COMMAND_TTL_MS = 2 * 60 * 1000L
    }
}
