package me.timschneeberger.rootlessjamesdsp.flavor

import android.os.SystemClock
import timber.log.Timber

object RootPlaybackDumpSource {
    private const val SEPARATOR = "__DSP_ACTIVE_TRACK_PROCESSES__"
    private var lastFailure: String? = null
    private var lastFailureAt = 0L

    private fun unavailable(reason: String, detail: String): Pair<String, String>? {
        val now=SystemClock.elapsedRealtime()
        if(lastFailure!=reason || now-lastFailureAt>=30000) {
            Timber.w("Root playback discovery unavailable: %s",detail)
            lastFailureAt=now
        }
        lastFailure=reason
        return null
    }

    /** Fixed read-only commands. An empty/failed dump is unavailable, never invented playback. */
    fun read(): Pair<String, String>? {
        return try {
            val shell=RootShellImpl.acquireShell()
                ?: return unavailable("no_root", "root shell is not available")
            // libsu newJob() discards stdout unless an explicit output sink is supplied.
            // Separate stderr also prevents the shell's redirect flag from corrupting tables.
            val stdout=ArrayList<String>()
            val stderr=ArrayList<String>()
            val result = shell.newJob().to(stdout,stderr).add(
                "dumpsys -t 2 media.audio_flinger",
                "printf '\\n$SEPARATOR\\n'",
                "ps -A -o PID,UID,NAME"
            ).exec()
            if (!result.isSuccess) return unavailable("exit_${result.code}",
                "command exit ${result.code}: ${stderr.take(3).joinToString("; ").take(240)}")
            val separator = stdout.indexOf(SEPARATOR)
            if (separator < 0) return unavailable("missing_separator", "stdout has no dump separator")
            val audio=stdout.take(separator).joinToString("\n")
            val processes=stdout.drop(separator + 1).joinToString("\n")
            if(audio.isBlank() || processes.isBlank())return unavailable("empty_dump", "audio/process dump is empty")
            if(lastFailure!=null)Timber.d("Root playback discovery transport recovered")
            lastFailure=null
            audio to processes
        } catch(error: Exception) {
            unavailable("exception_${error.javaClass.simpleName}",
                "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}")
        }
    }
}
