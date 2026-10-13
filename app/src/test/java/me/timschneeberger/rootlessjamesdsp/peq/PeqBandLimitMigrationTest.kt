package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class PeqBandLimitMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val recovery get() = context.getSharedPreferences(PeqRepository.BAND_LIMIT_BACKUP_PREFERENCES, Context.MODE_PRIVATE)
    private val legacyId = "0f16c327-8316-4b69-bf33-7797f4158b08"
    private val otherId = "d8f6b4b9-e822-43ab-976b-53ec404311bb"

    @Before fun clearPreferences() {
        preferences.edit().clear().commit()
        recovery.edit().clear().commit()
    }

    private fun legacyState(count: Int = 170, prefix: String = "old"): PeqState = PeqState(
        List(count) { index -> PeqBand("$prefix-$index", PeqFilterType.entries[index % 7],
            index % 3 != 0, 20.125 + index * 83.0, -12.5 + index * 0.125,
            0.25 + index * 0.125, if (index % 2 == 0) PeqQualityDisplay.Q else PeqQualityDisplay.BW) },
        gainRange = 31.25, gainStep = 0.25, qRange = 40.0, qStep = 0.125,
        frequencyStep = 2.5, pinResponse = false, showFrequencySlider = false)

    /** Build the previous format without routing its oversized bank through the new encoder. */
    private fun rawState(state: PeqState): String {
        val metadata = Json.parseToJsonElement(PeqRepository.encode(state.copy(bands = emptyList()))).jsonObject
        return JsonObject(metadata + ("bands" to JsonArray(state.bands.map { band ->
            buildJsonObject {
                put("id", band.id)
                put("type", band.type.name)
                put("enabled", band.enabled)
                put("frequency", band.frequency)
                put("gain", band.gain)
                put("q", band.q)
                put("qualityDisplay", band.qualityDisplay.name)
            }
        }))).toString()
    }

    private fun library(legacy: PeqState = legacyState()): String = buildJsonObject {
        put("format", PeqPresetCodec.FORMAT)
        put("version", PeqPresetCodec.VERSION)
        put("presets", buildJsonArray {
            add(buildJsonObject {
                put("id", legacyId)
                put("name", "旧多段预设")
                put("state", Json.parseToJsonElement(rawState(legacy)))
            })
            add(buildJsonObject {
                put("id", otherId)
                put("name", "独立保留")
                put("state", Json.parseToJsonElement(PeqRepository.encode(PeqModel.defaultState(4))))
            })
        })
    }.toString()

    private fun backups(file: File): List<File> = file.parentFile!!.listFiles()!!.filter {
        it.name.startsWith(file.name + ".before-130-") && it.isFile && it.extension == "json"
    }

    @Test fun newBanksAndPortableImportsReject131WithoutChangingCurrentBankOrPersonalLibrary() {
        assertEquals(130, PeqModel.MAX_BANDS)
        val current = PeqModel.defaultState(130)
        val repository = PeqRepository(context)
        assertTrue(repository.save(current))
        val before = preferences.all.toMap()
        val tooMany = legacyState(131)
        listOf<() -> Unit>(
            { PeqModel.defaultState(131) },
            { PeqModel.resize(current, 131) },
            { PeqModel.resize(current, 131, PeqResizeEdge.FRONT, false) },
            { repository.save(tooMany) },
            { PeqRepository.encode(tooMany) },
            { repository.importJson(rawState(tooMany)) },
            { PeqPresetCodec.decode(library(tooMany)) }
        ).forEach { operation -> assertThrows(IllegalArgumentException::class.java) { operation() } }
        assertEquals(before, preferences.all)
        val file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        val store = PeqPresetStore(file)
        store.save("保留", current)
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.importJson(library(tooMany)) }
        assertThrows(IllegalArgumentException::class.java) { store.save("过多", tooMany) }
        assertArrayEquals(original, file.readBytes())
        assertTrue(backups(file).isEmpty())
    }

    @Test fun backgroundLoadBacksUpAll170AndPendingNoticeSurvivesUntilUiAcknowledges() {
        val legacy = legacyState()
        val raw = rawState(legacy)
        preferences.edit().putString(PeqRepository.KEY_STATE, raw)
            .putString("geq_nodes", "legacy graphic curve").putBoolean("geq_enable", false).commit()
        val expected = legacy.copy(bands = legacy.bands.take(130))
        // An effect-service load may happen before the PEQ screen is opened.
        assertEquals(expected, PeqRepository(context).load())
        val repository = PeqRepository(context)
        val result = repository.loadResult()
        assertTrue(result.hasSavedState)
        assertFalse(result.corrupt)
        assertTrue(result.bandLimitBackupSaved)
        assertEquals(170, result.originalBandCount)
        assertEquals(expected, result.state)
        assertEquals(raw, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
        assertEquals(expected, PeqRepository.decode(preferences.getString(PeqRepository.KEY_STATE, null)!!))
        assertEquals(653, PeqProtocol.payload(result.state, true).size)
        assertEquals("legacy graphic curve", preferences.getString("geq_nodes", null))
        assertFalse(preferences.getBoolean("geq_enable", true))
        assertTrue(repository.save(expected.copy(gainStep = 0.5)))
        assertEquals(170, PeqRepository(context).loadResult().originalBandCount)
        assertTrue(repository.acknowledgeBandLimitMigration())
        assertNull(PeqRepository(context).loadResult().originalBandCount)
        assertEquals(raw, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
    }

    @Test fun writeBeforeFirstLoadAndSubsequentDifferentOldBankEachKeepTheirFullRecoveryCopy() {
        val first = rawState(legacyState(155, "first"))
        preferences.edit().putString(PeqRepository.KEY_STATE, first).commit()
        val repository = PeqRepository(context)
        val new = PeqModel.defaultState(4)
        assertTrue(repository.save(new))
        assertEquals(first, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
        assertEquals(155, repository.loadResult().originalBandCount)
        assertEquals(new, repository.load())
        val second = rawState(legacyState(170, "second"))
        preferences.edit().putString(PeqRepository.KEY_STATE, second).commit()
        assertEquals(170, repository.loadResult().originalBandCount)
        assertEquals(second, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
        val saved = recovery.all.filterKeys { it.startsWith(PeqRepository.KEY_LIMIT_BACKUP) }.values
        assertTrue(saved.contains(first))
        assertTrue(saved.contains(second))
    }

    @Test fun failedSharedPreferenceCommitIsRetriedBeforeReportingBackupSaved() {
        val raw = rawState(legacyState())
        preferences.edit().putString(PeqRepository.KEY_STATE, raw).commit()
        var failCommits = true
        var attempts = 0
        val underlying = preferences
        val failing = object : SharedPreferences by underlying {
            override fun edit(): SharedPreferences.Editor {
                val editor = underlying.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        attempts++
                        // Model Android's visible in-memory update even when persistence fails.
                        editor.commit()
                        return !failCommits
                    }
                }
            }
        }
        assertFalse(PeqRepository(failing, recovery).loadResult().bandLimitBackupSaved)
        assertEquals(130, PeqRepository.decode(underlying.getString(PeqRepository.KEY_STATE, null)!!).bands.size)
        assertFalse(PeqRepository(failing, recovery).loadResult().bandLimitBackupSaved)
        assertEquals(2, attempts)
        failCommits = false
        val successful = PeqRepository(failing, recovery).loadResult()
        assertTrue(successful.bandLimitBackupSaved)
        assertEquals(170, successful.originalBandCount)
        assertEquals(3, attempts)
        assertEquals(raw, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
    }

    @Test fun bankRecoverySurvivesGlobalOrDevicePreferenceReplacement() {
        val raw = rawState(legacyState())
        preferences.edit().putString(PeqRepository.KEY_STATE, raw).commit()
        assertTrue(PeqRepository(context).loadResult().bandLimitBackupSaved)
        // Global/device presets clear or replace PREF_GEQ; recovery has its own namespace.
        preferences.edit().clear().putBoolean("geq_enable", false).commit()
        assertTrue(PeqRepository(context).save(PeqModel.defaultState(3)))
        assertEquals(raw, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
        assertTrue(recovery.all.filterKeys { it.startsWith(PeqRepository.KEY_LIMIT_BACKUP + ".") }.values.contains(raw))
        assertEquals(170, Json.parseToJsonElement(recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null)!!)
            .jsonObject["bands"]!!.jsonArray.size)
    }

    @Test fun backupOrActiveStorageExceptionReturnsValidTrimmedBankWithoutClaimingCorruption() {
        val legacy = legacyState()
        val raw = rawState(legacy)
        preferences.edit().putString(PeqRepository.KEY_STATE, raw).commit()
        val expected = legacy.copy(bands = legacy.bands.take(130))
        val blockedRecovery = object : SharedPreferences by recovery {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("recovery unavailable")
        }
        val noBackup = PeqRepository(preferences, blockedRecovery)
        val backupFailed = noBackup.loadResult()
        assertFalse(backupFailed.corrupt)
        assertFalse(backupFailed.bandLimitBackupSaved)
        assertEquals(170, backupFailed.originalBandCount)
        assertEquals(expected, backupFailed.state)
        assertEquals(raw, preferences.getString(PeqRepository.KEY_STATE, null))
        assertFalse(noBackup.save(PeqModel.defaultState()))
        assertEquals(raw, preferences.getString(PeqRepository.KEY_STATE, null))
        assertTrue(recovery.all.isEmpty())
        val blockedBank = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("active store unavailable")
        }
        val activeFailed = PeqRepository(blockedBank, recovery).loadResult()
        assertFalse(activeFailed.corrupt)
        assertFalse(activeFailed.bandLimitBackupSaved)
        assertEquals(expected, activeFailed.state)
        assertEquals(raw, preferences.getString(PeqRepository.KEY_STATE, null))
        assertEquals(raw, recovery.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
    }

    @Test fun concurrentSaveWaitsForMigrationFromAnotherRepositoryAndRemainsTheLatestBank() {
        val raw = rawState(legacyState())
        val active = preferences
        val recoveryStore = recovery
        active.edit().putString(PeqRepository.KEY_STATE, raw).commit()
        val backupEntered = CountDownLatch(1)
        val releaseBackup = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val gatedRecovery = object : SharedPreferences by recoveryStore {
            override fun edit(): SharedPreferences.Editor {
                val editor = recoveryStore.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (pauseOnce.compareAndSet(true, false)) {
                            backupEntered.countDown()
                            check(releaseBackup.await(5, TimeUnit.SECONDS)) { "Backup was not released" }
                        }
                        return editor.commit()
                    }
                }
            }
        }
        val migrated = FutureTask { PeqRepository(active, gatedRecovery).loadResult() }
        val newState = PeqModel.defaultState(7).copy(gainRange = 42.0)
        val saved = FutureTask {
            saveStarted.countDown()
            PeqRepository(active, gatedRecovery).save(newState)
        }
        val migrationThread = Thread(migrated, "peq-limit-migration")
        val saveThread = Thread(saved, "peq-save-during-migration")
        migrationThread.start()
        try {
            assertTrue(backupEntered.await(5, TimeUnit.SECONDS))
            saveThread.start()
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
            fun waitsForMigration(): Boolean {
                if (saveThread.state != Thread.State.BLOCKED) return false
                val blockedAt = saveThread.stackTrace.firstOrNull() ?: return false
                // The latch above keeps migration inside its transaction. The other thread
                // must block at save's entry monitor, rather than inside SharedPreferences.
                return blockedAt.className == PeqRepository::class.java.name && blockedAt.methodName == "save"
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!saved.isDone && !waitsForMigration() && System.nanoTime() < deadline) Thread.yield()
            assertTrue("A second repository must wait for the migration's complete read/write transaction",
                waitsForMigration())
        } finally {
            releaseBackup.countDown()
            migrationThread.join(5000)
            saveThread.join(5000)
        }
        assertFalse(migrationThread.isAlive)
        assertFalse(saveThread.isAlive)
        assertTrue(migrated.get(1, TimeUnit.SECONDS).bandLimitBackupSaved)
        assertTrue(saved.get(1, TimeUnit.SECONDS))
        assertEquals(newState, PeqRepository(active, recoveryStore).load())
        assertEquals(raw, recoveryStore.getString(PeqRepository.KEY_LIMIT_BACKUP, null))
    }

    @Test fun readingOldPersonalLibraryKeepsOriginalBytesAndWriteBacksUpEveryEntryBeforeAdapting() {
        val file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        val original = library().toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val store = PeqPresetStore(file)
        val loaded = store.load()
        assertEquals(1, store.lastLoadedLegacyPresetCount)
        assertEquals(listOf(legacyId, otherId), loaded.map { it.id })
        assertEquals(legacyState().copy(bands = legacyState().bands.take(130)), loaded.first().state)
        assertEquals(PeqModel.defaultState(4), loaded.last().state)
        assertArrayEquals(original, file.readBytes())
        assertTrue(backups(file).isEmpty())
        val portable = store.exportJson()
        assertEquals(loaded.map { it.state }, PeqPresetCodec.decode(portable).map { it.state })
        assertArrayEquals(original, file.readBytes())
        val renamed = store.rename(legacyId, "保留前130段")
        assertEquals(loaded.first().copy(name = "保留前130段"), renamed)
        assertArrayEquals(original, backups(file).single().readBytes())
        assertEquals(listOf(renamed, loaded.last()), PeqPresetStore(file).load())
        assertEquals(0, store.lastLoadedLegacyPresetCount)
        assertEquals(listOf(130, 4), PeqPresetCodec.decode(file.readText()).map { it.state.bands.size })
    }

    @Test fun personalMigrationWriteFailureKeepsOriginalAndBackupAndCanBeRetried() {
        val file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        val original = library().toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val failing = PeqPresetStore(file) { output, bytes ->
            output.write(bytes, 0, 23)
            throw IOException("simulated full disk")
        }
        assertThrows(IOException::class.java) { failing.rename(legacyId, "新名字") }
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(original, backups(file).single().readBytes())
        val store = PeqPresetStore(file)
        assertEquals("旧多段预设", store.load().first().name)
        assertEquals(1, store.lastLoadedLegacyPresetCount)
        store.rename(legacyId, "成功重试")
        assertEquals("成功重试", store.load().first().name)
        assertArrayEquals(original, backups(file).single().readBytes())
    }

    @Test fun failedBackupRenamePreventsAnyWriteToOriginalPersonalLibrary() {
        val file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        val original = library().toByteArray(Charsets.UTF_8)
        file.writeBytes(original)
        val hash = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val blocked = File(file.path + ".before-130-$hash.json")
        assertTrue(blocked.mkdir())
        File(blocked, "occupied").writeText("keep")
        assertThrows(IOException::class.java) { PeqPresetStore(file).rename(legacyId, "不能写入") }
        assertArrayEquals(original, file.readBytes())
        assertEquals("旧多段预设", PeqPresetStore(file).load().first().name)
    }

    @Test fun invalidDiscardedTailAndBeyondOldLimitAreNotSilentlyReclassifiedAsValidLegacyData() {
        val original = legacyState()
        val malformed = listOf(
            original.copy(bands = original.bands.dropLast(1) + original.bands.last().copy(q = 0.0)),
            original.copy(bands = original.bands.dropLast(1) + original.bands.last().copy(id = original.bands.first().id)),
            legacyState(171)
        )
        val file = File(temporary.newFolder(), PeqPresetStore.FILE_NAME)
        malformed.forEach { invalid ->
            preferences.edit().clear().putString(PeqRepository.KEY_STATE, rawState(invalid)).commit()
            val before = preferences.all.toMap()
            assertTrue(PeqRepository(context).loadResult().corrupt)
            assertEquals(before, preferences.all)
            file.writeText(library(invalid), Charsets.UTF_8)
            val bytes = file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { PeqPresetStore(file).load() }
            assertThrows(IllegalArgumentException::class.java) { PeqPresetStore(file).rename(legacyId, "不能覆盖") }
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(backups(file).isEmpty())
        }
    }
}
