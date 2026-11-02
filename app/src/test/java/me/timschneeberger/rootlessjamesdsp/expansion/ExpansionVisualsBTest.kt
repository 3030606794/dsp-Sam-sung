package me.timschneeberger.rootlessjamesdsp.expansion

import me.timschneeberger.rootlessjamesdsp.effectvisuals.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExpansionVisualsBTest {
    private fun runtime(kind:ExpansionEffectsKind, vararg changes:Pair<Int,Float>):ExpansionEffectsRuntime {
        val meters=MutableList(116){0f};changes.forEach{meters[it.first]=it.second}
        return ExpansionEffectsRuntime(kind,true,48000,4096,7,-9f,-12f,-18f,meters,true,true)
    }
    private fun settings(kind:ExpansionEffectsKind,vararg values:Pair<String,Int>) = values.associate { (key,value)->
        ExpansionEffectsParameters.control(kind,key).key to value }

    @Test fun everyControlHasFiniteGeometryAtItsOwnLimits() {
        ExpansionEffectsKind.values().filter{it.id in 10..20}.forEach { kind->
            ExpansionEffectsParameters.controls(kind).forEach { control->
                listOf(control.min,control.default,control.max).forEach { value->
                    val pair=ExpansionVisualsB.static(kind,mapOf(control.key to value),control.key)
                    assertNotNull("${kind.name} needs an explanatory illustration",pair.illustration.scene)
                    listOf(pair.professional,pair.illustration).forEach{visual->
                        visual.plot?.let{plot->
                            assertTrue("${kind.name} ${control.key}",plot.xMaximum>plot.xMinimum)
                            assertTrue(plot.yMaximum>plot.yMinimum)
                            (plot.curves.flatMap{it.points}.map{it.x to it.y}+plot.marks.map{it.x to it.y}).forEach{
                                assertTrue(it.first.isFinite());assertTrue(it.second.isFinite())
                            }
                        }
                        visual.scene?.elements?.filterIsInstance<FigurePolyline>()?.flatMap{it.points}?.forEach {
                            assertTrue(it.x.isFinite());assertTrue(it.y.isFinite())
                        }
                    }
                }
            }
        }
    }

    @Test fun missingMatchLearningNeverProducesAnInventedFrequencyCurve() {
        val kind=ExpansionEffectsKind.MATCH_EQ
        val pair=ExpansionVisualsB.static(kind,emptyMap<String,Any>(),"p5")
        assertTrue(requireNotNull(pair.professional.plot).curves.isEmpty())
        assertNull(ExpansionVisualsB.matchCorrection(emptyMap<String,Any>(),null))
        val live=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind,6 to 1f,7 to .35f),emptyList())
        assertNull(live.plot)
        assertTrue(requireNotNull(live.scene).elements.filterIsInstance<FigureLabel>().any{it.text=="35%"})
    }

    @Test fun matchingRemovesWholeRecordingLoudnessButPreservesSpectralDifference() {
        val kind=ExpansionEffectsKind.MATCH_EQ
        val values=settings(kind,"strength" to 1000,"smoothing" to 0,"max_gain" to 12000,"low" to 20000,"high" to 20000000)
        val flat=ExpansionVisualsB.matchCorrection(values,List(32){.04}+List(32){.01})!!
        assertTrue(flat.all{abs(it)<1e-8})
        val tonal=ExpansionVisualsB.matchCorrection(values,List(32){if(it<16).04 else .0025}+List(32){.01})!!
        assertTrue(tonal.take(12).all{it>5.9});assertTrue(tonal.takeLast(12).all{it< -5.9})
        val muted=ExpansionVisualsB.matchCorrection(values+settings(kind,"strength" to 0),List(32){if(it<16).04 else .0025}+List(32){.01})!!
        assertTrue(muted.all{abs(it)<1e-8})
    }

    @Test fun matchingDoesNotDiscardAnAlreadyCapturedReferenceDuringTargetLearning() {
        val kind=ExpansionEffectsKind.MATCH_EQ
        val s=runtime(kind,4 to 1f,6 to 2f,7 to .6f).copy(meters=runtime(kind,4 to 1f,6 to 2f,7 to .6f).meters.toMutableList().also{
            for(i in 20..51)it[i]=-30f
        })
        val live=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,s,emptyList())
        assertEquals(1,requireNotNull(live.plot).curves.size)
        assertTrue(live.subtitle.contains("60%"))
        assertTrue(live.plot!!.curves.single().points.all{it.y== -30.0})
    }

    @Test fun headTrackingWithoutHardwareNeverDrawsInventedMotion() {
        val kind=ExpansionEffectsKind.HEADTRACK
        val disconnected=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind,4 to 72f,8 to 0f),emptyList())
        assertTrue(disconnected.subtitle.contains("未连接"))
        val connected=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind,4 to 72f,7 to 30f,8 to 1f),emptyList())
        assertNotEquals(disconnected.scene,connected.scene)
        assertTrue(connected.subtitle.contains("72.0°"))
    }

    @Test fun actualPublishedResponseAndCaptureUnitsReachThePlotUnchanged() {
        val kind=ExpansionEffectsKind.DEREVERB
        val s=runtime(kind,4 to 2.5f).copy(meters=MutableList(116){index->if(index in 68..115)-7.25f else 0f})
        val live=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,s,emptyList())
        assertTrue(live.plot!!.curves.single().points.size in 2..48)
        assertEquals(46.875,live.plot!!.curves.single().points.first().x,1e-8)
        assertTrue(live.plot!!.curves.single().points.all{it.y== -7.25})
    }

    @Test fun vocalOutputGainAppliesInsideWetBranchAndMixZeroIsOriginal() {
        val kind=ExpansionEffectsKind.VOCAL
        val dry=ExpansionVisualsB.static(kind,settings(kind,"mix" to 0,"vocal_gain" to -60000,"accompaniment_gain" to 12000,"output" to 12000),"p0")
        dry.professional.plot!!.curves.flatMap{it.points}.forEach{assertEquals(it.x,it.y,1e-8)}
        val wet=ExpansionVisualsB.static(kind,settings(kind,"mix" to 1000,"vocal_gain" to -6000,"output" to 3000),"p1")
        wet.professional.plot!!.curves.first().points.forEach{assertEquals(it.x-3,it.y,1e-8)}
    }

    @Test fun repairedCounterResetDoesNotBecomeNegativeRepairEvents() {
        val kind=ExpansionEffectsKind.DECLICK
        fun frame(t:Long,count:Float)=EffectVisualFrame(t,MutableList(116){if(it==4)count else 0f},-12f,-12f,-12f,48000)
        val history=listOf(frame(1000,100f),frame(1200,105f),frame(1400,0f),frame(1600,3f))
        val live=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind,4 to 3f),history)
        assertEquals(listOf(5.0,3.0),live.plot!!.marks.map{it.y})
    }

    @Test fun targetCoefficientResponsesAreNotPresentedAsMeasuredAudioResponses() {
        listOf(ExpansionEffectsKind.MS_EQ,ExpansionEffectsKind.LOUDNESS).forEach{kind->
            val visual=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind),emptyList())
            assertTrue(visual.title.contains("目标"))
            assertTrue(visual.notes.any{it.contains("10 ms")&&it.contains("未含干湿混合")})
        }
    }

    @Test fun subharmonicEnvelopeAndLastSampleHaveDistinctAccurateLabels() {
        val kind=ExpansionEffectsKind.SUB_BASS
        val meters=MutableList(116){if(it==5)-12f else if(it==6)-36f else 0f}
        val frame=EffectVisualFrame(1000,meters,-18f,-18f,-18f,48000)
        val visual=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),"p4",runtime(kind),listOf(frame))
        assertEquals(listOf("合成包络","合成采样"),visual.plot!!.curves.map{it.label})
        assertEquals(listOf(-12.0,-36.0),visual.plot!!.curves.map{it.points.single().y})
        assertTrue(visual.notes.any{it.contains("不是该时间窗的峰值")})
    }

    @Test fun zeroMixNeverShowsStaleHeadPoseOrWaitsForVocalOutput() {
        listOf(ExpansionEffectsKind.HEADTRACK,ExpansionEffectsKind.VOCAL).forEach{kind->
            val visual=ExpansionVisualsB.live(kind,settings(kind,"mix" to 0),null,
                runtime(kind,4 to 75f,8 to 1f,10 to 0f),emptyList())
            assertEquals("混合 0% · 当前原声",visual.subtitle)
            assertNull(visual.plot)
            assertTrue(visual.scene!!.elements.filterIsInstance<FigureLabel>().any{it.text=="当前保持原声"})
        }
    }

    @Test fun fftTelemetryUsesRealBinFrequenciesWithoutInventingLowFrequencyResolution() {
        listOf(ExpansionEffectsKind.RESONANCE,ExpansionEffectsKind.DEREVERB).forEach{kind->
            listOf(8000,48000,192000).forEach{rate->
                val visual=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind).copy(sampleRate=rate),emptyList())
                val points=visual.plot!!.curves.single().points
                assertEquals(points.size,points.map{it.x}.distinct().size)
                points.forEach{point->
                    val bin=point.x*1024/rate
                    assertEquals(Math.rint(bin),bin,1e-8)
                    assertTrue(bin in 1.0..512.0)
                    assertTrue(point.x in visual.plot!!.xMinimum..visual.plot!!.xMaximum)
                }
                if(rate==48000)assertEquals(46.875,points.first().x,1e-8)
            }
        }
    }

    @Test fun lowSampleRateCoefficientResponsesStopAtNativeClamp() {
        listOf(ExpansionEffectsKind.MS_EQ,ExpansionEffectsKind.LOUDNESS).forEach{kind->
            val visual=ExpansionVisualsB.live(kind,emptyMap<String,Any>(),null,runtime(kind).copy(sampleRate=8000),emptyList())
            visual.plot!!.curves.forEach{curve->
                assertEquals(curve.points.size,curve.points.map{it.x}.distinct().size)
                assertTrue(curve.points.all{it.x<=3920.0003})
                assertEquals(3920.0,curve.points.last().x,.001)
            }
        }
    }

    @Test fun vocalExtremeAttenuationRemainsInsideVisibleAxes() {
        val kind=ExpansionEffectsKind.VOCAL
        val plot=ExpansionVisualsB.static(kind,settings(kind,"mix" to 1000,"vocal_gain" to -60000,"accompaniment_gain" to 12000,"output" to -60000),"p3").professional.plot!!
        assertTrue(plot.yMinimum<= -192.0)
        plot.curves.flatMap{it.points}.forEach{assertTrue(it.y in plot.yMinimum..plot.yMaximum)}
    }

    @Test fun eightCoincidentBellBandsRemainVisibleOnBothMidAndSideAxes() {
        val kind=ExpansionEffectsKind.MS_EQ
        val values=mutableMapOf<String,Int>()
        for(band in 0..7) {
            values.putAll(settings(kind,"mid_${band}_frequency" to 1000000,"mid_${band}_gain" to 18000,
                "side_${band}_frequency" to 1000000,"side_${band}_gain" to -18000))
        }
        // Each peaking section contributes its declared gain at its center: eight × 18 dB = 144 dB.
        val focus=ExpansionEffectsParameters.control(kind,"mid_0_gain").key
        val static=ExpansionVisualsB.static(kind,values,focus).professional.plot!!
        assertEquals(144.0,static.marks.single().y,1e-6)
        assertTrue(static.yMaximum>=144.0);assertTrue(static.yMinimum<= -144.0)
        static.curves.flatMap{it.points}.forEach{assertTrue(it.y in static.yMinimum..static.yMaximum)}
        val measured=runtime(kind).copy(meters=MutableList(116){when(it){in 20..67->143.75f;in 68..115->-143.75f;else->0f}})
        val live=ExpansionVisualsB.live(kind,values,focus,measured,emptyList()).plot!!
        assertTrue(live.yMaximum>=143.75);assertTrue(live.yMinimum<= -143.75)
        live.curves.flatMap{it.points}.forEach{assertTrue(it.y in live.yMinimum..live.yMaximum)}
    }

    @Test fun transientNeutralAndDryOnlySettingsDoNotVisuallyAlterTheReferenceDrum() {
        val kind=ExpansionEffectsKind.TRANSIENT
        listOf(emptyMap<String,Int>(),settings(kind,"mix" to 0,"mid_attack" to 18000,"mid_sustain" to -18000)).forEach{values->
            val pair=ExpansionVisualsB.static(kind,values,ExpansionEffectsParameters.control(kind,"mid_attack").key)
            val paths=pair.illustration.scene!!.elements.filterIsInstance<FigurePolyline>()
            assertEquals(2,paths.size)
            assertEquals("Neutral or dry-only processing must preserve the reference shape",paths[0].points,paths[1].points)
        }
        val altered=ExpansionVisualsB.static(kind,settings(kind,"mid_attack" to 6000),ExpansionEffectsParameters.control(kind,"mid_attack").key)
            .illustration.scene!!.elements.filterIsInstance<FigurePolyline>()
        assertNotEquals(altered[0].points,altered[1].points)
    }
}
