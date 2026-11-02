package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogParamsActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.liveprog.EelListProperty
import me.timschneeberger.rootlessjamesdsp.liveprog.EelNumberRangeProperty
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.liveprog.EelBaseProperty
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogParameterStore
import me.timschneeberger.rootlessjamesdsp.liveprog.BuiltinLiveprogRuntime
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.NonPersistentDatastore
import timber.log.Timber
import java.io.File
import java.util.Locale

class LiveprogParamsFragment : PreferenceFragmentCompat(), NonPersistentDatastore.OnPreferenceChanged {
    private val eelParser = EelParser()
    private val dataStore = NonPersistentDatastore()
    private val handler = Handler(Looper.getMainLooper())
    private val pending = linkedSetOf<String>()
    private val rangeControls = HashMap<String, MaterialSeekbarPreference>()
    private val listControls = HashMap<String, DropDownPreference>()
    private var scriptInitialValues: Map<String, Float> = emptyMap()
    private var writingParameters = false
    private var flushScheduled = false
    private var observingPreferences = false
    private val preferences by lazy {
        requireContext().getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
    }
    private val flushTask = Runnable { flushParameters() }
    private val synchronizeTask = Runnable { synchronizeValues() }
    private val reloadTask = Runnable { reload() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (writingParameters || !isCreated) return@OnSharedPreferenceChangeListener
        if (key == null || key == getString(R.string.key_liveprog_file)) {
            handler.removeCallbacks(reloadTask)
            handler.post(reloadTask)
        } else if (key.startsWith("liveprog_parameter_v1:")) {
            handler.removeCallbacks(synchronizeTask)
            handler.post(synchronizeTask)
        }
    }
    private var isCreated = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        (savedInstanceState?.getString(BUNDLE_TARGET_FILE)
            ?: requireArguments().getString(BUNDLE_TARGET_FILE))
            ?.let { eelParser.load(it) }

        if(!eelParser.isFileLoaded) {
            requireContext().toast(R.string.liveprog_not_found)
            return
        }

        captureInitialValues()
        LiveprogParameterStore(requireContext()).applyTo(eelParser)

        requireActivity().title = eelParser.description
        updateResetMenuItem()

        dataStore.setOnPreferenceChanged(this)
        preferenceManager.preferenceDataStore = dataStore
        preferenceScreen = createPreferences()

