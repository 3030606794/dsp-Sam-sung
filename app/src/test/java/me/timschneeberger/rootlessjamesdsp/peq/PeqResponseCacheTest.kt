package me.timschneeberger.rootlessjamesdsp.peq

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Incremental previews must retain the full adaptive grid and its unclipped mathematical sum. */
class PeqResponseCacheTest {
    private fun bank(count: Int = 170): PeqState = PeqModel.defaultState(count).let { state ->
        state.copy(bands = state.bands.mapIndexed { index, band -> band.copy(
            gain = if (index % 2 == 0) 0.5 else -0.5, q = 3.0 + index % 7) })
    }

    private fun edited(state: PeqState, index: Int, edit: (PeqBand) -> PeqBand): PeqState =
        state.copy(bands = state.bands.mapIndexed { position, band -> if (position == index) edit(band) else band })

    private fun activeBands(state: PeqState, sampleRate: Double): Int = state.bands.map(PeqProtocol::quantized).count {
        it.enabled && it.frequency < sampleRate / 2.0 && !(it.type.isUnityAtZeroGain && it.gain == 0.0)
    }

    private fun assertMatchesFull(state: PeqState, actual: List<PeqResponse.Point>, points: Int,
                                  sampleRate: Double = 48000.0) {
        val expected = PeqResponse.adaptiveCurve(state, points, sampleRate)
        assertEquals("No adaptive frequency may be lost or substituted", expected.map { it.frequency }, actual.map { it.frequency })
        expected.zip(actual).forEach { (reference, cached) ->
            assertTrue("Non-finite response at ${cached.frequency} Hz", cached.db.isFinite())
            assertEquals("Response at ${cached.frequency} Hz", reference.db, cached.db, 1e-8)
        }
    }

    @Test fun oneEditedBandAmong170MatchesFullCurveWithDeterministicallyFewerEvaluations() {
        val cache = PeqResponse.PreviewCache()
        val initial = bank()
        val before = requireNotNull(cache.curve(initial, points = 1024))
        assertMatchesFull(initial, before, 1024)
        assertEquals(before.size.toLong() * 170, cache.lastFilterEvaluations)
        val next = edited(initial, 85) { it.copy(frequency = it.frequency + 11.25, q = 7.321, gain = 0.37) }
        val after = requireNotNull(cache.curve(next, points = 1024))
        assertMatchesFull(next, after, 1024)
        val previousFrequencies = before.map { it.frequency }.toSet()
        val retained = after.count { it.frequency in previousFrequencies }
        val new = after.size - retained
        val completeWork = after.size.toLong() * activeBands(next, 48000.0)
        val expectedWork = retained.toLong() * 2 + new.toLong() * 170
        assertEquals(expectedWork, cache.lastFilterEvaluations)
        assertTrue("Incremental work ${cache.lastFilterEvaluations} vs complete $completeWork",
            cache.lastFilterEvaluations < completeWork / 10)
        println("170-band preview: ${cache.lastFilterEvaluations} filter evaluations vs $completeWork full; ${after.size} adaptive points")
    }

    @Test fun allFilterTypesAndExtremeParametersMatchAcrossNyquistBoundaries() {
        val cache = PeqResponse.PreviewCache()
        val anchors = listOf(PeqBand(id = "anchor-low", frequency = 200.0, gain = 1.3),
            PeqBand(id = "anchor-high", frequency = 1200.0, gain = -0.7))
        for (rate in listOf(32000.0, 40001.0, 48000.0, 96000.0)) {
            for (type in PeqFilterType.entries) {
                for (frequency in listOf(20.0, 16000.0, 20000.0)) {
                    for (gain in listOf(-100.0, 0.0, 100.0)) {
                        for (q in listOf(PeqModel.MIN_Q, PeqModel.MAX_Q)) {
                            val state = PeqState(anchors + PeqBand("edited", type, true, frequency, gain, q))
                            assertMatchesFull(state, requireNotNull(cache.curve(state, 64, rate)), 64, rate)
                        }
                    }
                }
            }
        }
    }

    @Test fun highQCentersAndMovingNarrowExtremaRemainInTheIncrementalGrid() {
        val cache = PeqResponse.PreviewCache()
        val anchors = bank(12).bands
        for (type in listOf(PeqFilterType.PEAK, PeqFilterType.NOTCH, PeqFilterType.BAND_PASS,
            PeqFilterType.LOW_SHELF, PeqFilterType.HIGH_SHELF)) {
            for (frequency in listOf(1388.8894, 1390.1234, 19999.999)) {
                val band = PeqBand("narrow", type, true, frequency, 100.0, 100.0)
                val state = PeqState(anchors + band)
                val actual = requireNotNull(cache.curve(state, 32))
                assertTrue(actual.any { it.frequency == PeqProtocol.quantized(band).frequency })
                assertMatchesFull(state, actual, 32)
            }
        }
    }

