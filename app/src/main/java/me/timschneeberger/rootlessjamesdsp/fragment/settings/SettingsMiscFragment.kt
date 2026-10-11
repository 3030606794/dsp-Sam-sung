package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.OnboardingActivity
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.preference.IconPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AssetManagerExtensions.installPrivateAssets
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import me.timschneeberger.rootlessjamesdsp.utils.isRootless

class SettingsMiscFragment : SettingsBaseFragment() {

    private val autoStartNotify by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_autostart_prompt_at_boot)) }
    private val repairAssets by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting_repair_assets)) }
    private val crashReports by lazy { findPreference<Preference>(getString(R.string.key_share_crash_reports)) }
    private val debugDatabase by lazy { findPreference<Preference>(getString(R.string.key_debug_database)) }
    private val permSkipPrompt by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_skip_prompt)) }
    private val permAutoStart by lazy { findPreference<IconPreference>(getString(R.string.key_misc_permission_auto_start)) }
    private val permRestartSetup by lazy { findPreference<Preference>(getString(R.string.key_misc_permission_restart_setup)) }


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_misc_preferences, rootKey)

        crashReports?.setOnPreferenceChangeListener { _, newValue ->
            CrashlyticsImpl.setCollectionEnabled(newValue as Boolean)
            true
        }

        repairAssets?.setOnPreferenceClickListener {
            requireContext().assets.installPrivateAssets(requireContext(), force = true)
            requireContext().showAlert(R.string.success, R.string.troubleshooting_repair_assets_success)
            true
        }

        permRestartSetup?.setOnPreferenceClickListener {
            startActivity(Intent(context, OnboardingActivity::class.java).apply {
                putExtra(OnboardingActivity.EXTRA_ROOTLESS_REDO_ADB_SETUP, true)
            })
            true
        }

        updatePermissionStates()

        crashReports?.parent?.isVisible = !BuildConfig.FOSS_ONLY
        debugDatabase?.parent?.isVisible = BuildConfig.DEBUG
        autoStartNotify?.isVisible = isRootless()
        permRestartSetup?.parent?.isVisible = isRootless()
    }

    override fun onResume() {
        updatePermissionStates()
        super.onResume()
    }

    private fun updatePermissionStates() {
        if(!isRootless())
            return

        val allowSkipPrompt = context?.hasProjectMediaAppOp() == true
        val allowAutoStart = allowSkipPrompt && Settings.canDrawOverlays(context)

        autoStartNotify?.title = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot
            else R.string.autostart_prompt_at_boot
        )
        autoStartNotify?.summaryOn = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_on
            else R.string.autostart_prompt_at_boot_on
        )
        autoStartNotify?.summaryOff = getString(
            if(allowAutoStart) R.string.autostart_service_at_boot_off
            else R.string.autostart_prompt_at_boot_off
        )

        fun getIcon(allowed: Boolean) = context?.let {
            ContextCompat.getDrawable(it,
                if(allowed) R.drawable.ic_twotone_check_circle_24dp
                else R.drawable.ic_twotone_warning_24dp
            )
        }

        fun getSummary(allowed: Boolean) = context?.getString(
            if(allowed) R.string.permission_allowed
            else R.string.permission_not_allowed
        )

        permSkipPrompt?.summary = getSummary(allowSkipPrompt)
        permAutoStart?.summary = getSummary(allowAutoStart)
        permSkipPrompt?.icon = getIcon(allowSkipPrompt)
        permAutoStart?.icon = getIcon(allowAutoStart)
        permRestartSetup?.isVisible = !allowSkipPrompt || !allowAutoStart
    }

    companion object {
        fun newInstance(): SettingsMiscFragment {
            return SettingsMiscFragment()
        }
    }
}