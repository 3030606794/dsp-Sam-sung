package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh

/** Values below come from the supplied APK's tables and independently verified generator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowPosixAtomicFile::class])
class PeqSystemPresetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var presets: List<PeqSystemPreset>

    @Before fun loadLibrary() { presets = PeqSystemPresets.load(app) }

    @Test fun bundledDefaultsIncludeEveryOriginalCategoryInOrderWithoutAutoEqEntries() {
        assertEquals(74, presets.size)
        assertEquals(3098, presets.sumOf { it.bands.size })
        assertEquals(74, presets.map { it.id }.toSet().size)
        assertEquals(74, presets.map { it.name }.toSet().size)
        val parametric = presets.filter { it.category == "parametric" }
        val graphic = presets.filter { it.category == "graphic" }
        assertEquals(29, parametric.size)
        assertEquals(45, graphic.size)
        assertEquals(listOf(4) + (4..20) + (30..130 step 10), parametric.map { it.bands.size })
        assertEquals(List(21) { 10 } + (20..130 step 10).flatMap { listOf(it, it) },
            graphic.map { it.bands.size })
        assertEquals(parametric + graphic, presets)
        assertEquals("4-Band Parametric (Default)", presets.first().name)
        assertEquals("Flat 20k (130-Band Graphic)", presets.last().name)
        assertEquals(listOf("Flat", "Acoustic", "Classical", "Dance", "Deep", "Electronic",
            "Hip Hop", "Jazz", "Latin", "Loudness", "Lounge", "Piano", "Pop", "R&B", "Rock",
            "Vocal", "Speech", "Bass Booster", "Bass Reducer", "Treble Booster", "Treble Reducer"),
            graphic.take(21).map { it.name.removeSuffix(" (10-Band Graphic)") })
    }

    @Test fun theTwoFourBandTemplatesRetainDifferentShelfFrequenciesAndPeakBandwidths() {
        val original = presets.single { it.name == "4-Band Parametric (Default)" }
        val alternate = presets.single { it.name == "4-Band Parametric" }
        assertEquals(listOf(250.0, 500.0, 3000.0, 6000.0), original.bands.map { it.frequency })
        assertEquals(listOf(100.0, 250.0, 2500.0, 10000.0), alternate.bands.map { it.frequency })
        assertEquals(listOf(null, 1.0, 1.0, null), original.sourceBandwidths)
        assertEquals(listOf(null, 1.25, 2.0, null), alternate.sourceBandwidths)
        for (preset in listOf(original, alternate)) {
            assertEquals(listOf(PeqFilterType.LOW_SHELF, PeqFilterType.PEAK,
                PeqFilterType.PEAK, PeqFilterType.HIGH_SHELF), preset.bands.map { it.type })
            assertEquals(listOf(PeqQualityDisplay.Q, PeqQualityDisplay.BW,
                PeqQualityDisplay.BW, PeqQualityDisplay.Q), preset.bands.map { it.qualityDisplay })
            assertEquals(0.7071067690849304, preset.bands.first().q, 0.0)
            assertEquals(0.7071067690849304, preset.bands.last().q, 0.0)
            assertTrue(preset.bands.all { it.enabled && it.gain == 0.0 })
        }
    }

    @Test fun sourceOctaveBandwidthRoundTripsAt48kWithoutBeingMistakenForQ() {
        var bandwidthCount = 0
        for (preset in presets) {
            preset.bands.forEachIndexed { index, band ->
                val bandwidth = preset.sourceBandwidths[index]
                if (bandwidth != null) {
                    bandwidthCount++
                    assertEquals(PeqQualityDisplay.BW, band.qualityDisplay)
                    assertEquals(bandwidth, PeqResponse.bandwidthOctaves(band.q, band.frequency, 48000.0), 1e-12)
                    assertEquals(nativeEquivalentQ(bandwidth, band.frequency), band.q, 1e-12)
                } else {
                    assertEquals(PeqQualityDisplay.Q, band.qualityDisplay)
                }
            }
        }
        assertEquals(3040, bandwidthCount) // The 29 parametric presets each have two Q-mode shelves.
        val flat = presets.single { it.name == "Flat (10-Band Graphic)" }
        assertEquals(1.0, flat.sourceBandwidths.last()!!, 0.0)
        assertEquals(0.532032414455131, flat.bands.last().q, 1e-12)
        assertTrue(abs(flat.bands.first().q - flat.bands.last().q) > 0.8)
    }

    @Test fun adapting26Sub20HzBandsPreservesSourcesAndRecomputesQFromTheirOriginalBandwidth() {
        val adapted = presets.filter { it.adaptedFrequencyCount > 0 }
        assertEquals(11, adapted.size)
        assertEquals(26, adapted.sumOf { it.adaptedFrequencyCount })
        assertEquals(16.480819702148438, presets.flatMap { it.bands }.minOf { it.frequency }, 0.0)
        for (preset in adapted) {
            val original = preset.bands.toList()
            val next = preset.applyTo(PeqModel.defaultState())
            assertEquals(original, preset.bands)
            assertEquals(original.size, next.bands.size)
            original.zip(next.bands).forEachIndexed { index, (source, actual) ->
                if (source.frequency < 20.0) {
                    assertEquals(20.0, actual.frequency, 0.0)
                    assertEquals(nativeEquivalentQ(preset.sourceBandwidths[index]!!, 20.0), actual.q, 1e-12)
                    assertTrue("Adapted frequency must not reuse Q for the old frequency", abs(source.q - actual.q) > 1e-9)
                } else {
                    assertEquals(source.frequency, actual.frequency, 0.0)
                    assertEquals(source.q, actual.q, 1e-12)
                }
                assertEquals(source.gain, actual.gain, 0.0)
                assertEquals(source.type, actual.type)
                assertEquals(source.qualityDisplay, actual.qualityDisplay)
            }
        }
    }

    @Test fun everyAppliedPresetSurvivesRepositoryRoundTripAndProducesValidDriverUnits() {
        val repository = PeqRepository(app)
        for (preset in presets) {
            val applied = preset.applyTo(PeqModel.defaultState())
            assertSame(applied, PeqModel.validate(applied))
            assertTrue(repository.save(applied))
            val restored = repository.load()
            assertEquals(applied, restored)
            val packet = PeqProtocol.payload(restored, true)
            assertArrayEquals(intArrayOf(1, 1, preset.bands.size), packet.take(3).toIntArray())
            assertEquals(3 + 5 * restored.bands.size, packet.size)
            restored.bands.forEachIndexed { index, band ->
                val offset = 3 + 5 * index
                assertEquals(band.type.wireId, packet[offset])
                assertEquals(1, packet[offset + 1])
                assertEquals((band.frequency * 1000).roundToInt(), packet[offset + 2])
                assertEquals((band.gain * 1000).roundToInt(), packet[offset + 3])
                assertEquals((band.q * 1000).roundToInt(), packet[offset + 4])
                assertTrue(packet[offset + 2] in 20000..20000000)
                assertTrue(packet[offset + 4] in 100..100000)
            }
        }
    }

    @Test fun repeatApplicationsCreateIndependentIdsRetainEditorPreferencesAndWidenOnlyNecessaryRanges() {
        val current = PeqModel.defaultState(1).copy(gainRange = 1.0, qRange = 0.5,
            gainStep = 0.07, qStep = 0.03, frequencyStep = 13.0,
            showQSlider = false, showFrequencySlider = false, pinResponse = false)
        val narrow = presets.single { it.name == "130-Band Parametric" }
        val first = narrow.applyTo(current)
        val second = narrow.applyTo(first)
        assertEquals(24.0, first.qRange, 0.0)
        assertEquals(1.0, first.gainRange, 0.0)
        assertTrue(first.bands.map { it.id }.toSet().intersect(second.bands.map { it.id }.toSet()).isEmpty())
        assertTrue(first.bands.map { it.id }.toSet().intersect(narrow.bands.map { it.id }.toSet()).isEmpty())
        assertTrue(first.bands.map { it.id }.toSet().intersect(current.bands.map { it.id }.toSet()).isEmpty())
        val dance = presets.single { it.name == "Dance (10-Band Graphic)" }.applyTo(second)
        assertEquals(4.0, dance.gainRange, 0.0)
        assertEquals(24.0, dance.qRange, 0.0)
        assertEquals(current.copy(bands = dance.bands, gainRange = 4.0, qRange = 24.0), dance)
        val large = current.copy(gainRange = 87.0, qRange = 91.0)
        assertEquals(large.copy(bands = emptyList()), narrow.applyTo(large).copy(bands = emptyList()))
    }

    @Test fun applyingSystemTemplatesLeavesThePersonalLibraryEffectPowerAndOtherPreferencesUntouched() {
        val prefs = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
        val enabledKey = app.getString(R.string.key_geq_enable)
        prefs.edit().clear().putBoolean(enabledKey, false).putInt("system_output_volume", 37).commit()
        val file = File(temporary.newFolder(), "peq-presets.json")
        val store = PeqPresetStore(file)
        val original = PeqModel.defaultState(3)
        val saved = store.save("我的调音", original)
        val libraryBytes = file.readBytes()
        val dance = presets.single { it.name == "Dance (10-Band Graphic)" }
        assertEquals(-3.5, dance.sourcePreampDb, 0.0)
        val next = dance.applyTo(original)
        assertTrue(PeqRepository(app).save(next))
        assertArrayEquals(libraryBytes, file.readBytes())
        assertEquals(listOf(saved), store.load())
        assertFalse(prefs.getBoolean(enabledKey, true))
        assertEquals(37, prefs.getInt("system_output_volume", -1))
        assertEquals(dance.bands.map { it.gain }, next.bands.map { it.gain })
    }

    /** Algebraically equate Neutron's Q and BW alpha expressions, independent of app conversion. */
    private fun nativeEquivalentQ(bandwidth: Double, frequency: Double): Double {
        val omega = 2.0 * PI * frequency / 48000.0
        return 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * bandwidth * omega / sin(omega)))
    }
}