    @Test fun removingAnExtremeCutRecoversTheUnclippedSumAboveTheDisplayFloor() {
        val cache = PeqResponse.PreviewCache()
        // Closely spaced unique cuts still overlap below the floor; 1000 Hz remains after removing cut-0.
        val state = PeqState(List(3) { PeqBand(id = "cut-$it", frequency = 999.999 + it * 0.001, gain = -100.0, q = 2.0) } +
            listOf(PeqBand(id = "anchor-1", frequency = 7000.0, gain = 6.0),
                PeqBand(id = "anchor-2", frequency = 500.0, gain = 1.0)))
        val before = requireNotNull(cache.curve(state, 128))
        assertEquals(PeqResponse.FLOOR_DB, before.single { it.frequency == 1000.0 }.db, 0.0)
        val next = state.copy(bands = state.bands.drop(1))
        val after = requireNotNull(cache.curve(next, 128))
        assertTrue(after.single { it.frequency == 1000.0 }.db > PeqResponse.FLOOR_DB)
        assertMatchesFull(next, after, 128)
        assertTrue(cache.lastFilterEvaluations < after.size.toLong() * 4)
    }

    @Test fun earlyPartialAndFinalCancellationNeverCommitAnIncompleteSnapshot() {
        val original = bank(20)
        val next = edited(original, 5) { it.copy(frequency = 7123.456, gain = 12.345, q = 100.0) }
        val totalNext = PeqResponse.adaptiveCurve(next, 128).size
        val finalCancellationCheck = 3 + (totalNext + 15) / 16
        for (cancelAt in listOf(1, 2, 4, finalCancellationCheck)) {
            val cache = PeqResponse.PreviewCache()
            val before = requireNotNull(cache.curve(original, 128))
            var checks = 0
            assertNull(cache.curve(next, 128, cancelled = { ++checks >= cancelAt }))
            assertEquals(cancelAt, checks)
            if (cancelAt >= 4) assertTrue("Must exercise cancellation after some work", cache.lastFilterEvaluations > 0)
            val unchanged = requireNotNull(cache.curve(original, 128))
            assertEquals(before, unchanged)
            assertEquals("A cancelled draft must leave all original samples available", 0, cache.lastFilterEvaluations)
            assertMatchesFull(next, requireNotNull(cache.curve(next, 128)), 128)
        }
    }

    @Test fun sampleRatePointCountAndDisplayOnlyChangesNeverReuseWrongCoefficients() {
        val cache = PeqResponse.PreviewCache()
        val state = bank(25)
        val initial = requireNotNull(cache.curve(state, 128))
        val display = state.copy(gainRange = 1.0, gainStep = 2.0, qStep = 100.0,
            frequencyStep = 1000.0, pinResponse = false,
            bands = state.bands.map { it.copy(qualityDisplay = PeqQualityDisplay.BW) })
        assertEquals(initial, cache.curve(display, 128))
        assertEquals(0, cache.lastFilterEvaluations)
        assertMatchesFull(display, requireNotNull(cache.curve(display, 257)), 257)
        for (rate in listOf(32000.0, 96000.0, 48000.0)) {
            val next = requireNotNull(cache.curve(display, 257, rate))
            assertEquals(next.size.toLong() * activeBands(display, rate), cache.lastFilterEvaluations)
            assertMatchesFull(display, next, 257, rate)
        }
        val empty = display.copy(bands = emptyList())
        assertTrue(requireNotNull(cache.curve(empty, 257)).all { it.db == 0.0 })
    }

    @Test fun longEditSequenceStaysAccurateAndPeriodicallyRebasesTheSum() {
        val cache = PeqResponse.PreviewCache()
        var state = bank(20)
        requireNotNull(cache.curve(state, 64))
        for (edit in 1..130) {
            state = edited(state, 10) { it.copy(gain = 1.0 + edit / 100.0) }
            val actual = requireNotNull(cache.curve(state, 64))
            assertMatchesFull(state, actual, 64)
            if (edit == 129) assertEquals(actual.size.toLong() * 20, cache.lastFilterEvaluations)
        }
    }

    @Test fun workerCompletesTheRunningFrameThenOnlyTheLatestPendingRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val revisions = CopyOnWriteArrayList<Int>()
        val cancellation = CopyOnWriteArrayList<Boolean>()
        val worker = PeqResponse.PreviewWorker(onComplete = { request, _ ->
            revisions.add(request.revision)
            completed.countDown()
        }, computeOverride = { request, cancelled ->
            if (request.revision == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            cancellation.add(cancelled())
            listOf(PeqResponse.Point(1000.0, request.revision.toDouble()))
        })
        try {
            val state = bank(1)
            worker.submit(PeqResponse.PreviewWorker.Request(state, 32, 1))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (revision in 2..100) worker.submit(PeqResponse.PreviewWorker.Request(state, 32, revision))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 100), revisions.toList())
            assertEquals(listOf(false, false), cancellation.toList())
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test fun closingWorkerCancelsRunningWorkAndSuppressesItsCompletion() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellation = AtomicBoolean(false)
        val callbacks = AtomicInteger()
        val computations = AtomicInteger()
        val worker = PeqResponse.PreviewWorker(onComplete = { _, _ -> callbacks.incrementAndGet() },
            computeOverride = { _, cancelled ->
                computations.incrementAndGet()
                entered.countDown()
                try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                cancellation.set(cancelled())
                exited.countDown()
                listOf(PeqResponse.Point(1000.0, 1.0))
            })
        try {
            val request = PeqResponse.PreviewWorker.Request(bank(1), 32, 1)
            worker.submit(request)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.close()
            release.countDown()
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            assertTrue(worker.isClosed)
            assertTrue(cancellation.get())
            assertEquals(0, callbacks.get())
            worker.submit(request.copy(revision = 2))
            assertEquals(1, computations.get())
        } finally {
            release.countDown()
            worker.close()
        }
    }
}
