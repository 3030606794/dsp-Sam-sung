package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.BenchmarkManager
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.AudioProcessingModeSettings
import me.timschneeberger.rootlessjamesdsp.utils.UsbAudioFollowStatus
import me.timschneeberger.rootlessjamesdsp.utils.RootAccessStatus
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.requestIgnoreBatteryOptimizations
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class SettingsAudioFormatFragment : SettingsBaseFragment() {

    private val encoding by lazy { findPreference<ListPreference>(getString(R.string.key_audioformat_encoding)) }
    private val bufferSize by lazy { findPreference<MaterialSeekbarPreference>(getString(R.string.key_audioformat_buffersize)) }
    private val legacyMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_processing)) }
    private val enhancedMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_enhanced_processing)) }
    private val enhancedModeInfo by lazy { findPreference<Preference>(getString(R.string.key_audioformat_enhanced_processing_info)) }
    private val benchmark by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_optimization_benchmark)) }
    private val benchmarkRefresh by lazy { findPreference<Preference>(getString(R.string.key_audioformat_optimization_refresh)) }
    private val usbRateFollow by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_usb_rate_follow)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_audio_format_preferences, rootKey)

        // Root: Hide audio format & benchmark category
        encoding?.parent?.isVisible = isRootless()
        benchmark?.parent?.isVisible = !isRoot()

        // Rootless: Hide audio processing category
        legacyMode?.parent?.isVisible = isRoot()
        if (isRoot()) {
            AudioProcessingModeSettings(requireContext(), preferences.preferences) { playerSessions ->
                usbRateFollow?.summary = UsbAudioFollowStatus.summary(requireContext(), playerSessions)
                if (playerSessions) requireContext().requestIgnoreBatteryOptimizations()
                RootAudioProcessorService.refreshProcessingMode(requireContext())
            }.bind(requireNotNull(legacyMode), requireNotNull(enhancedMode), requireNotNull(usbRateFollow))
        }
        enhancedModeInfo?.setOnPreferenceClickListener {
            context?.showAlert(
                R.string.audio_format_enhanced_processing_info_title,
                R.string.audio_format_enhanced_processing_info_content
            )
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbAudioFollowStatus.state.collect { status ->
                    usbRateFollow?.summary = UsbAudioFollowStatus.summary(requireContext(),
                        preferences.get<Boolean>(R.string.key_audioformat_usb_rate_follow), status)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RootAccessStatus.state.collect {
                    usbRateFollow?.summary = UsbAudioFollowStatus.summary(requireContext(),
                        preferences.get<Boolean>(R.string.key_audioformat_usb_rate_follow))
                }
            }
        }

        fun runBenchmark() = context?.let { ctx ->
            BenchmarkManager.runBenchmarks(ctx) {
                CoroutineScope(Dispatchers.Main).launch {
                    benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
                }
            }
        }

        benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
        benchmark?.setOnPreferenceChangeListener { _, newValue ->
            if(newValue as Boolean)
                runBenchmark()
            else
                BenchmarkManager.clearBenchmarks()
            true
        }
        benchmarkRefresh?.setOnPreferenceClickListener {
            runBenchmark()
            true
        }

        bufferSize?.setDefaultValue(preferences.getDefault<Float>(R.string.key_audioformat_buffersize))
        bufferSize?.setOnPreferenceChangeListener { _, newValue ->
            if((newValue as Float) <= 1024){
                requireContext().toast(R.string.audio_format_buffer_size_warning_low_value, false)
            }
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
        encoding?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
    }

    companion object {
        fun newInstance(): SettingsAudioFormatFragment {
            return SettingsAudioFormatFragment()
        }
    }
}
