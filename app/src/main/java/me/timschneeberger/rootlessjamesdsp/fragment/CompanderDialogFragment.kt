package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.core.content.edit
import androidx.preference.PreferenceDialogFragmentCompat
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceCompanderDialogBinding
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.view.CompanderSurface
import me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters

class CompanderDialogFragment : PreferenceDialogFragmentCompat() {

    private var compander: CompanderSurface? = null
    private lateinit var mLevels: DoubleArray
    private lateinit var mOldSetting: String
    private lateinit var mFrequencies: DoubleArray
    private lateinit var mLastApplied: String
    private var stale=false
    private val settingsListener=SharedPreferences.OnSharedPreferenceChangeListener { prefs,_ ->
        val raw=prefs.all[preference.key]
        if(CompanderParameters.curveTakenOver(prefs.all) || (raw!=null && raw!=mLastApplied))stale=true
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                Constants.ACTION_PRESET_LOADED -> {stale=true;dismiss()}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mOldSetting = savedInstanceState?.getString("oldSetting")
            ?: (preference.sharedPreferences?.all?.get(preference.key) as? String)
            ?: (preference as CompanderPreference).initialValue

        val curve=CompanderParameters.parseCurve(mOldSetting) ?: CompanderParameters.parseCurve(CompanderParameters.DEFAULT_CURVE)!!
        mLevels=savedInstanceState?.getDoubleArray("levels")?.takeIf {it.size==7 && it.all {n->n.isFinite() && n in -1.2..1.2}} ?: curve.coefficients.toDoubleArray()
        mFrequencies=curve.frequencies.toDoubleArray()
        mLastApplied=savedInstanceState?.getString("lastApplied") ?: mOldSetting
        stale=savedInstanceState?.getBoolean("stale") ?: false
        preference.sharedPreferences?.registerOnSharedPreferenceChangeListener(settingsListener)

        requireContext().registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
    }


    // FIXME Equalizer is impossible to use with accessibility tools
    @SuppressLint("ClickableViewAccessibility")
    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        val binding = PreferenceCompanderDialogBinding.bind(view)
        compander = binding.companderSurface
        compander!!.setFrequencies(mFrequencies)
        compander!!.areKnobsVisible = true
        compander!!.setOnTouchListener { v, event ->
            if(!canModify())return@setOnTouchListener true
            val minDb = compander!!.minDb
            val maxDb = compander!!.maxDb
            val band = compander!!.findClosest(event.x)
            var level = event.y / v.height * (minDb - maxDb) - minDb
            if (level > -0.02 && level < 0.0) {
                level = 0.0
            } else if (level > maxDb) {
                level = maxDb
            } else if (level < minDb) {
                level = minDb
            }

            updateBand(band, level)
            applyCurrentSetting()
            true
        }

        mLevels.forEachIndexed(compander!!::setBand)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState.apply {
            putDoubleArray("levels", mLevels)
            putString("oldSetting", mOldSetting)
            putString("lastApplied",mLastApplied)
            putBoolean("stale",stale)
        })
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if(!canModify())return
        if (positiveResult) {
            val array = mFrequencies + mLevels
            val value = array.joinToString(";")
            if (preference.callChangeListener(value)) {
                applySetting(value)
            }
        } else {
            applySetting(mOldSetting)
        }
    }

    private fun applyCurrentSetting() {
        applySetting((mFrequencies + mLevels).joinToString(";"))
    }

    private fun canModify(): Boolean {
        val prefs=preference.sharedPreferences ?: return false
        val raw=prefs.all[preference.key]
        return !stale && !CompanderParameters.curveTakenOver(prefs.all) && (raw==null || raw==mLastApplied)
    }
    private fun applySetting(value: String) {
        if(!canModify() || CompanderParameters.parseCurve(value)==null)return
        mLastApplied=value
        preference.preferenceManager.sharedPreferences?.edit(commit = true) {
            putString(preference.key, value)
        }
        (preference as CompanderPreference).updateFromPreferences()
    }

    private fun updateBand(i: Int, gain: Double) {
        mLevels[i] = gain
        compander!!.setBand(i, gain)
    }

    override fun onDestroy() {
        preference.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
        compander = null
    }

    companion object {
        private const val BUNDLE_KEY = "key"

        fun newInstance(key: String): CompanderDialogFragment {
            val fragment = CompanderDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(BUNDLE_KEY, key)
            }
            return fragment
        }
    }
}
