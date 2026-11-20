package com.galaxyrio.gracelauncher.assistant.memory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.galaxyrio.gracelauncher.BuildConfig
import com.galaxyrio.gracelauncher.assistant.HealthVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide Android side of memory-chat: one Room mirror, one sync engine (so concurrent controllers never double-push),
 * token-aware RPC client. The token stays in [HealthVault]; with no Supabase config or no token everything is local-only.
 */
internal class MemoryRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val vault = HealthVault.create(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store: MemoryLocalStore by lazy {
        val db = MemoryMirrorDatabase.getInstance(appContext)
        RoomMemoryStore(db.dao()) { block -> db.runInTransaction { block() } }
    }
    val sync by lazy {
        MemorySync(store, {
            memoryRpcOrNull(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, vault::tokenSaved, vault::token)
        }, System::currentTimeMillis)
    }
    val turns by lazy { MemoryTurns(store, sync) }

    suspend fun status(): MemorySyncStatus = withContext(Dispatchers.IO) {
        runCatching { sync.status() }.getOrDefault(MemorySyncStatus.LocalOnly)
    }

    /** Chat open, after a local write, connectivity regained, Health token saved (Advanced). Failures stay in the outbox (retried on the next trigger). */
    fun syncSoon() { scope.launch { runCatching { sync.sync() }.onFailure { if (it is CancellationException) throw it } } }

    suspend fun syncNow() = withContext(Dispatchers.IO) { runCatching { sync.sync() } }

    /** Manual button: re-queue unconfirmed rows, then sync. Any failure other than cancellation reports as [SyncOutcome.Failed]. */
    suspend fun syncLocalNow(): SyncOutcome = withContext(Dispatchers.IO) {
        try { sync.syncLocalNow() } catch (e: CancellationException) { throw e } catch (_: Exception) { SyncOutcome.Failed }
    }

    companion object {
        @Volatile private var instance: MemoryRuntime? = null
        fun get(context: Context): MemoryRuntime = instance ?: synchronized(this) { instance ?: MemoryRuntime(context).also { instance = it } }
    }
}

/** Registered for a controller's lifetime (from first chat open until close); no WorkManager. */
internal class MemoryConnectivity(private val context: Context, private val onAvailable: () -> Unit) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun register() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onAvailable() }
        }
        try { manager.registerDefaultNetworkCallback(cb); callback = cb } catch (_: Exception) { }
    }

    fun unregister() {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }
}
