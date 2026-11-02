package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class DynamicPeqPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: DynamicPeqPresetStore
    private val state = DynamicPeqState(true, listOf(DynamicPeqBand("preset", frequency = 1234.567,
        threshold = -35.125, attack = 0.125, release = 1250.125)))

    @Before fun setup() {
        file = File(temporary.newFolder(), DynamicPeqPresetStore.FILE_NAME)
        store = DynamicPeqPresetStore(file)
    }

    @Test fun libraryOperationsAndExportsRetainEveryDynamicParameter() {
        assertTrue(store.load().isEmpty())
        assertFalse(file.exists())
        val saved = store.save("  人声柔化  ", state)
        assertEquals("人声柔化", saved.name)
        val renamed = store.rename(saved.id, "我的动态均衡")
        assertEquals(saved.id, renamed.id)
        val next = state.copy(bands = state.bands.map { it.copy(ratio = 8.125, range = 12.345) })
        val updated = store.overwrite(saved.id, next)
        assertEquals(next, DynamicPeqPresetStore(file).load().single().state)
        val portable = store.exportJson(saved.id)
        assertEquals(next, DynamicPeqPresetCodec.decode(portable).single().state)
        assertFalse(portable.contains(saved.id))
        val duplicate = store.importJson(portable).single()
        assertEquals("${updated.name} (2)", duplicate.name)
        assertNotEquals(saved.id, duplicate.id)
        store.delete(duplicate.id)
        assertEquals(listOf(updated), store.load())
    }

    @Test fun invalidImportAndWriteFailureRetainWorkingLibrary() {
        store.save("安全状态", state)
        val before = file.readBytes()
        val exported = store.exportJson()
        listOf(exported.replace(DynamicPeqPresetCodec.FORMAT, "dsp-audio-peq-presets"),
            exported.replace("\"attack\":0.125", "\"attack\":0.0"),
            "[".repeat(33) + "]".repeat(33)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { store.importJson(bad) }
            assertArrayEquals(before, file.readBytes())
        }
        val broken = DynamicPeqPresetStore(file) { stream, bytes ->
            stream.write(bytes, 0, bytes.size / 2)
            throw IOException("simulated disk failure")
        }
        assertThrows(IOException::class.java) { broken.save("不能写入", state) }
        assertArrayEquals(before, file.readBytes())
        assertEquals("安全状态", store.load().single().name)
    }

    @Test fun capacityIsCheckedBeforeImportOrMutation() {
        repeat(20) { store.save("预设 $it", state) }
        val before = file.readBytes()
        val incoming = DynamicPeqPresetCodec.encode(listOf(DynamicPeqPreset(UUID.randomUUID().toString(), "新预设", state)))
        assertThrows(IllegalArgumentException::class.java) { store.importJson(incoming) }
        assertThrows(IllegalArgumentException::class.java) { store.save("不能添加", state) }
        assertArrayEquals(before, file.readBytes())
    }
}
