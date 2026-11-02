package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.timschneeberger.rootlessjamesdsp.R

/** Transient service evidence, not a profile setting or a claim of exclusive USB access. */
object UsbAudioFollowStatus {
    private val mutable = MutableStateFlow(UsbSampleRateFollow.Status())
    val state = mutable.asStateFlow()
    fun publish(status: UsbSampleRateFollow.Status) { mutable.value = status }
    fun summary(context: Context, enabled: Boolean, status: UsbSampleRateFollow.Status = state.value): String {
        if (!enabled) return context.getString(R.string.usb_audio_follow_off)
        if (isRoot() && RootAccessStatus.state.value == RootAccessStatus.State.UNAVAILABLE)
            return context.getString(R.string.audio_root_access_help)
        val detail = when (status.stage) {
            UsbSampleRateFollow.Stage.OFF, UsbSampleRateFollow.Stage.WAITING_USB -> context.getString(R.string.usb_audio_follow_wait_usb)
            UsbSampleRateFollow.Stage.WAITING_AUDIO -> context.getString(R.string.usb_audio_follow_wait_audio)
            UsbSampleRateFollow.Stage.ACTIVE -> context.getString(R.string.usb_audio_follow_actual, status.rates.joinToString(" / "))
        }
        return "$detail\n${context.getString(R.string.usb_audio_follow_scope)}"
    }
}
