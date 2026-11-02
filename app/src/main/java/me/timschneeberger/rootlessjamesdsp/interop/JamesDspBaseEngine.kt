package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fusion.DdcParameters
import me.timschneeberger.rootlessjamesdsp.fusion.OutputDefaults
import me.timschneeberger.rootlessjamesdsp.fusion.DdcVdc
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionParameters
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.liveprog.BuiltinLiveprogRuntime
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogParameterStore
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader

abstract class JamesDspBaseEngine(val context: Context, val callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : AutoCloseable {
    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    protected val engineLock = Any()
    private val syncQueue = PreferenceSyncQueue()
    private val deferredNamespaces = linkedSetOf<String>()
    private val convolutionStateLock = Any()
    @Volatile private var convolutionClosed=false
    @Volatile protected var convolutionRequestCurrent: () -> Boolean = { !convolutionClosed }
    private data class IrRequest(val path: String,val modified: Long,val bytes: Long,val rate: Int,val mode: Int,
        val legacy: String,val options: ConvolutionIrPreprocessor.Options, val advanced: ConvolutionAdvancedParameters.IrOptions, val deepAnalysis: Boolean)
    private data class CachedIr(val request: IrRequest,val samples: FloatArray,val prepared: ConvolutionController.PreparedIr)
    private var convolutionCache: CachedIr?=null
    private var convolutionReferenceGain: Pair<IrRequest,Double>?=null
    private data class DdcFileIdentity(val path: String, val modified: Long, val bytes: Long)
    private var cachedDdcFile: Pair<DdcFileIdentity, DdcVdc.Parsed>? = null
    private var cachedDdcDocument: DdcVdc.Parsed? = null
    protected val cache = PreferenceCache(context)
    private var activeLiveprogIdentity: String? = null
    private val appliedLiveprogParameters = mutableMapOf<String, Double>()
    protected var liveprogContentRejected = false

    override fun close() {
        syncQueue.close()
        synchronized(convolutionStateLock) {
            convolutionClosed=true
            convolutionCache=null
            convolutionReferenceGain=null
        }
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        if (!syncQueue.request(forceUpdateNamespaces)) return
        syncScope.launch {
            while (true) {
                // A preset writes multiple keys in one edit. Apply one complete snapshot off the UI thread.
                delay(40)
                val targets = syncQueue.take() ?: break
                try {
                    synchronized(engineLock) {
                        if (!convolutionClosed) {
                            deferredNamespaces.addAll(targets)
                            preparePreferenceSync(deferredNamespaces.toTypedArray())
                            if (beforePreferenceSync()) {
                                syncWithPreferencesNow(deferredNamespaces.toTypedArray())
                                deferredNamespaces.clear()
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Preference synchronization failed")
                }
            }
        }
    }

    protected open fun beforePreferenceSync(): Boolean = true
    /** Explicit reloads must also invalidate the parsed source, including a same-size replacement. */
    protected open fun preparePreferenceSync(forceUpdateNamespaces: Array<String>) {
        if (Constants.PREF_DDC in forceUpdateNamespaces) {
            cachedDdcFile = null
            cachedDdcDocument = null
        }
    }
    protected open fun afterPreferenceSync() {}
    protected open fun preferenceSyncFailed() {}
    /** A bad curve must not prevent a separately requested module shutdown. */
    protected open fun onRejectedCompander(enable: Boolean): Boolean = true

    fun clearCache() {
        synchronized(engineLock) {
            cache.clear()
            cachedDdcFile = null
            cachedDdcDocument = null
            onPreferenceCacheCleared()
        }
    }

    protected open fun onPreferenceCacheCleared() {}

    /** Reuse the already validated document when the remote engine needs its SHA/coefficients. */
    protected fun parseDdcSource(text: String): DdcVdc.Parsed =
        cachedDdcDocument?.takeIf { it.text == text } ?: DdcVdc.parse(text).also { cachedDdcDocument = it }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private fun syncWithPreferencesNow(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        run {
            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, OutputDefaults.POST_GAIN_DB)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, OutputDefaults.THRESHOLD_DB)
            val limiterRelease = cache.get(R.string.key_limiter_release, OutputDefaults.RELEASE_MS)

            cache.select(Constants.PREF_COMPANDER)
            val companderRaw=PreferenceCache.getPreferences(context,Constants.PREF_COMPANDER).all
            val compFoundationValid=me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters.validSavedFoundation(companderRaw) &&
                (!companderRaw.containsKey("compander_enable") || companderRaw["compander_enable"] is Boolean)
            val compEnabled = if(!companderRaw.containsKey("compander_enable") || companderRaw["compander_enable"] is Boolean)
                cache.get(R.string.key_compander_enable,false) else false
            val compTimeConst = if(!companderRaw.containsKey("compander_timeconstant") || companderRaw["compander_timeconstant"] is Float)
                cache.get(R.string.key_compander_timeconstant,.22f) else Float.NaN
            val compGranularity = if(!companderRaw.containsKey("compander_granularity") || companderRaw["compander_granularity"] is Float)
                cache.get(R.string.key_compander_granularity,2f).toInt() else -1
            val compTfTransforms = if(!companderRaw.containsKey("compander_tftransforms") || companderRaw["compander_tftransforms"] is String)
                cache.get(R.string.key_compander_tftransforms,"0").toIntOrNull() ?: -1 else -1
            val compResponse = if(!companderRaw.containsKey("compander_response") || companderRaw["compander_response"] is String)
                cache.get(R.string.key_compander_response,"95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0") else ""
            if(!compFoundationValid && !cache.changedNamespaces.contains(Constants.PREF_COMPANDER)) cache.changedNamespaces.add(Constants.PREF_COMPANDER)

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "0").toInt()
            val eqInterpolationMode = cache.get(R.string.key_eq_interpolation, "0").toInt()
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
            val geqEnabled = cache.get(R.string.key_geq_enable, false)
            val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = cache.get(R.string.key_reverb_preset, "0").toInt()

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = cache.get(R.string.key_crossfeed_mode, "0").toInt()

            cache.select(Constants.PREF_TUBE)
            val tubeEnabled = cache.get(R.string.key_tube_enable, false)
            val tubeDrive = cache.get(R.string.key_tube_drive, 2f)

            cache.select(Constants.PREF_DDC)
            val ddcValues=PreferenceCache.getPreferences(context,Constants.PREF_DDC).all
            val ddcFoundationValid=DdcParameters.validSavedFoundation(ddcValues)
            val ddcEnabled = ddcValues[DdcParameters.ENABLED] as? Boolean ?: false
            val ddcFile = ddcValues[DdcParameters.FILE] as? String ?: ""

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            // Runtime controls do not rebuild the IR: setConvolver caches only preprocessing inputs.
            val targets = (cache.changedNamespaces.toTypedArray() + (forceUpdateNamespaces ?: arrayOf()) + Constants.PREF_CONVOLVER + Constants.PREF_DDC).distinct()
            val failedNamespaces = mutableListOf<String>()
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain)
                    Constants.PREF_COMPANDER -> if(!compFoundationValid || me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters.parseCurve(compResponse)==null) {
                        // A rejected preset is not an AudioEffect transport failure.
                        // Keep the last valid configuration; the editor reports the bad curve.
                        Timber.e("Compander foundation rejected; retaining last valid configuration")
                        onRejectedCompander(compEnabled)
                    } else setCompander(compEnabled, compTimeConst, compGranularity, compTfTransforms, compResponse)
                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)
                    Constants.PREF_EQ -> setMultiEqualizer(eqEnabled, eqFilterType, eqInterpolationMode, eqBands)
                    Constants.PREF_GEQ -> setGraphicEq(geqEnabled, geqBands)
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> setCrossfeed(crossfeedEnabled, crossfeedMode)
                    Constants.PREF_TUBE -> setVacuumTube(tubeEnabled, tubeDrive)
                    Constants.PREF_DDC -> if(ddcFoundationValid)setVdc(ddcEnabled,ddcFile) else onRejectedDdc(ddcValues[DdcParameters.ENABLED]!=false,ddcFile,"DDC 开关或文件路径类型无效，保留上一有效校正")
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile) || liveprogContentRejected
                    Constants.PREF_CONVOLVER -> setConvolver(convolverEnabled, convolverFile, convolverMode, convolverAdvImp)
                    else -> true
                }

                if(!result) {
                    Timber.e("Failed to apply $it")
                    failedNamespaces.add(it)
                }
            }

            cache.markChangesAsCommitted()
            cache.changedNamespaces.addAll(failedNamespaces)
            if (failedNamespaces.isEmpty()) afterPreferenceSync() else preferenceSyncFailed()
            Timber.i("Preferences synchronized")
        }
    }

    fun setMultiEqualizer(enable: Boolean, filterType: Int, interpolationMode: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setFirEqualizer: malformed EQ string")
                return false
            }
            doubleArray[i] = number
        }

        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: String): Boolean
    {
        val curve=me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters.parseCurve(bands) ?: return false
        if(!timeConstant.isFinite() || timeConstant !in .06f.. .3f || granularity !in 0..4 || tfTransforms !in 0..3)return false
        val doubleArray=curve.values().toDoubleArray()
        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean
    {
        onDdcSource(vdcPath)
        if(!enable)return setVdcInternal(false, "") // OFF never depends on a file read or valid content.
        return try {
            val fullPath=FileLibraryPreference.createFullPathCompat(context,vdcPath)
            require(vdcPath.isNotBlank()) { "尚未选择 DDC 文件" }
            val file = File(fullPath)
            require(file.isFile) { "找不到所选 DDC 文件" }
            val identity = DdcFileIdentity(file.canonicalPath, file.lastModified(), file.length())
            val parsed = cachedDdcFile?.takeIf { it.first == identity }?.second
                ?: DdcVdc.read(file).also { cachedDdcFile = identity to it }
            cachedDdcDocument = parsed
            setVdcInternal(true,parsed.text)
        } catch(error:Exception) {
            Timber.w(error,"DDC source rejected; retaining last valid correction")
            onRejectedDdc(true,vdcPath,error.message ?: "DDC 文件读取或校验失败")
        }
    }

    protected open fun onDdcSource(path:String) {}
    protected open fun onRejectedDdc(enabled:Boolean,path:String,error:String):Boolean = true

    protected open fun convolutionSampleRate(): Int = sampleRate.toInt()
    protected open fun decodeConvolutionIr(path: String,rate: Int,info: IntArray,mode: Int,legacy: IntArray): FloatArray? =
        JdspImpResToolbox.ReadImpulseResponseToFloat(path,rate,info,mode,legacy)

    protected open fun configureConvolutionRuntime(prepared: ConvolutionController.PreparedIr): Boolean = true
    /** A transport success can stage a kernel before the driver has an active processing plan. */
    protected open fun convolutionTransferPending(): Boolean = false
    /** A terminal IR-only rejection must not restart the engine or resynchronise unrelated modules. */
    protected open fun convolutionFailureIsolated(): Boolean = false

    private fun publishConvolutionPreparation(prepared: ConvolutionController.PreparedIr) {
        if(!convolutionRequestCurrent())return
        // close() cancels work immediately without waiting for a potentially long native decode.
        synchronized(convolutionStateLock) {
            if(!convolutionClosed)ConvolutionController.publishPrepared(context,prepared)
        }
    }

    private fun bindConvolution(cached: CachedIr): Boolean {
        if(!convolutionRequestCurrent())return true
        if(!configureConvolutionRuntime(cached.prepared)) {
            if(!convolutionRequestCurrent())return true
            publishConvolutionPreparation(cached.prepared.copy(status=ConvolutionController.Preparation.FAILED,
                message="驱动参数未应用，请检查高通／低通频率范围及连接"))
            return false
        }
        if(!convolutionRequestCurrent())return true
        val result=setConvolverInternal(true,cached.samples,cached.prepared.channels,cached.prepared.workingFrames,cached.prepared.crc)
        if(!convolutionRequestCurrent())return true
        val next=when {
            !result->cached.prepared.copy(status=ConvolutionController.Preparation.FAILED,
                message="驱动多次重试后仍未确认装载成功，请检查连接或脉冲响应文件")
            convolutionTransferPending()->cached.prepared.copy(status=ConvolutionController.Preparation.STAGED,
                message="已暂存待激活；音频流恢复后将自动装载并核对 CRC")
            else->cached.prepared
        }
        publishConvolutionPreparation(next)
        return result || convolutionFailureIsolated()
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean =
        synchronized(engineLock) { setConvolverNow(enable,impulseResponsePath,optimizationMode,waveEditStr) }

    private fun setConvolverNow(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean {
        if(convolutionClosed)return false
        val prefs=ConvolutionController.preferences(context)
        // Default-file protection/migration never edits any user-supplied source IR.
        ConvolverLibrary.install(context)
        fun request(): IrRequest {
            val values=prefs.all
            val selected=values[ConvolutionParameters.FILE] as? String ?: impulseResponsePath
            val path=FileLibraryPreference.createFullPathCompat(context,selected)
            val file=File(path)
            val mode=(values[ConvolutionParameters.LEGACY_MODE] as? String)?.toIntOrNull()?.takeIf {it in 0..2} ?: optimizationMode.coerceIn(0,2)
            return IrRequest(path,file.lastModified(),file.length(),convolutionSampleRate(),mode,
                values[ConvolutionParameters.LEGACY_ADVANCED] as? String ?: waveEditStr,ConvolutionIrPreprocessor.Options.read(values),
                if(ConvolutionController.advancedSupported(context))ConvolutionAdvancedParameters.IrOptions.read(values) else ConvolutionAdvancedParameters.IrOptions(),
                ConvolutionAdvancedParameters.on(values,ConvolutionAdvancedParameters.ANALYSIS))
        }
        val target=request()
        convolutionRequestCurrent={ !convolutionClosed && request()==target && prefs.getBoolean(ConvolutionParameters.ENABLED,enable)==enable }
        if(!enable) {
            ConvolutionController.stopAudition(context)
            return setConvolverInternal(false,FloatArray(0),0,0,0)
        }
        if(!File(target.path).isFile) {
            publishConvolutionPreparation(ConvolutionController.PreparedIr(target.path,0,target.rate,0,0,0,0,
                status=ConvolutionController.Preparation.FAILED,message="找不到所选脉冲响应文件"))
            if(convolutionRequestCurrent())setConvolverInternal(false,FloatArray(0),0,0,0)
            return false
        }
        if(target.rate !in 8000..768000) {
            publishConvolutionPreparation(ConvolutionController.PreparedIr(target.path,0,0,0,0,0,0,
                status=ConvolutionController.Preparation.WAITING,message="已暂存所选 IR；等待音频流锁定实际处理采样率后自动装载"))
            return true
        }
        val cached=convolutionCache?.takeIf {it.request==target}
        if(cached!=null)return bindConvolution(cached)
        val source=ConvolutionIrPreprocessor.metadata(File(target.path))
        val pending=ConvolutionController.PreparedIr(target.path,source.sampleRate,target.rate,source.channels,source.frames,0,0,
            status=ConvolutionController.Preparation.PROCESSING,message="正在后台生成工作副本；当前声音以驱动已加载核为准")
        ConvolutionController.stopAudition(context,keepComparison=true)
        publishConvolutionPreparation(pending)
        return try {
            val old=target.legacy.split(';').map {it.trim().toIntOrNull()}
            val legacy=if(old.size==6 && old.all {it!=null})old.filterNotNull().toIntArray() else {
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
                intArrayOf(-80,-100,0,0,0,0)
            }
            val info=IntArray(5)
            val decoded=decodeConvolutionIr(target.path,target.rate,info,target.mode,legacy)
                ?: throw IllegalArgumentException("无法解码脉冲响应")
            require(info[0] in listOf(1,2,4) && info[1]>0 && decoded.size.toLong()==info[0].toLong()*info[1]){"脉冲响应通道或长度无效"}
            if(!convolutionRequestCurrent())return true
            val sourceRate=info[4].takeIf {it in 8000..768000} ?: source.sampleRate
            require(sourceRate in 8000..768000){"无法确认脉冲响应原始采样率"}
            if(info[3]==0)callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            val windowed=ConvolutionIrPreprocessor.edit(decoded,info[0],target.rate,target.options.copy(normalize=false))
            val adjusted=ConvolutionAdvancedIr.edit(windowed.samples,info[0],target.rate,target.advanced)
            require(ImpulseResponseTransfer.header(adjusted.samples.size,adjusted.channels)!=null){"工作IR超过驱动传输容量"}
            if(!convolutionRequestCurrent())return true
            // Target-rate sample-peak normalization would cancel the inverse SRC ratio.
            // Anchor its gain to the file's original rate, using the same complete IR edits.
            val referenceKey=target.copy(rate=sourceRate,deepAnalysis=false)
            val normalizationGain=if(!target.options.normalize)1.0 else
                convolutionReferenceGain?.takeIf {it.first==referenceKey}?.second ?: run {
                    val reference=if(sourceRate==target.rate)adjusted.samples else {
                        val referenceFrames=if(source.frames>0)source.frames.toDouble() else kotlin.math.ceil(info[1]*sourceRate.toDouble()/target.rate)
                        val heap=Runtime.getRuntime()
                        val available=heap.maxMemory()-heap.totalMemory()+heap.freeMemory()
                        // Native decode/optimization and the edit buffers coexist during this pass.
                        val referenceBytes=referenceFrames*info[0]*4*(if(target.mode==2)12 else 6)
                        require(referenceBytes.isFinite() && referenceBytes<available*.7){"原采样率参考 IR 所需内存过大，请缩短脉冲响应"}
                        val referenceInfo=IntArray(5)
                        val referenceDecoded=decodeConvolutionIr(target.path,sourceRate,referenceInfo,target.mode,legacy)
                            ?: throw IllegalArgumentException("无法生成原采样率参考 IR")
                        if(!convolutionRequestCurrent())return true
                        require(referenceInfo[0]==info[0] && referenceInfo[1]>0 &&
                            referenceDecoded.size.toLong()==referenceInfo[0].toLong()*referenceInfo[1]){"参考 IR 通道或长度无效"}
                        val referenceWindow=ConvolutionIrPreprocessor.edit(referenceDecoded,referenceInfo[0],sourceRate,target.options.copy(normalize=false))
                        ConvolutionAdvancedIr.edit(referenceWindow.samples,referenceInfo[0],sourceRate,target.advanced).samples
                    }
                    ConvolutionIrGain.normalizationGain(reference,target.options).also {gain->
                        if(convolutionRequestCurrent())convolutionReferenceGain=referenceKey to gain
                    }
                }
            if(!convolutionRequestCurrent())return true
            val gained=ConvolutionIrGain.apply(adjusted.samples,sourceRate,target.rate,normalizationGain)
            val edited=ConvolutionIrPreprocessor.edit(gained.samples,adjusted.channels,target.rate,ConvolutionIrPreprocessor.Options())
            if(!convolutionRequestCurrent())return true
            val analysis=ConvolutionIrPreprocessor.analyze(edited.samples,adjusted.channels,target.rate)
            val deep=if(target.deepAnalysis) {
                val originalInfo=IntArray(5)
                val raw=if(target.mode==0)decoded else decodeConvolutionIr(target.path,target.rate,originalInfo,0,intArrayOf(-80,-100,0,0,0,0))
                    ?: throw IllegalArgumentException("无法读取原 IR 分析数据")
                if(!convolutionRequestCurrent())return true
                val referenceRaw=ConvolutionIrGain.apply(raw,sourceRate,target.rate).samples
                ConvolutionAdvancedAnalysis.analyze(referenceRaw,info[0],edited.samples,adjusted.channels,target.rate)
            } else null
            fun peak(samples: FloatArray): Double {val p=samples.maxOf {kotlin.math.abs(it).toDouble()};return if(p==0.0)Double.NEGATIVE_INFINITY else 20*kotlin.math.log10(p)}
            val prepared=ConvolutionController.PreparedIr(target.path,sourceRate,target.rate,adjusted.channels,source.frames,edited.frames,edited.crc,
                normalizationGainDb=gained.normalizationGainDb,sampleRateCompensationDb=gained.rateCompensationDb,waveform=analysis.waveform.toList(),frequencyHz=analysis.frequencyHz,magnitudesDb=analysis.magnitudesDb.toList(),
                retainedStartFrames=windowed.startFrame,fadeInFrames=if(target.options.window)Math.round(target.options.fadeInUs*target.rate.toDouble()/1e6).toInt() else 0,fadeOutFrames=if(target.options.window)Math.round(target.options.fadeOutUs*target.rate.toDouble()/1e6).toInt() else 0,
                originalPeakDb=peak(decoded)+gained.rateCompensationDb,workingPeakDb=peak(edited.samples),peakFrames=edited.peakFrames,beforeWindowFrames=windowed.originalFrames,sourceChannels=info[0],commonDelaySamples=adjusted.commonDelay,advancedAnalysis=deep)
            if(!convolutionRequestCurrent())return true
            val next=CachedIr(target,edited.samples,prepared)
            synchronized(convolutionStateLock) {
                if(!convolutionClosed)convolutionCache=next
            }
            bindConvolution(next)
        } catch(error: Exception) {
            Timber.e(error,"IR preparation failed")
            if(!convolutionRequestCurrent())return true
            publishConvolutionPreparation(pending.copy(status=ConvolutionController.Preparation.FAILED,message=error.message ?: "工作IR生成失败"))
            false
        }
    }

    fun setGraphicEq(enable: Boolean, bands: String): Boolean
    {
        // Sanity check
        if(!bands.contains("GraphicEQ:", true)) {
            Timber.e("setGraphicEq: malformed string")
            setGraphicEqInternal(false, "")
            return false
        }

        return setGraphicEqInternal(enable, bands)
    }

    fun setLiveprog(enable: Boolean, path: String): Boolean
    {
        liveprogContentRejected = false
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            val source = it.readText()
            val identity = LiveprogParameterStore.identity(fullPath, source)
            val result = setLiveprogInternal(enable, name, prepareLiveprogScript(name, source))
            if (!result) return@use false
            if (!enable) return@use true
            if (activeLiveprogIdentity != identity) appliedLiveprogParameters.clear()
            activeLiveprogIdentity = identity
            val parser = EelParser().apply { loadSource(fullPath, source) }
            applyLiveprogParameters(parser)
        } ?: false
    }

    /** Update only the selected, successfully compiled script; never upload EEL on a parameter edit. */
    fun updateLiveprogParameters(path: String) {
        syncScope.launch {
            synchronized(engineLock) {
                updateLiveprogParametersNow(path)
            }
        }
    }

    internal fun updateLiveprogParametersNow(path: String) {
        if (convolutionClosed) return
        val preferences = PreferenceCache.getPreferences(context, Constants.PREF_LIVEPROG)
        val selected = FileLibraryPreference.createFullPathCompat(context,
            preferences.getString(context.getString(R.string.key_liveprog_file), "").orEmpty())
        if (File(selected).absolutePath != File(path).absolutePath ||
            !preferences.getBoolean(context.getString(R.string.key_liveprog_enable), false)) return
        val parser = EelParser().apply { load(path) }
        val identity = parser.contents?.let { LiveprogParameterStore.identity(path, it) }
        if (identity == null || identity != activeLiveprogIdentity) {
            reportLiveprogParameterFailure(path, context.getString(R.string.liveprog_parameters_script_inactive))
            return
        }
        applyLiveprogParameters(parser)
    }

    protected fun invalidateLiveprogParameters() {
        activeLiveprogIdentity = null
        appliedLiveprogParameters.clear()
    }

    protected open fun supportsRealtimeLiveprogParameters(): Boolean = supportsEelVmAccess()
    protected open fun prepareLiveprogScript(name: String, source: String): String =
        BuiltinLiveprogRuntime.prepare(context, name, source)
    protected open fun setLiveprogParameterInternal(name: String, value: Double): Boolean =
        manipulateEelVariable(name, value.toFloat())

    private fun applyLiveprogParameters(parser: EelParser): Boolean {
        val store = LiveprogParameterStore(context)
        val requested = store.values(parser)
        if (requested.isEmpty()) return true
        if (!supportsRealtimeLiveprogParameters()) {
            reportLiveprogParameterFailure(parser.path!!, context.getString(R.string.liveprog_parameters_driver_upgrade))
            // An unsupported optional interface must not trigger driver-recovery loops.
            return true
        }
        store.applyTo(parser)
        requested.keys.forEach { key ->
            val value = parser.properties.first { it.key == key }.valueAsString().toDouble()
            if (appliedLiveprogParameters[key] == value) return@forEach
            if (!setLiveprogParameterInternal(key, value)) {
                reportLiveprogParameterFailure(parser.path!!, context.getString(R.string.liveprog_parameters_apply_failed, key))
                return false
            }
            appliedLiveprogParameters[key] = value
        }
        return true
    }

    private fun reportLiveprogParameterFailure(path: String, message: String) {
        Timber.w(message)
        context.sendLocalBroadcast(Intent(Constants.ACTION_LIVEPROG_PARAMETER_RESULT).apply {
            putExtra(Constants.EXTRA_LIVEPROG_PATH, path)
            putExtra(Constants.EXTRA_LIVEPROG_ERROR, message)
        })
    }

    private fun safeFileReader(path: String) =
        try { FileReader(path) }
        catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // Effect config
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Float): Boolean

    protected abstract fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    protected abstract fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean
    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean
    protected abstract fun setConvolverInternal(enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int, irCrc: Int): Boolean
    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean
    protected abstract fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean

    // Feature support
    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    // EEL VM utilities
    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
}
