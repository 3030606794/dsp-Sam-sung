package me.timschneeberger.rootlessjamesdsp.session.root

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.flavor.RootPlaybackDumpSource
import timber.log.Timber

/** Read-only fallback for root mode; does not require/grant the application's DUMP permission. */
class RootActivePlaybackDiscovery(context: Context) {
    data class Snapshot(val players: List<ActivePlaybackSession>, val globalEvidence: GlobalPlaybackEvidence?,
                        val processes: Map<Int, ActivePlaybackSessionParser.ProcessIdentity>? = null)
    private val context = context.applicationContext

    suspend fun discover(includeGlobalEvidence: Boolean = true): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val dump = RootPlaybackDumpSource.read() ?: return@withContext null
            val players = ActivePlaybackSessionParser.sessions(
                dump.first, dump.second, context.applicationInfo.uid, context.packageName
            ) { process ->
                val packages = context.packageManager.getPackagesForUid(process.uid).orEmpty()
                val processPackage = process.processName.substringBefore(':')
                // A shared UID cannot authorize an arbitrary sibling package for an allowlist.
                packages.firstOrNull { it == processPackage } ?: packages.singleOrNull().orEmpty()
            } ?: return@withContext null
            Snapshot(players, if (includeGlobalEvidence) GlobalPlaybackEvidence.parse(dump.first) else null,
                ActivePlaybackSessionParser.processes(dump.second))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.d(error, "Active playback session discovery unavailable")
            null
        }
    }
}
