package me.timschneeberger.rootlessjamesdsp.activity

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegateImpl
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.AppFontScale
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

abstract class BaseActivity :
    AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    KoinComponent,
    ThemingDelegate by ThemingDelegateImpl() {

    /* Preferences */
    protected val prefsApp: Preferences.App by inject()
    protected val prefsVar: Preferences.Var by inject()

    protected open val disableAppTheme = false
    private var appliedFontPercent = AppFontScale.DEFAULT_PERCENT
    private var fontRecreationRequested = false

    protected val app
        get() = application as MainApplication

    override fun attachBaseContext(newBase: Context) {
        appliedFontPercent = AppFontScale.percent(newBase)
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if(!disableAppTheme)
            applyAppTheme(this)
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshFontSizeIfNeeded()

        // Exclude from recents if enabled
        getSystemService<ActivityManager>()?.appTasks?.takeIf { it.isNotEmpty() }?.forEach {
            it.setExcludeFromRecents(prefsApp.get<Boolean>(R.string.key_exclude_app_from_recents))
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || key == AppFontScale.KEY) refreshFontSizeIfNeeded()
        if(key == getString(R.string.key_appearance_pure_black) ||
            key == getString(R.string.key_appearance_app_theme)) {
            if(!disableAppTheme)
                ActivityCompat.recreate(this)
        }
    }

    private fun refreshFontSizeIfNeeded() {
        if (!fontRecreationRequested && AppFontScale.percent(this) != appliedFontPercent &&
            !isFinishing && !isDestroyed) {
            fontRecreationRequested = true
            ActivityCompat.recreate(this)
        }
    }
}
