package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DynamicPeqProfileStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val actual get() = context.getSharedPreferences(Constants.PREF_DYNAMIC_PEQ, Context.MODE_PRIVATE)

    @Before fun clear() {
        stopKoin()
        startKoin { modules(module { single<Context> { context } }) }
        actual.edit().clear().commit()
    }
    @After fun stopDependencies() { stopKoin() }

    @Test fun realGlobalPresetLoadReplacesExistingLiveCacheAfterAsynchronousEdits() {
        val repository = DynamicPeqRepository(context)
        val incoming = DynamicPeqState(true, listOf(DynamicPeqBand("profile-bank", gain = -6.125)))
        assertTrue(repository.save(incoming, synchronous = false))
        val preset = Preset("dynamic-profile.tar", temporary.newFolder())
        assertTrue(preset.save()) // must flush apply before archiving this namespace
        val old = DynamicPeqState(true, listOf(DynamicPeqBand("old-pending-bank", gain = 9.125)))
        assertTrue(repository.save(old, synchronous = false))
        Preset.load(context, preset.file().inputStream())
        assertEquals(incoming, repository.load())
        assertEquals(incoming, DynamicPeqRepository.decode(actual.getString(DynamicPeqRepository.KEY_STATE, null)!!))
        assertTrue(repository.flush())
        assertEquals(incoming, repository.load())
    }

    @Test fun newProfileCommitCannotBeOverwrittenByOldRoutesPendingApply() {
        val preferences = DelayedDiskPreferences(actual)
        val repository = DynamicPeqRepository(preferences)
        val old = DynamicPeqState(true, listOf(DynamicPeqBand("old-route", gain = 4.125)))
        val incoming = DynamicPeqState(false, listOf(DynamicPeqBand("new-route", threshold = -39.125)))
        assertTrue(repository.save(old, synchronous = false))
        assertEquals(1, preferences.pendingCount)
        assertEquals(old, repository.load())
        val xml = preferenceXml(incoming)
        assertTrue(repository.replaceFromPreset(DynamicPeqRepository.readPresetPreferences(xml)))
        // Simulate the next Android QueuedWork flush, after the profile operation completed.
        preferences.drain()
        assertEquals(0, preferences.pendingCount)
        assertEquals(1, preferences.commitCount)
        assertEquals(incoming, repository.load())
        assertEquals(incoming, DynamicPeqRepository.decode(preferences.disk[DynamicPeqRepository.KEY_STATE] as String))
        assertEquals(false, preferences.disk[DynamicPeqRepository.KEY_ENABLED])
        assertEquals(incoming, DynamicPeqRepository(actual).load()) // existing live cache updated too
    }

    @Test fun absentModuleInOlderPresetClearsOnlyThisBankAfterPendingWrites() {
        val static = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        static.edit().clear().putString("sentinel", "static-bank").putBoolean("geq_enable", true).commit()
        val unchanged = static.all.toMap()
        val preferences = DelayedDiskPreferences(actual)
        val repository = DynamicPeqRepository(preferences)
        repository.save(DynamicPeqState(true), synchronous = false)
        assertTrue(repository.replaceFromPreset(null))
        preferences.drain()
        assertTrue(preferences.disk.isEmpty())
        assertFalse(repository.load().enabled)
        assertFalse(repository.hasSavedState())
        assertEquals(unchanged, static.all)
    }

    @Test fun archivingFlushesThisNamespacesQueuedSnapshotBeforeReadingXml() {
        val preferences = DelayedDiskPreferences(actual)
        val repository = DynamicPeqRepository(preferences)
        val edited = DynamicPeqState(true, listOf(DynamicPeqBand("latest-drag", frequency = 2222.125)))
        repository.save(edited, synchronous = false)
        assertTrue(preferences.disk.isEmpty())
        assertTrue(repository.flush())
        assertEquals(0, preferences.pendingCount)
        assertEquals(edited, DynamicPeqRepository.decode(preferences.disk[DynamicPeqRepository.KEY_STATE] as String))
    }

    @Test fun invalidOrEntityBearingIncomingXmlCannotReplaceCurrentBank() {
        val repository = DynamicPeqRepository(actual)
        val current = DynamicPeqState(true)
        repository.save(current)
        val before = actual.all.toMap()
        val valid = preferenceXml(current).readText()
        listOf(valid.replace("name=\"dynamic_peq_state\"", "name=\"geq_nodes\""),
            valid.replace("&quot;schema&quot;:1", "&quot;schema&quot;:999").replace("\"schema\":1", "\"schema\":999"),
            "<?xml version=\"1.0\"?><!DOCTYPE map [<!ENTITY remote SYSTEM \"file:///does-not-exist\">]><map><string name=\"dynamic_peq_state\">&remote;</string></map>",
            "<map><boolean name=\"dynamic_peq_enable\" value=\"yes\" /></map>").forEachIndexed { index, xml ->
            val file = File(temporary.root, "invalid-$index.xml").apply { writeText(xml) }
            assertThrows(Exception::class.java) {
                repository.replaceFromPreset(DynamicPeqRepository.readPresetPreferences(file))
            }
            assertEquals(before, actual.all)
        }
    }

    private fun preferenceXml(state: DynamicPeqState): File = File.createTempFile("dynamic-preset-", ".xml", temporary.root).apply {
        outputStream().use { output ->
            Xml.newSerializer().apply {
                setOutput(output, "UTF-8"); startDocument("UTF-8", true); startTag(null, "map")
                startTag(null, "string"); attribute(null, "name", DynamicPeqRepository.KEY_STATE)
                text(DynamicPeqRepository.encode(state)); endTag(null, "string")
                startTag(null, "boolean"); attribute(null, "name", DynamicPeqRepository.KEY_ENABLED)
                attribute(null, "value", state.enabled.toString()); endTag(null, "boolean")
                endTag(null, "map"); endDocument(); flush()
            }
        }
    }

    /** Model Android's ordered apply/commit disk queue while leaving actual cache/listeners real. */
    private class DelayedDiskPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        val disk = linkedMapOf<String, Any?>()
        private val pending = ArrayDeque<Map<String, *>>()
        val pendingCount: Int get() = pending.size
        var commitCount = 0
            private set
        fun drain() { while (pending.isNotEmpty()) { disk.clear(); disk.putAll(pending.removeFirst()) } }
        override fun edit(): SharedPreferences.Editor {
            val delegate = actual.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { delegate.putString(key, value) }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { delegate.putBoolean(key, value) }
                override fun clear(): SharedPreferences.Editor = apply { delegate.clear() }
                override fun apply() { delegate.apply(); pending += actual.all.toMap() }
                override fun commit(): Boolean {
                    commitCount++
                    val saved = delegate.commit()
                    drain()
                    if (saved) { disk.clear(); disk.putAll(actual.all) }
                    return saved
                }
            }
        }
    }
}