        isCreated = true
    }

    override fun onResume() {
        super.onResume()
        if (!isCreated) return
        if (!observingPreferences) {
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            observingPreferences = true
        }
        // A second editor, import or script edit may have changed values while this page was away.
        reload()
    }

    override fun onPause() {
        flushParameters()
        if (observingPreferences) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            observingPreferences = false
        }
        handler.removeCallbacks(synchronizeTask)
        handler.removeCallbacks(reloadTask)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState.apply {
            putString(BUNDLE_TARGET_FILE, eelParser.path)
        })
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    }

    @Suppress("UNCHECKED_CAST")
    override fun onFloatPreferenceChanged(key: String, value: Float) {
        if(!isCreated) {
            Timber.d("onFloatPreferenceChanged not yet ready")
            return
        }

        Timber.d("onFloatPreferenceChanged: $key=$value")

        val baseProp = eelParser.properties.find { it.key == key }
        if(baseProp is EelListProperty)
            return

        (baseProp as? EelNumberRangeProperty<Float>)?.apply {
            this.value = normalize(value)
            pending += key
            // Keep the original parameter update protocol, shared with the inline editor.
            if (!flushScheduled) {
                flushScheduled = true
                handler.postDelayed(flushTask, 40L)
            }
        } ?: return
    }

    private fun createPreferences(): PreferenceScreen {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        dataStore.clear()
        rangeControls.clear()
        listControls.clear()

        eelParser.properties.forEach { prop ->
            if(prop is EelListProperty) {
                val selected = prop.validateRange(prop.value).coerceIn(prop.options.indices)
                dataStore.seedString(prop.key, selected.toString())
                DropDownPreference(requireContext()).apply {
                    key = prop.key
                    title = prop.description
                    entries = prop.options.toTypedArray()
                    entryValues = (0 until(prop.options.size)).toList().map { it.toString() }.toTypedArray()
                    setDefaultValue(selected.toString())
                    setValueSilently(selected.toString())
                    setOnPreferenceChangeListener { _, newValue ->
                        if(!isCreated) {
                            Timber.d("onPreferenceChangeListener not yet ready")
                            return@setOnPreferenceChangeListener false
                        }

                        val currentProp = eelParser.properties.find { it.key == prop.key } as? EelListProperty
                        currentProp ?: return@setOnPreferenceChangeListener false

                        Timber.d("List item with value $newValue selected")

                        val selectedIndex = (newValue as? String)?.toIntOrNull()
                            ?: return@setOnPreferenceChangeListener false
                        if (selectedIndex !in currentProp.options.indices)
                            return@setOnPreferenceChangeListener false
                        flushParameters()
                        val previous = currentProp.value
                        currentProp.value = currentProp.validateRange(selectedIndex)
                        if (saveAndApply(listOf(currentProp))) true else {
                            currentProp.value = previous
                            false
                        }
                    }
                }.also { listControls[prop.key] = it }.let(screen::addPreference)
            }
            else if(prop is EelNumberRangeProperty<*>) {
                dataStore.seedFloat(prop.key, prop.value.toFloat())
                MaterialSeekbarPreference(requireContext()).apply {
                    key = prop.key
                    title = prop.description
                    mPrecision = prop.precision
                    setRange(prop.minimum.toFloat(), prop.maximum.toFloat())
                    // EEL ranges need not contain a whole number of steps (for example 1..4000/20).
                    // Quantize the value directly instead of imposing Material Slider's stricter grid.
                    valueNormalizer = prop::normalize
                    thumbResetValueOverride = prop.default?.toFloat() ?: scriptInitialValues[prop.key]
                    valueLabelOverride = { "%.${mPrecision}f".format(Locale.ROOT, it) }
                    setUpdatesContinuously(true)
                    setShowSeekBarValue(true)
                    setDefaultValue(prop.value)
                }.also { rangeControls[prop.key] = it }.let(screen::addPreference)
            }
        }

        val source = eelParser.contents.orEmpty()
        val runtime = BuiltinLiveprogRuntime.prepare(requireContext(), File(eelParser.path!!).name, source)
        if (!Regex("(?m)^\\s*@slider\\b").containsMatchIn(runtime)) {
            screen.addPreference(Preference(requireContext()).apply {
                summary = getString(R.string.liveprog_legacy_parameter_notice)
                isSelectable = false
            })
        }

        return screen
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView.itemAnimator = null // Fix to prevent RecyclerView crash if group is toggled rapidly
        recyclerView.isNestedScrollingEnabled = false
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        this.setDivider(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED).apply {
            addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
            addAction(Constants.ACTION_LIVEPROG_PARAMETER_RESULT)
        })
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        flushParameters()
        handler.removeCallbacksAndMessages(null)
        if (observingPreferences) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            observingPreferences = false
        }
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                Constants.ACTION_PRESET_LOADED -> reload()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> reload()
                Constants.ACTION_LIVEPROG_PARAMETER_RESULT -> if (intent.getStringExtra(Constants.EXTRA_LIVEPROG_PATH) == eelParser.path) {
                    intent.getStringExtra(Constants.EXTRA_LIVEPROG_ERROR)?.let { requireContext().toast(it, false) }
                }
            }
        }
    }

    private fun updateResetMenuItem() {
        val act = requireActivity() as LiveprogParamsActivity
        act.setResetEnabled(eelParser.canLoadDefaults())
        act.setResetVisible(eelParser.hasDefaults())
    }

    private fun reload() {
        if (!isAdded || !isCreated) return
        flushParameters()
        val newPath = context?.let { ctx ->
            val selected = PreferenceCache.uncachedGet(
                ctx,
                Constants.PREF_LIVEPROG,
                R.string.key_liveprog_file,
                ""
            )
            if (selected.isBlank()) null else ctx.getExternalFilesDir(null)!!.resolve(selected).absolutePath
        }

        val path = newPath ?: run {
            // Clearing the selected script must not leave this editor showing the old one.
            activity?.finish()
            return
        }
        // Always recover file literals before applying saved choices, including reset defaults.
        eelParser.load(path, true)
        captureInitialValues()
        LiveprogParameterStore(requireContext()).applyTo(eelParser)

        activity?.title = eelParser.description
        if(eelParser.properties.isEmpty()) {
            activity?.finish()
            return
        }

        updateResetMenuItem()

        isCreated = false
        preferenceScreen = createPreferences()
        isCreated = true
    }

    fun restoreDefaults() {
        flushParameters()
        val defaults = eelParser.properties.filter { it.hasDefault() }
        defaults.forEach { it.restoreDefaults() }
        saveAndApply(defaults)
        reload()
    }

    private fun captureInitialValues() {
        scriptInitialValues = eelParser.properties.filterIsInstance<EelNumberRangeProperty<*>>()
            .associate { it.key to it.value.toFloat() }
    }

    private fun synchronizeValues() {
        if (!isAdded || !isCreated || pending.isNotEmpty()) return
        LiveprogParameterStore(requireContext()).applyTo(eelParser)
        isCreated = false
        try {
            eelParser.properties.forEach { property ->
                when (property) {
                    is EelListProperty -> {
                        val selected = property.value.toString()
                        dataStore.seedString(property.key, selected)
                        listControls[property.key]?.setValueSilently(selected)
                    }
                    is EelNumberRangeProperty<*> -> {
                        val value = property.value.toFloat()
                        dataStore.seedFloat(property.key, value)
                        rangeControls[property.key]?.refreshDisplayedValue(value)
                    }
                }
            }
        } finally {
            isCreated = true
        }
        updateResetMenuItem()
    }

    private fun flushParameters() {
        handler.removeCallbacks(flushTask)
        flushScheduled = false
        if (pending.isEmpty() || !isAdded) return
        val changed = eelParser.properties.filter { it.key in pending }
        pending.clear()
        saveAndApply(changed)
    }

    private fun saveAndApply(properties: Collection<EelBaseProperty>): Boolean {
        writingParameters = true
        val saved = try {
            LiveprogParameterStore(requireContext()).save(eelParser, properties)
        } finally {
            writingParameters = false
        }
        if (!saved) {
            requireContext().toast(R.string.liveprog_parameters_save_failed)
            return false
        }
        updateResetMenuItem()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETERS).apply {
            putExtra(Constants.EXTRA_LIVEPROG_PATH, eelParser.path)
        })
        return true
    }

    companion object {
        private const val BUNDLE_TARGET_FILE = "TargetFile"

        fun newInstance(targetFile: String): LiveprogParamsFragment {
            val fragment = LiveprogParamsFragment()

            val args = Bundle()
            args.putString(BUNDLE_TARGET_FILE, targetFile)
            fragment.arguments = args
            return fragment
        }
    }
}
