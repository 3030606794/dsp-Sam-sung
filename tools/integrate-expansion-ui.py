from pathlib import Path
root=Path(__file__).resolve().parents[1]
b=root/'app/src/main/java/me/timschneeberger/rootlessjamesdsp'
def edit(path,fn):
    p=b/path;p.write_text(fn(p.read_text(encoding='utf-8')),encoding='utf-8')
def replace(text,old,new):
    assert old in text,old[:100]
    return text.replace(old,new)
def activity(t):
    t=replace(t,'import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectPreferenceFragment','import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectPreferenceFragment\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectPreferenceFragment')
    t=t.replace('setOf(KIND_DYNAMIC_BASS, KIND_CREATIVE)','setOf(KIND_DYNAMIC_BASS, KIND_CREATIVE, KIND_EXPANSION)')
    t=t.replace('intent.getStringExtra(EXTRA_KIND) != KIND_CREATIVE','intent.getStringExtra(EXTRA_KIND) !in setOf(KIND_CREATIVE, KIND_EXPANSION)')
    t=replace(t,'            KIND_CREATIVE -> {','            KIND_EXPANSION -> {\n                val kind = runCatching { ExpansionEffectsKind.valueOf(intent.getStringExtra(EXTRA_EXPANSION).orEmpty()) }.getOrNull()\n                if (kind == null) { finish(); return }\n                ExpansionEffectPreferenceFragment.newInstance(kind)\n            }\n            KIND_CREATIVE -> {')
    t=replace(t,'        private const val KIND_CREATIVE = "creative"','        private const val KIND_CREATIVE = "creative"\n        private const val KIND_EXPANSION = "expansion"\n        private const val EXTRA_EXPANSION = "professional.expansionKind"')
    t=replace(t,'        fun openCreative(context:', '        fun openExpansion(context: Context, kind: ExpansionEffectsKind, title: CharSequence? = kind.title) {\n            context.startActivity(intent(context, KIND_EXPANSION, title).putExtra(EXTRA_EXPANSION, kind.name))\n        }\n        fun openCreative(context:')
    return t
edit(Path('activity/ProfessionalEffectActivity.kt'),activity)
def engine(t):
    t=replace(t,'import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRuntime','import me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRuntime\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsController\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsParameters\nimport me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRuntime')
    t=replace(t,'    private var creativeEffectsNeedsDriver = false','    private var creativeEffectsNeedsDriver = false\n    @Volatile private var expansionEffectsCapability: Boolean? = null\n    private var expansionEffectsNeedsDriver = false')
    t=replace(t,'                creativeEffectsCapability = it.getParameterInt(CreativeEffectsParameters.CAPABILITY_QUERY) == CreativeEffectsParameters.SCHEMA','                creativeEffectsCapability = it.getParameterInt(CreativeEffectsParameters.CAPABILITY_QUERY) == CreativeEffectsParameters.SCHEMA\n                expansionEffectsCapability = it.getParameterInt(ExpansionEffectsParameters.CAPABILITY_QUERY) == ExpansionEffectsParameters.SCHEMA')
    t=replace(t,'            creativeEffectsCapability = null','            creativeEffectsCapability = null\n            expansionEffectsCapability = null')
    t=replace(t,'        val creativeEffectsOk = CreativeEffectsController.sync(context, creativeEffectsCapability == true, ::sendFusionParameter)','        val creativeEffectsOk = CreativeEffectsController.sync(context, creativeEffectsCapability == true, ::sendFusionParameter)\n        expansionEffectsNeedsDriver = expansionEffectsCapability != true && ExpansionEffectsKind.values().any {\n            ExpansionEffectsController.preferences(context, it).all[it.enableKey] == true\n        }\n        val expansionEffectsOk = ExpansionEffectsController.sync(context, expansionEffectsCapability == true, ::sendFusionParameter)')
    t=t.replace('dynamicPeqOk && creativeEffectsOk && balanceOk','dynamicPeqOk && creativeEffectsOk && expansionEffectsOk && balanceOk')
    t=replace(t,'        else if (creativeEffectsNeedsDriver) CreativeEffectsController.DRIVER_REQUIRED','        else if (creativeEffectsNeedsDriver) CreativeEffectsController.DRIVER_REQUIRED\n        else if (expansionEffectsNeedsDriver) ExpansionEffectsController.DRIVER_REQUIRED')
    insertion='''    fun expansionEffectsSupported(): Boolean? = if (engineClosed || effect == null) null else expansionEffectsCapability

    fun expansionCommand(parameter: Int, words: IntArray): Boolean = synchronized(engineLock) {
        if (engineClosed || expansionEffectsCapability != true) return@synchronized false
        sendFusionParameter(parameter, words)
    }

    fun expansionEffectsRuntime(kind: ExpansionEffectsKind): Pair<Any, ExpansionEffectsRuntime>? = synchronized(engineLock) {
        if (engineClosed || expansionEffectsCapability != true) return@synchronized null
        val live = effect ?: return@synchronized null
        val words = live.getParameterInts(kind.query, ExpansionEffectsParameters.RUNTIME_WORDS) ?: return@synchronized null
        val runtime = ExpansionEffectsParameters.decode(kind, words) ?: return@synchronized null
        if (effect !== live) return@synchronized null
        val expected = ExpansionEffectsController.packet(context, kind)
        if (fusionPackets[kind.parameter]?.contentEquals(expected) != true || runtime.enabled != (expected[1] == 1)) return@synchronized null
        live to runtime.copy(engineEnabled = runCatching { live.enabled }.getOrDefault(false))
    }

'''
    t=replace(t,'    fun creativeEffectsSupported(): Boolean?',insertion+'    fun creativeEffectsSupported(): Boolean?')
    return t
edit(Path('interop/JamesDspRemoteEngine.kt'),engine)
def presets(t):
    t=replace(t,'            check(me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRepository.flush(ctx))','            check(me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.flush(ctx)) { "新增音效参数尚未写入存储，请重试保存" }\n            check(me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRepository.flush(ctx))')
    t=replace(t,'        internal fun minimumVersionCode(context: Context): String = when {','        internal fun minimumVersionCode(context: Context): String = when {\n            me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind.values().any {\n                context.getSharedPreferences(it.prefsName, Context.MODE_PRIVATE).all.isNotEmpty()\n            } -> "1155"')
    t=replace(t,'            val creativeFiles =', '''            val expansionFiles = me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind.values().associateWith { kind ->
                files.firstOrNull { it.name == "${kind.prefsName}.xml" }
            }
            val expansionStates = expansionFiles.mapValues { (kind, file) ->
                file?.let { me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.read(kind, it) }
            }
            val creativeFiles =''')
    t=replace(t,'            creativeStates.forEach { (kind, values) ->','''            expansionStates.forEach { (kind, values) ->
                check(me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.replace(ctx, kind, values)) {
                    "${kind.title}设备配置保存失败"
                }
            }
            creativeStates.forEach { (kind, values) ->''')
    t=t.replace('f == dynamicPeqFile || f in creativeFiles.values','f == dynamicPeqFile || f in creativeFiles.values || f in expansionFiles.values')
    return t
edit(Path('model/preset/Preset.kt'),presets)
