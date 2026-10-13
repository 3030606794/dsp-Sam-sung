package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.os.Bundle
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import me.timschneeberger.rootlessjamesdsp.utils.isRoot

class SettingsAboutFragment : SettingsBaseFragment() {

    private val version by lazy { findPreference<Preference>(getString(R.string.key_credits_version)) }
    private val buildInfo by lazy { findPreference<Preference>(getString(R.string.key_credits_build_info)) }
    private val googlePlay by lazy { findPreference<Preference>(getString(R.string.key_credits_google_play)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_about_preferences, rootKey)

        version?.summary = BuildConfig.VERSION_NAME
        buildInfo?.isVisible = BuildConfig.DEBUG || BuildConfig.PREVIEW || isPlugin()
        val type = if(BuildConfig.PREVIEW)
            "Preview"
        else if(BuildConfig.DEBUG)
            "Debug"
        else
            "Release"

        buildInfo?.summary = "$type build (${BuildConfig.FLAVOR_dependencies}) @${BuildConfig.COMMIT_SHA} (compiled at ${BuildConfig.BUILD_TIME})"

        googlePlay?.isVisible = !isRoot()
    }

    companion object {
        fun newInstance(): SettingsAboutFragment {
            return SettingsAboutFragment()
        }
    }
}
