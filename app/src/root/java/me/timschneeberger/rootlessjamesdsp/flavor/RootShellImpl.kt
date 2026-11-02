package me.timschneeberger.rootlessjamesdsp.flavor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.utils.RootAccessStatus
import me.timschneeberger.rootlessjamesdsp.utils.RootCommandResult
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

object RootShellImpl {
    private const val ROOT_RETRY_INTERVAL_MS=30000L
    private val acquisitionLock=Any()
    private val backgroundRequestQueued=AtomicBoolean(false)
    private val mainHandler=Handler(Looper.getMainLooper())
    private var lastAcquisitionAt:Long?=null

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        try {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
        } catch (_: IllegalStateException) {
            // Session discovery may already own libsu's process-wide shell.
            // Reuse it; opening a settings page must never reconfigure it.
            Timber.d("Reusing the already initialized main root shell")
        }
    }

    /** Called before audio services or session discovery can create libsu's shell. */
    fun initialize() = Unit

    interface OnShellAttachedCallback {
        fun onShellAttached(isRoot: Boolean)
    }

    fun getShell(callback: OnShellAttachedCallback) {
        // libsu otherwise returns its cached NON_ROOT_SHELL immediately, forever.
        Shell.EXECUTOR.execute {
            val granted=acquireShell()!=null
            mainHandler.post {callback.onShellAttached(granted)}
        }
    }

    private fun available(shell:Shell?)=shell?.isRoot==true && shell.isAlive

    /** Recheck normal manager authorization in the app process, at most once per 30 seconds. */
    @WorkerThread
    fun acquireShell():Shell? {
        val cached=Shell.getCachedShell()
        if(available(cached)) {
            RootAccessStatus.publish(RootAccessStatus.State.AVAILABLE)
            return cached
        }
        // Never close a shell or wait for an authorization prompt on the UI thread.
        if(Looper.myLooper()==Looper.getMainLooper()) {
            RootAccessStatus.publish(RootAccessStatus.State.UNAVAILABLE)
            if(backgroundRequestQueued.compareAndSet(false,true))Shell.EXECUTOR.execute {
                try {acquireShell()}finally {backgroundRequestQueued.set(false)}
            }
            return null
        }
        return synchronized(acquisitionLock) {
            // Another background reader may have recovered authorization while we waited.
            val current=Shell.getCachedShell()
            if(available(current)) {
                RootAccessStatus.publish(RootAccessStatus.State.AVAILABLE)
                return@synchronized current
            }
            val now=SystemClock.elapsedRealtime()
            if(lastAcquisitionAt?.let {now-it<ROOT_RETRY_INTERVAL_MS}==true) {
                RootAccessStatus.publish(RootAccessStatus.State.UNAVAILABLE)
                return@synchronized null
            }
            lastAcquisitionAt=now
            try {
                // Never close a live root shell or interrupt its queued privileged jobs.
                // libsu 5.0.4 drops a closed cached shell before getShell() builds again.
                if(current!=null && (!current.isRoot || !current.isAlive)) {
                    current.close()
                    // getCachedShell clears the slot but returns the old closed instance once.
                    Shell.getCachedShell()
                }
                val acquired=Shell.getShell()
                if(available(acquired)) {
                    RootAccessStatus.publish(RootAccessStatus.State.AVAILABLE)
                    acquired
                } else {
                    RootAccessStatus.publish(RootAccessStatus.State.UNAVAILABLE)
                    null
                }
            } catch(error:Exception) {
                RootAccessStatus.publish(RootAccessStatus.State.UNAVAILABLE)
                Timber.w(error,"Root shell authorization is not available")
                null
            }
        }
    }

    fun cmd(command: String): Boolean {
        Timber.d("root command: $command")
        val shell=acquireShell() ?: return false
        val stdout=ArrayList<String>()
        val stderr=ArrayList<String>()
        return shell.newJob().to(stdout,stderr).add(command).exec().run {
            if(!isSuccess) {
                Timber.e("Command failed; error $code")
                stderr.forEach(Timber::e)
            }
            isSuccess
        }
    }

    /** Background-only configuration transport; stdout must remain separate from diagnostics. */
    fun execute(command: String): RootCommandResult {
        val shell=acquireShell() ?: return RootCommandResult(rootGranted = false)
        val stdout = ArrayList<String>()
        val stderr = ArrayList<String>()
        val result = shell.newJob().to(stdout, stderr).add(command).exec()
        return RootCommandResult(rootGranted = true, code = result.code, output = stdout)
    }
}
