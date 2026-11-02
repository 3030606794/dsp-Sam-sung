package me.timschneeberger.rootlessjamesdsp.model.preset

import android.content.Context
import android.content.Intent
import android.system.ErrnoException
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.fusion.BassParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.HighFrequencyController
import me.timschneeberger.rootlessjamesdsp.fusion.FieldController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionController
import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionParameters
import me.timschneeberger.rootlessjamesdsp.convolver.ConvolverLibrary
import me.timschneeberger.rootlessjamesdsp.dynamicpeq.DynamicPeqRepository
import me.timschneeberger.rootlessjamesdsp.fusion.DiffController
import me.timschneeberger.rootlessjamesdsp.fusion.DiffParameters
import me.timschneeberger.rootlessjamesdsp.fusion.DiffAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldParameters
import me.timschneeberger.rootlessjamesdsp.fusion.FieldAdvancedParameters
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.backup.BackupManager
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.liveprog.LiveprogParameterStore
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.broadcastPresetLoadEvent
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.xml.sax.SAXException
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class Preset(val name: String, externalPath: File? = null): KoinComponent {
    private val ctx: Context by inject()
    private val externalPath = externalPath ?: File("${ctx.getExternalFilesDir(null)!!.path}/Presets")

    fun file(): File = File(externalPath, name)

    fun rename(newName: String): Boolean {
        return file().renameTo(File(externalPath, newName))
    }

    fun validate(): Boolean {
        return validate(FileInputStream(file()))
    }

    /**
     * @exception Exception if preset cannot be loaded
     */
    fun load(): PresetMetadata {
        val file = file()
        Timber.d("Loading preset from ${file.path}")
        return load(
            ctx,
            FileInputStream(file)
        )
    }

    fun save(): Boolean {
        val targetFile = file()
        if (targetFile.exists())
            targetFile.delete()

        try {
            externalPath.mkdirs()
        } catch (_: Exception) {}

        Timber.d("Saving preset $name to ${targetFile.path}")

        // Create a TarOutputStream
        try {
            // Interactive dynamic edits use apply(); finish their ordered writes before reading XML.
            check(DynamicPeqRepository(ctx).flush()) { "动态 PEQ 参数尚未写入存储，请重试保存" }
            check(me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.flush(ctx)) { "新增音效参数尚未写入存储，请重试保存" }
            check(me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRepository.flush(ctx)) { "创意音效参数尚未写入存储，请重试保存" }
            Tar.Composer(targetFile).use { c ->
                c.metadata = mutableMapOf(
                    META_VERSION to PRESET_VERSION,
                    META_APP_VERSION to BuildConfig.VERSION_NAME,
                    META_APP_FLAVOR to BuildConfig.FLAVOR,
                    META_LIVEPROG_INCLUDED to false.toString(),
                    // Older APKs cannot represent the fine bass keys and must not silently discard them.
                    META_MIN_VERSION_CODE to minimumVersionCode(ctx)
                )

                currentPath(ctx)
                    .listFiles()
                    ?.filter { it.name.startsWith("dsp_") }
                    ?.filter { it.extension == "xml" }
                    ?.forEach(c::add)

                findLiveprogScriptPath(ctx)?.let { path ->
                    val liveprogFile = File(path)
                    if (liveprogFile.exists()) {
                        Timber.d("Saving included liveprog script state from '$path'")

                        c.metadata[META_LIVEPROG_INCLUDED] = true.toString()
                        File(ctx.cacheDir, FILE_LIVEPROG).let {
                            it.writeText(LiveprogParameterStore(ctx).exportSource(liveprogFile.absolutePath))
                            c.add(it)
                        }
                    }
                }
            }
        }
        catch (ex: ErrnoException) {
            Timber.d(ex)
            ex.localizedMessage?.let { ctx.toast(it) }
            return false
        }
        catch (ex: Exception) {
            Timber.d(ex)
            return false
        }

        return true
    }

    companion object {
        /* Update constants as needed */
        const val PRESET_VERSION = "3"
        const val MIN_VERSION_CODE = "26"

        const val FILE_LIVEPROG = "liveprog"

        const val META_VERSION = "version"
        const val META_APP_VERSION = "app_version"
        const val META_APP_FLAVOR = "app_flavor"
        const val META_LIVEPROG_INCLUDED = "liveprog_included" /* version 2+ */
        const val META_MIN_VERSION_CODE = "min_version_code" /* version 3+ */

        internal fun minimumVersionCode(context: Context): String = when {
            context.getSharedPreferences(me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind.SPEAKER_OPTIMIZATION.prefsName,
                Context.MODE_PRIVATE).all.isNotEmpty() -> "1157"
            me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind.values().any {
                context.getSharedPreferences(it.prefsName, Context.MODE_PRIVATE).all.isNotEmpty()
            } -> "1155"
            me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind.values().any {
                context.getSharedPreferences(it.prefsName, Context.MODE_PRIVATE).all.isNotEmpty()
            } -> "1154"
            context.getSharedPreferences(Constants.PREF_DYNAMIC_PEQ, Context.MODE_PRIVATE).all.isNotEmpty() -> "1152"
            context.getSharedPreferences(Constants.PREF_LIVEPROG, Context.MODE_PRIVATE).all.keys.any { it.startsWith("liveprog_parameter_v1:") } -> "1052"
            me.timschneeberger.rootlessjamesdsp.fusion.DdcParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.DdcController.preferences(context).all::containsKey) -> "1051"
            me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.ToneController.preferences(context).all::containsKey) -> "1050"
            me.timschneeberger.rootlessjamesdsp.fusion.CompanderParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.CompanderController.preferences(context).all::containsKey) -> "1049"
            (me.timschneeberger.rootlessjamesdsp.fusion.TimbreController.preferences(context).all[me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters.amount.key] as? Int ?: 1000) > 2000 -> "1048"
            me.timschneeberger.rootlessjamesdsp.fusion.DynamicParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.DynamicController.preferences(context).all::containsKey) -> "1048"
            me.timschneeberger.rootlessjamesdsp.fusion.TimbreParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.TimbreController.preferences(context).all::containsKey) -> "1047"
            me.timschneeberger.rootlessjamesdsp.fusion.AnalogParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.AnalogController.preferences(context).all::containsKey) -> "1046"
            me.timschneeberger.rootlessjamesdsp.fusion.TubeParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.TubeController.preferences(context).all::containsKey) -> "1045"
            me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController.preferences(context).all::containsKey) -> "1044"
            me.timschneeberger.rootlessjamesdsp.fusion.ReverbAdvancedParameters.keys.any(context.getSharedPreferences("dsp_fusion_viper",Context.MODE_PRIVATE).all::containsKey) ||
                me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters.advancedKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedController.preferences(context).all::containsKey) -> "1043"
            me.timschneeberger.rootlessjamesdsp.fusion.ReverbParameters.newKeys.any(context.getSharedPreferences("dsp_fusion_viper",Context.MODE_PRIVATE).all::containsKey) -> "1042"
            me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedAdvancedParameters.keys.any(context.getSharedPreferences("dsp_fusion_viper",Context.MODE_PRIVATE).all::containsKey) ||
                me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.PresetCrossfeedController.preferences(context).all::containsKey) -> "1041"
            me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper",Context.MODE_PRIVATE).all) -> "1040"
            me.timschneeberger.rootlessjamesdsp.fusion.SurroundListeningParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_surround",Context.MODE_PRIVATE).all) -> "1039"
            me.timschneeberger.rootlessjamesdsp.fusion.SurroundProfessionalParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_surround",Context.MODE_PRIVATE).all) -> "1038"
            me.timschneeberger.rootlessjamesdsp.fusion.WidthAdvancedParameters.keys.any(me.timschneeberger.rootlessjamesdsp.fusion.WidthController.preferences(context).all::containsKey) -> "1037"
            me.timschneeberger.rootlessjamesdsp.fusion.WidthParameters.newKeys.any(me.timschneeberger.rootlessjamesdsp.fusion.WidthController.preferences(context).all::containsKey) -> "1036"
            me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedParameters.newKeys.any(ConvolutionController.preferences(context).all::containsKey) -> "1035"
            ConvolutionParameters.requiresNewApp(ConvolutionController.preferences(context).all) -> "1034"
            DiffAdvancedParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1033"
            DiffParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1032"
            FieldAdvancedParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1031"
            FieldParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1030"
            HighFrequencyAdvancedParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1029"
            HighFrequencyParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all) -> "1028"
            BassParameters.requiresNewApp(context.getSharedPreferences("dsp_fusion_viper", Context.MODE_PRIVATE).all,
                context.getSharedPreferences(Constants.PREF_BASS, Context.MODE_PRIVATE).all) -> "1027"
            context.getSharedPreferences("dsp_fusion_surround", Context.MODE_PRIVATE).all.isNotEmpty() -> "1013"
            else -> "1010"
        }

        private fun currentPath(ctx: Context) = File(ctx.applicationInfo.dataDir + "/shared_prefs")
        private fun isKnownEntry(n: String) = (n.startsWith("dsp_") && n.endsWith("xml")) || n == FILE_LIVEPROG

        fun validate(inputStream: InputStream) = Tar.Reader(inputStream, ::isKnownEntry).validate()

        /**
         * @exception Exception if preset cannot be loaded
         */
        fun load(ctx: Context, stream: InputStream): PresetMetadata {
            Timber.d("Loading preset from stream")

            val targetFolder = File(ctx.cacheDir, "preset")
            val metadata = Tar.Reader(stream, ::isKnownEntry).extract(targetFolder)
            metadata ?: throw Exception(ctx.getString(R.string.filelibrary_corrupted))

            if(metadata[BackupManager.META_IS_BACKUP]?.toBoolean() == true) {
                Timber.e("This is a backup file, not a preset file")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_is_backup_not_preset))
            }

            val version = metadata[META_VERSION]?.toIntOrNull() ?: 2
            Timber.d("Loaded preset file version $version")

            val minVersionCode = metadata[META_MIN_VERSION_CODE]?.toIntOrNull() ?: 0
            if(BuildConfig.VERSION_CODE < minVersionCode) {
                Timber.w("Preset too new. Version code $minVersionCode or later required")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_file_too_new))
            }

            val files = targetFolder.listFiles()
            if(files == null || files.isEmpty()) {
                Timber.e("Preset archive did not contain any useful data")
                targetFolder.deleteRecursively()
                throw Exception(ctx.getString(R.string.filelibrary_corrupted))
            }

            // Validate this independent namespace before touching any current effect state.
            val dynamicPeqFile = files.firstOrNull { it.name == "${Constants.PREF_DYNAMIC_PEQ}.xml" }
            val dynamicPeqState = dynamicPeqFile?.let { DynamicPeqRepository.readPresetPreferences(it) }
            val expansionFiles = me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsKind.values().associateWith { kind ->
                files.firstOrNull { it.name == "${kind.prefsName}.xml" }
            }
            val expansionStates = expansionFiles.mapValues { (kind, file) ->
                file?.let { me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.read(kind, it) }
            }
            val creativeFiles = me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsKind.values().associateWith { kind ->
                files.firstOrNull { it.name == "${kind.prefsName}.xml" }
            }
            val creativeStates = creativeFiles.mapValues { (kind, file) ->
                file?.let { me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRepository.read(kind, it) }
            }
            me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsController.resetTransient(ctx)
            me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsController.resetTransient(ctx)

            HighFrequencyController.stopAudition(ctx)
            FieldController.stopAudition(ctx)
            DiffController.stopAudition(ctx)
            me.timschneeberger.rootlessjamesdsp.fusion.CrossfeedController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.ReverbController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.RoomReverbController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.TubeController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.AnalogController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.DynamicController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.TimbreController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.CompanderController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.ToneAdvancedController.stopAudition(ctx,clearSnapshots=true)
            me.timschneeberger.rootlessjamesdsp.fusion.DdcController.stopAudition(ctx,clearSnapshots=true)
            ConvolutionController.stopAudition(ctx)
            me.timschneeberger.rootlessjamesdsp.fusion.WidthController.stopAudition(ctx)

            // Missing processor namespaces must not retain PEQ/tone/ViPER/surround settings
            // from the preceding device profile underneath an older preset.
            arrayOf("dsp_fusion_viper", "dsp_fusion_tone", "dsp_fusion_surround", Constants.PREF_GEQ, Constants.PREF_BASS, Constants.PREF_CONVOLVER,Constants.PREF_STEREOWIDE,Constants.PREF_REVERB,Constants.PREF_TUBE,Constants.PREF_COMPANDER,Constants.PREF_DDC).forEach { namespace ->
                if (files.none { it.name == "$namespace.xml" }) {
                    ctx.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }

            // A raw copy can be overwritten later by the preceding route's queued apply.
            // commit serializes the new bank after that queue and refreshes live preferences.
            check(DynamicPeqRepository(ctx).replaceFromPreset(dynamicPeqState)) { "动态 PEQ 设备配置保存失败" }
            expansionStates.forEach { (kind, values) ->
                check(me.timschneeberger.rootlessjamesdsp.expansion.ExpansionEffectsRepository.replace(ctx, kind, values)) {
                    "${kind.title}设备配置保存失败"
                }
            }
            creativeStates.forEach { (kind, values) ->
                check(me.timschneeberger.rootlessjamesdsp.creative.CreativeEffectsRepository.replace(ctx, kind, values)) {
                    "${kind.title}设备配置保存失败"
                }
            }

            files.forEach next@ { f ->
                if(!isKnownEntry(f.name) || f == dynamicPeqFile || f in creativeFiles.values || f in expansionFiles.values)
                    return@next

                val target = File(currentPath(ctx), f.name)
                f.copyTo(target, overwrite = true)
                Timber.d("Copying to ${target.absolutePath}")
            }

            if (files.any { it.name == FILE_LIVEPROG }) {
                findLiveprogScriptPath(ctx)?.let {
                    val originalFile = File(it)
                    val targetFile =
                        File("${ctx.getExternalFilesDir(null)!!.path}/Liveprog", originalFile.name)
                    val tempPath = File(currentPath(ctx), FILE_LIVEPROG)

                    if(metadata[META_LIVEPROG_INCLUDED].toBoolean()) {
                        if(!targetFile.exists()) {
                            Timber.d("Extracting embedded liveprog file to '${targetFile.absolutePath}'")
                            tempPath.copyTo(targetFile, overwrite = true)
                            tempPath.delete()
                        }
                        else {
                            Timber.d("Copying parameters of embedded liveprog file to '${targetFile.absolutePath}'")
                            val parser = EelParser()
                            val parserNew = EelParser()
                            parser.load(tempPath.absolutePath)
                            parserNew.load(targetFile.absolutePath)
                            parser.properties.forEach(parserNew::manipulateProperty)
                            parserNew.save()
                            tempPath.delete()
                        }
                        ctx.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_RELOAD_LIVEPROG))
                    }
                }
            }

            // clean up
            targetFolder.deleteRecursively()

            ctx.broadcastPresetLoadEvent()

            return metadata.toMutableMap()
        }

        private fun findLiveprogScriptPath(ctx: Context): String? {
            val xmlFile = File(currentPath(ctx), "${Constants.PREF_LIVEPROG}.xml")
            if (!xmlFile.exists())
                return null
            try {
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(FileInputStream(xmlFile))
                val nodes = doc.getElementsByTagName("string")

                for(i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    if(node.attributes.getNamedItem("name").nodeValue ==
                        ctx.getString(R.string.key_liveprog_file)) {
                        return node.textContent.let {
                            ctx.getExternalFilesDir(null)!!.absolutePath + "/" + it
                        }.also {
                            Timber.d("Found liveprog file path: $it")
                        }
                    }
                }
            } catch (e: SAXException) {
                Timber.w(e)
            }
            catch (e: IOException) {
                Timber.w(e)
            }
            return null
        }
    }
}


typealias PresetMetadata = MutableMap<String, String>
