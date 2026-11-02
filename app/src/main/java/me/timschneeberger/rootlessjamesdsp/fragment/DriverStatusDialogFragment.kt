package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.media.audiofx.AudioEffectHidden
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder as MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.DriverInstallationInfo
import me.timschneeberger.rootlessjamesdsp.fusion.DriverStatusReader
import me.timschneeberger.rootlessjamesdsp.fusion.DriverStatusText
import me.timschneeberger.rootlessjamesdsp.model.root.RemoteEffectSession
import me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import org.koin.android.ext.android.inject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads existing sessions only. It never opens an effect, writes a parameter, or requests recovery. */
class DriverStatusDialogFragment : DialogFragment() {
    private val routingObserver: RoutingObserver by inject()
    private var detail: TextView? = null
    private var readJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = TextView(requireContext()).apply {
            id = R.id.driver_status_detail
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(padding, padding / 2, padding, padding / 2)
            setText(R.string.driver_status_reading)
        }
        detail = content
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.driver_status_title)
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNeutralButton(R.string.driver_status_refresh, null)
            .setPositiveButton(R.string.driver_status_close, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        readJob?.cancel()
        detail?.setText(R.string.driver_status_reading)
        val appContext = requireContext().applicationContext
        val output = routingObserver.currentDevice?.name
        // Only copy the main-thread session map and immutable metadata here; native reads happen in IO.
        val sources = runCatching {
            (appContext as? MainApplication)?.rootSessionDatabase?.sessionList?.toMap()?.mapNotNull { (id, session) ->
                (session as? RemoteEffectSession)?.let {
                    DriverStatusReader.Source(id, it.packageName, it.uid, it.effect?.effect?.let(::NativeProbe))
                }
            }
        }.getOrNull()
        readJob = lifecycleScope.launch {
            val service = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                        ?.getRunningServices(Int.MAX_VALUE)?.any { it.service.className == RootAudioProcessorService::class.java.name }
                }.getOrNull()
            }
            val snapshot = DriverStatusReader().read(output, service, sources)
            val appVersion = BuildConfig.VERSION_NAME
            if (isActive && dialog?.isShowing == true)
                detail?.text = DriverStatusText.format(snapshot, applicationVersion = appVersion)
            // Optional installation metadata never delays the existing audio-status snapshot.
            val installation = DriverInstallationInfo.read(snapshot)
            if (isActive && dialog?.isShowing == true)
                detail?.text = DriverStatusText.format(snapshot, installation, appVersion)
        }
    }

    override fun onStop() {
        readJob?.cancel()
        readJob = null
        super.onStop()
    }

    override fun onDestroyView() {
        readJob?.cancel()
        detail = null
        super.onDestroyView()
    }

    private class NativeProbe(private val effect: AudioEffectHidden) : DriverStatusReader.Probe {
        override fun integer(parameter: Int): Int? {
            require(parameter in DriverStatusReader.READ_ONLY_PARAMETERS)
            val bytes = ByteArray(4)
            return if (effect.getParameter(parameter, bytes) == bytes.size)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).int else null
        }
        override fun enabled(): Boolean = effect.enabled
        override fun hasControl(): Boolean = effect.hasControl()
        override fun descriptor(): DriverStatusReader.Descriptor = effect.descriptor.let {
            DriverStatusReader.Descriptor(it.name, it.implementor)
        }
    }

    companion object {
        const val TAG = "driver_status_details"
    }
}
