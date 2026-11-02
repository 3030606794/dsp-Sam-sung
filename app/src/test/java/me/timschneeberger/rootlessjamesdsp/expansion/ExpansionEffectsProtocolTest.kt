package me.timschneeberger.rootlessjamesdsp.expansion

import org.junit.Assert.*
import org.junit.Test

class ExpansionEffectsProtocolTest {
    @Test fun allDefaultPacketsMatchTheSeparateNativeProcessorCatalog() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/expansion/native-default-packets.txt"))
            .bufferedReader().use { it.readLines() }
        assertEquals(21, rows.size)
        rows.forEach { line ->
            val columns=line.split('|')
            val kind=ExpansionEffectsKind.values().first { it.id==columns[0].toInt() }
            val expected=columns[1].split(',').map(String::toInt).toIntArray()
            assertArrayEquals(kind.name,expected,ExpansionEffectsParameters.packet(kind,emptyMap<String,Any>()))
        }
    }
    @Test fun allModulesUseIndependentNamespacesAndPreserveOriginalTransportIds() {
        val kinds = ExpansionEffectsKind.values()
        assertEquals(23, kinds.size)
        assertEquals((196740..196760).toSet()+setOf(196762,196763), kinds.map { it.parameter }.toSet())
        assertEquals((132021..132041).toSet()+setOf(132043,132045), kinds.map { it.query }.toSet())
        assertEquals(23, kinds.map { it.prefsName }.toSet().size)
        assertEquals(23, kinds.map { it.enableKey }.toSet().size)
        kinds.forEach { kind ->
            val packet = ExpansionEffectsParameters.packet(kind, emptyMap<String, Any>())
            assertEquals(128, packet.size); assertEquals(1, packet[0]); assertEquals(0, packet[1])
            val count = ExpansionEffectsParameters.allControls(kind).size
            assertTrue(packet.drop(2 + count).all { it == 0 })
            assertTrue(ExpansionEffectsParameters.basic(kind).size in 2..3)
        }
    }
    @Test fun physicalUnitsMatchNativeFixedPointContract() {
        val micro = ExpansionEffectsParameters.packet(ExpansionEffectsKind.MICROSHIFT, emptyMap<String, Any>())
        assertArrayEquals(intArrayOf(1,0,350,9000,18000,1000,250,2000,12000000),micro.take(9).toIntArray())
        val vocal = ExpansionEffectsParameters.packet(ExpansionEffectsKind.VOCAL, mapOf("p1" to -60000,"p2" to 12000))
        assertEquals(-60000,vocal[3]); assertEquals(12000,vocal[4])
        val phase=ExpansionEffectsParameters.packet(ExpansionEffectsKind.LOW_PHASE,emptyMap<String,Any>())
        assertEquals(100000,phase[3]);assertEquals(2000,phase[5])
        val wow=ExpansionEffectsParameters.control(ExpansionEffectsKind.TAPE,"wow")
        val flutter=ExpansionEffectsParameters.control(ExpansionEffectsKind.TAPE,"flutter")
        assertEquals("0.15 %",wow.label(wow.default));assertEquals("0.05 %",flutter.label(flutter.default))
    }
    @Test fun midSideContainsSixteenTrulySeparateBands() {
        val kind=ExpansionEffectsKind.MS_EQ
        val controls=ExpansionEffectsParameters.allControls(kind)
        assertEquals(82,controls.size)
        val original=ExpansionEffectsParameters.packet(kind,emptyMap<String,Any>())
        val mid=ExpansionEffectsParameters.control(kind,"mid_3_gain")
        val side=ExpansionEffectsParameters.control(kind,"side_3_gain")
        val edited=ExpansionEffectsParameters.packet(kind,mapOf(mid.key to 4500,side.key to -7250))
        val changed=edited.indices.filter { edited[it]!=original[it] }
        assertEquals(listOf(mid.key.drop(1).toInt()+2,side.key.drop(1).toInt()+2),changed)
        assertEquals(4500,edited[changed[0]]);assertEquals(-7250,edited[changed[1]])
    }
    @Test fun discreteControlsRejectInvalidChoicesBeforeNativeTransport() {
        val vocoder = ExpansionEffectsParameters.packet(ExpansionEffectsKind.VOCODER,mapOf("p3" to 9000))
        assertEquals(16000,vocoder[5])
        assertEquals(setOf(8000,16000,24000), ExpansionEffectsParameters.options(ExpansionEffectsKind.VOCODER,"p3").keys)
        val hum=ExpansionEffectsParameters.packet(ExpansionEffectsKind.DEHUM,mapOf("p1" to 55000))
        assertEquals(50000,hum[3])
        val ms=ExpansionEffectsParameters.packet(ExpansionEffectsKind.MS_EQ,mapOf("p2" to 500,"p3" to 4000))
        assertEquals(1000,ms[4]);assertEquals(0,ms[5])
    }
    @Test fun corruptCrossoversNeverProduceAnInvertedNativeRange() {
        ExpansionEffectsKind.values().forEach { kind ->
            val range=ExpansionEffectsParameters.linkedRange(kind) ?: return@forEach
            val low=ExpansionEffectsParameters.control(kind,range.first)
            val high=ExpansionEffectsParameters.control(kind,range.second)
            val packet=ExpansionEffectsParameters.packet(kind,mapOf(low.key to low.max,high.key to high.min))
            assertTrue(packet[high.key.drop(1).toInt()+2]>=packet[low.key.drop(1).toInt()+2]+range.third)
        }
    }
    @Test fun headPoseDoesNotInvalidateConfirmedConfigurationHash() {
        val kind=ExpansionEffectsKind.HEADTRACK
        val first=ExpansionEffectsParameters.packet(kind,mapOf(kind.enableKey to true))
        val moved=first.copyOf().apply { this[7]=43000;this[8]=-12000;this[10]=1000;this[11]=8000 }
        assertEquals(ExpansionEffectsParameters.hash(kind,first),ExpansionEffectsParameters.hash(kind,moved))
        moved[3]=45000
        assertNotEquals(ExpansionEffectsParameters.hash(kind,first),ExpansionEffectsParameters.hash(kind,moved))
    }
    @Test fun ieeeLearnedProfilePreservesSmallPowersAndRejectsInvalidInput() {
        val data=FloatArray(64){10.0.powForTest(-it/8.0).toFloat()}
        val encoded=data.map { it.toBits() }.joinToString(",")
        val decoded=requireNotNull(ExpansionEffectsController.matchProfile(encoded))
        assertArrayEquals(data.map { it.toBits() }.toIntArray(),decoded)
        listOf(Float.NaN,Float.POSITIVE_INFINITY,-.1f,10000.1f).forEach { bad ->
            assertNull(ExpansionEffectsController.matchProfile(IntArray(64){bad.toBits()}.joinToString(",")))
        }
        assertNull(ExpansionEffectsController.matchProfile("1,2,3"))
    }
    @Test fun telemetryRetainsNativeUnitsAndDoesNotInventProcessingEvidence() {
        val words=IntArray(128).apply {this[0]=1;this[1]=1;this[2]=48000;this[3]=512;this[4]=17;this[5]=-12345;this[16]=1250500}
        val runtime=requireNotNull(ExpansionEffectsParameters.decode(ExpansionEffectsKind.AUTO_WAH,words))
        assertEquals(-12.345f,runtime.inputPeakDb,.0001f)
        assertEquals(1250.5f,runtime.meter(4),.0001f)
        assertFalse(runtime.engineEnabled);assertFalse(runtime.isProcessing)
        assertNull(ExpansionEffectsParameters.decode(ExpansionEffectsKind.AUTO_WAH,words.copyOf(127)))
    }
    @Test fun everyProfessionalModelHasFiniteLabeledGeometryAtDefaultsAndLimits() {
        ExpansionEffectsKind.values().forEach { kind ->
            val controls=ExpansionEffectsParameters.controls(kind)
            listOf(emptyMap<String,Any>(), controls.associate { it.key to it.min },controls.associate { it.key to it.max }).forEach { values ->
                val pair=ExpansionEffectsVisuals.static(kind,values,controls.first().key)
                listOf(pair.professional,pair.illustration).forEach { visual ->
                    assertTrue(visual.title.isNotBlank());assertTrue(visual.plot!=null||visual.scene!=null)
                    visual.plot?.let { plot ->
                        assertTrue(plot.yMaximum>plot.yMinimum)
                        plot.curves.flatMap { it.points }.forEach { assertTrue(it.x.isFinite());assertTrue(it.y.isFinite()) }
                    }
                }
            }
        }
    }
    private fun Double.powForTest(exponent:Double)=Math.pow(this,exponent)
}
