package me.timschneeberger.rootlessjamesdsp.liveprog

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.appcompat.widget.PopupMenu
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.NonPersistentDatastore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** The existing EEL controls/store/protocol, exposed directly under the professional charts. */
internal class LiveprogProfessionalEditor(private val context: Context, private val group: PreferenceGroup,
    private val openImport: () -> Unit) : AutoCloseable {
    private val preferences = context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE)
    private val store = LiveprogParameterStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { work -> Thread(work, "liveprog-script-description").apply { isDaemon = true } }
    private val rows = ArrayList<Preference>()
    private val pending = linkedSetOf<String>()
    private var parser: EelParser? = null
    private var sourceHash: Int? = null
    private var knownScript: String? = null
    private data class LoadedScript(
        val parser: EelParser,
        val sourceHash: Int?,
        val initialValues: Map<String, Float>,
        val knownScript: String?,
    )
    private var closed = false
    private var building = false
    private var committing = false
    private var loading = false
    private var rowDataStore: NonPersistentDatastore? = null
    private val externalKeys = linkedSetOf<String>()
    private var flushScheduled = false
    private var loadToken = 0L
    private var targetPath = ""
    private var configurationIdentity = ""
    private var scriptInitialValues: Map<String, Float> = emptyMap()
    private var detailsExpanded = false
    private val detailsRows = ArrayList<Preference>()
    private var detailsToggle: LiveprogCompactRow? = null
    private val filePreference = group.findPreference<FileLibraryPreference>(context.getString(R.string.key_liveprog_file))
    private val nativeParameters = group.findPreference<Preference>(context.getString(R.string.key_liveprog_params))
    private val nativeEditor = group.findPreference<Preference>(context.getString(R.string.key_liveprog_edit))
    private val tools = LiveprogToolsRow(context, openImport,
        editScript = { nativeEditor?.performClick() },
        more = { anchor ->
            PopupMenu(context, anchor).apply {
                menu.add("独立参数页").apply {
                    isEnabled = nativeParameters?.isEnabled == true
                    setOnMenuItemClickListener { nativeParameters?.performClick(); true }
                }
                menu.add("脚本管理").setOnMenuItemClickListener { filePreference?.showDialog(); true }
                menu.add("${if (detailsExpanded) "收起" else "展开"}脚本说明").setOnMenuItemClickListener { toggleDetails(); true }
            }.show()
        }, canEdit = { parser?.contents != null })
    var onChanged: ((String?) -> Unit)? = null
    private val flush = Runnable { flushParameters() }
    private val reloadTask = Runnable { reload() }
    private val synchronizeTask = Runnable { synchronizeControls() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (closed || committing) return@OnSharedPreferenceChangeListener
        when (key) {
            context.getString(R.string.key_liveprog_file), null -> {
                handler.removeCallbacks(reloadTask); handler.post(reloadTask)
            }
            context.getString(R.string.key_liveprog_enable) -> onChanged?.invoke(null)
            else -> if (key.startsWith("liveprog_parameter_v1:$configurationIdentity:") && !building) {
                externalKeys += key.substringAfterLast(':')
                handler.removeCallbacks(synchronizeTask); handler.post(synchronizeTask)
            }
        }
    }

    init {
        (group as? SwitchPreferenceGroup)?.setChildVisibilityFilter { row ->
            row !== nativeParameters && row !== nativeEditor && (row !in detailsRows || detailsExpanded)
        }
        filePreference?.apply {
            title = "当前 DSP 脚本"; layoutResource = R.layout.preference_liveprog_compact_row
            widgetLayoutResource = 0; isIconSpaceReserved = false; icon = null; isSingleLineTitle = false
        }
        // Retain the native entry objects and callbacks in Tools, without duplicate full rows.
        nativeParameters?.isVisible = false; nativeEditor?.isVisible = false
        tools.key = "liveprog_compact_tools"; tools.order = 10; group.addPreference(tools)
        preferences.registerOnSharedPreferenceChangeListener(listener); reload()
    }

    fun reload(invalidateSource: Boolean = false) {
        if (closed) return
        flushParameters()
        val selected = preferences.getString(context.getString(R.string.key_liveprog_file), "").orEmpty()
        val path = if (selected.isBlank()) "" else FileLibraryPreference.createFullPathCompat(context, selected)
        val switched = targetPath != path || invalidateSource
        targetPath = path
        if (switched) {
            // A selected path must never be paired with the previous script's live controls.
            detailsExpanded = false; loading = path.isNotBlank()
            parser = null; sourceHash = null; knownScript = null; scriptInitialValues = emptyMap()
            configurationIdentity = "loading:$path"
            externalKeys.clear(); handler.removeCallbacks(synchronizeTask)
            rebuildRows(); onChanged?.invoke(null)
        }
        val token = ++loadToken
        loader.execute {
            val result = runCatching {
                val loaded = EelParser().apply { if (path.isNotBlank()) load(path) }
                // Capture source literals before applying saved values, for undeclared defaults.
                val initial = loaded.properties.filterIsInstance<EelNumberRangeProperty<*>>()
                    .associate { it.key to it.value.toFloat() }
                store.applyTo(loaded)
                val source = loaded.contents.orEmpty()
                val hash = if (source.isNotBlank()) LiveprogPcmTelemetry.sourceHash(
                    BuiltinLiveprogRuntime.prepare(context.applicationContext, File(path).name, source)) else null
                // Source identity changes only on reload, never on a slider event.
                val name = File(path).name
                val known = name.takeIf {
                    source.isNotBlank() && BuiltinLiveprogMetadata.identifiedTitle(name, source) != null
                }
                LoadedScript(loaded, hash, initial, known)
            }
            handler.post {
                if (closed || token != loadToken || targetPath != path) return@post
                // Preserve edits committed while the same-script background read was in flight.
                flushParameters()
                result.onSuccess { (loaded, hash, initial, known) ->
                    store.applyTo(loaded); loading = false
                    parser = loaded; sourceHash = hash; knownScript = known; scriptInitialValues = initial
                    nativeEditor?.isEnabled = loaded.contents != null
                    nativeParameters?.isEnabled = loaded.properties.isNotEmpty()
                    configurationIdentity = if (loaded.contents != null) LiveprogParameterStore.identity(path, loaded.contents!!) else "empty"
                    rebuildRows(); onChanged?.invoke(null)
                }.onFailure {
                    loading = false; parser = null; sourceHash = null; knownScript = null; scriptInitialValues = emptyMap(); configurationIdentity = "unreadable:$path"
                    nativeEditor?.isEnabled = false; nativeParameters?.isEnabled = false
                    rebuildRows(); onChanged?.invoke(null)
                }
            }
        }
    }

    private fun rebuildRows() {
        building = true
        rows.forEach(group::removePreference); rows.clear(); detailsRows.clear(); detailsToggle = null
        tools.refresh()
        val loaded = parser
        val source = loaded?.contents.orEmpty()
        fun add(row: Preference) { row.order = 100 + rows.size; row.isIconSpaceReserved = false; group.addPreference(row); rows += row }
        // Show actionable empty/error states; routine explanatory copy lives in the folded section.
        if (targetPath.isBlank() || source.isBlank() || loaded?.properties.isNullOrEmpty()) add(LiveprogCompactRow(context).apply {
            key = "liveprog_inline_state"; isSelectable = false
            title = when {
                targetPath.isBlank() -> "请选择或导入 DSP 脚本"
                loading -> "正在读取当前脚本参数…"
                source.isBlank() -> "无法读取脚本，请在编辑器检查文件"
                else -> "此脚本没有声明可调参数"
            }
        })
        val dataStore = NonPersistentDatastore().apply {
            setOnPreferenceChanged(object : NonPersistentDatastore.OnPreferenceChanged {
                override fun onFloatPreferenceChanged(key: String, value: Float) {
                    if (building || closed) return
                    val rawKey = rawKey(key)
                    val base = loaded?.properties?.find { it.key == rawKey }
                    if (base is EelListProperty) return
                    @Suppress("UNCHECKED_CAST")
                    val property = base as? EelNumberRangeProperty<Float> ?: return
                    property.value = property.normalize(value)
                    parameterChanged(rawKey)
                }
            })
        }
        rowDataStore = dataStore
        loaded?.properties?.forEach { property ->
            val controlKey = INLINE_PREFIX + property.key
            when (property) {
                is EelListProperty -> dataStore.seedString(controlKey, property.value.toString())
                is EelNumberRangeProperty<*> -> dataStore.seedFloat(controlKey, property.value.toFloat())
            }
            when (property) {
                is EelListProperty -> if (property.options.size in 2..3) add(LiveprogCompactChoices(context,
                    property.options, current = { property.value }, choose = { value ->
                        if (!building && !closed) { property.value = property.validateRange(value); parameterChanged(property.key) }
                    }).apply { key = INLINE_PREFIX + property.key; title = property.description })
                else add(LiveprogCompactDropdown(context).apply {
                    key = INLINE_PREFIX + property.key; title = property.description
                    preferenceDataStore = dataStore
                    entries = property.options.toTypedArray()
                    entryValues = property.options.indices.map { it.toString() }.toTypedArray()
                    setDefaultValue(property.value.toString())
                    setValueSilently(property.value.toString())
                    setOnPreferenceChangeListener { _, newValue ->
                        if (building || closed) false else {
                            val value = (newValue as? String)?.toIntOrNull()
                            if (value == null || value !in property.options.indices) false else {
                                property.value = property.validateRange(value)
                                parameterChanged(property.key); true
                            }
                        }
                    }
                })
                is EelNumberRangeProperty<*> -> add(MaterialSeekbarPreference(context).apply {
                    key = INLINE_PREFIX + property.key; title = property.description
                    preferenceDataStore = dataStore
                    mPrecision = property.precision
                    setRange(property.minimum.toFloat(), property.maximum.toFloat())
                    valueNormalizer = property::normalize
                    useDspCompactLayout = true
                    thumbResetValueOverride = property.default?.toFloat() ?: scriptInitialValues[property.key]
                    valueLabelOverride = { "%.${mPrecision}f".format(Locale.ROOT, it) }
                    setUpdatesContinuously(true); setShowSeekBarValue(true); setDefaultValue(property.value)
                })
            }
        }
        if (loaded?.properties?.any { it.hasDefault() } == true) add(LiveprogCompactRow(context).apply {
            key = "liveprog_inline_reset"; title = "恢复当前脚本参数默认值"; actionLabel = "恢复"
            setOnPreferenceClickListener {
                loaded.properties.filter { it.hasDefault() }.forEach { it.restoreDefaults(); pending += it.key }
                flushParameters(); rebuildRows(); onChanged?.invoke(null); true
            }
        })
        detailsToggle = LiveprogCompactRow(context).apply {
            key = "liveprog_compact_details_toggle"; title = "脚本说明"
            actionLabel = if (detailsExpanded) "收起" else "展开"
            setOnPreferenceClickListener { toggleDetails(); true }
        }.also(::add)
        fun note(key: String, title: String, text: String) {
            add(LiveprogCompactRow(context).apply {
                this.key = key; this.title = title; summary = text
                isSelectable = false; isVisible = detailsExpanded
            }.also(detailsRows::add))
        }
        note("liveprog_inline_description", loaded?.description ?: "脚本参数", when {
            targetPath.isBlank() -> "选择或导入 .eel 脚本；原有脚本编辑入口保留。"
            source.isBlank() -> "无法读取所选脚本，请在原脚本编辑器中检查文件。"
            loaded?.properties.isNullOrEmpty() -> "此脚本没有可调参数声明；上方显示程序作用说明，仍可在原编辑器中修改。"
            else -> "${loaded!!.properties.size} 个原生参数 · 调哪里看哪里 · 参数保存独立于脚本正文"
        })
        note("liveprog_compact_control_help", "调节与复位",
            "双击滑块球，只恢复当前一项的脚本默认值；脚本未声明默认值时，使用脚本文件的初始值。点击右侧数值框可精确输入。恢复按钮会一起恢复当前脚本声明了默认值的参数；不会改写脚本文件或其他模块。")
        if (source.isNotBlank()) {
            val runtime = runCatching { BuiltinLiveprogRuntime.prepare(context, File(targetPath).name, source) }.getOrDefault(source)
            if (!Regex("(?m)^\\s*@slider\\b").containsMatchIn(runtime) && !loaded?.properties.isNullOrEmpty())
                note("liveprog_compact_legacy_details", "参数应用说明", context.getString(R.string.liveprog_legacy_parameter_notice))
            val metadata = source.lineSequence().filter { line ->
                line.startsWith("author:") || line.startsWith("tags:") || line.startsWith("//author:") || line.startsWith("//tags:")
            }.take(6).joinToString("\n").trim()
            if (metadata.isNotBlank()) note("liveprog_compact_original_details", "脚本原有说明", metadata)
        }
        building = false
    }

    private fun toggleDetails() {
        detailsExpanded = !detailsExpanded
        detailsRows.forEach { it.isVisible = detailsExpanded }
        detailsToggle?.actionLabel = if (detailsExpanded) "收起" else "展开"
    }

    private fun parameterChanged(key: String) {
        pending += key
        onChanged?.invoke(key)
        if (!flushScheduled) { flushScheduled = true; handler.postDelayed(flush, 40L) }
    }

    private fun synchronizeControls() {
        if (closed || building) return
        if (pending.isNotEmpty()) flushParameters()
        val loaded = parser ?: return
        val changed = externalKeys.toList(); externalKeys.clear()
        store.applyTo(loaded)
        loaded.properties.forEach { property ->
            val row = rows.firstOrNull { it.key == INLINE_PREFIX + property.key }
            when (property) {
                is EelListProperty -> {
                    val value = property.value.toString()
                    rowDataStore?.seedString(INLINE_PREFIX + property.key, value)
                    when (row) {
                        is LiveprogCompactDropdown -> row.setValueSilently(value)
                        is LiveprogCompactChoices -> row.refreshSelection()
                    }
                }
                is EelNumberRangeProperty<*> -> {
                    val value = property.value.toFloat()
                    rowDataStore?.seedFloat(INLINE_PREFIX + property.key, value)
                    (row as? MaterialSeekbarPreference)?.refreshDisplayedValue(value)
                }
            }
        }
        onChanged?.invoke(changed.singleOrNull()?.takeIf { key -> loaded.properties.any { it.key == key } })
    }

    fun flushParameters() {
        handler.removeCallbacks(flush)
        flushScheduled = false
        val loaded = parser ?: return
        if (pending.isEmpty()) return
        val properties = loaded.properties.filter { it.key in pending }
        pending.clear()
        committing = true
        val saved = try { store.save(loaded, properties) } finally { committing = false }
        if (!saved) { context.toast(R.string.liveprog_parameters_save_failed); return }
        context.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETERS).apply {
            putExtra(Constants.EXTRA_LIVEPROG_PATH, loaded.path)
        })
    }

    fun configuration(rate: Int, actualRate: Boolean): LiveprogChartConfiguration {
        val loaded = parser
        val source = loaded?.contents.orEmpty()
        val name = File(targetPath).name
        val parameters = loaded?.properties?.mapNotNull { property ->
            (property as? EelNumberRangeProperty<*>)?.let {
                LiveprogChartParameter(it.key, it.description, it.value.toDouble(), it.minimum.toDouble(), it.maximum.toDouble(),
                    it.default?.toDouble(), (it as? EelListProperty)?.options.orEmpty())
            }
        }.orEmpty()
        return LiveprogChartConfiguration(configurationIdentity, targetPath, name, loaded?.description ?: "DSP 脚本处理",
            source, parameters, knownScript, rate, actualRate,
            preferences.getBoolean(context.getString(R.string.key_liveprog_enable), false), sourceHash)
    }

    override fun close() {
        if (closed) return
        flushParameters(); closed = true; loadToken++
        handler.removeCallbacksAndMessages(null); loader.shutdownNow()
        preferences.unregisterOnSharedPreferenceChangeListener(listener); onChanged = null
        group.removePreference(tools)
        (group as? SwitchPreferenceGroup)?.setChildVisibilityFilter(null)
    }
    companion object {
        const val INLINE_PREFIX = "liveprog_inline:"
        fun rawKey(key: String): String = key.removePrefix(INLINE_PREFIX)
    }
}
