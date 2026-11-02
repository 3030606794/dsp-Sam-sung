package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test

class CrossfeedParametersTest {
    private val p=CrossfeedParameters
    @Test fun defaultsKeepLegacyAndDoNotEnableNewProcessing(){
        assertArrayEquals(intArrayOf(1,0,700,60,100,0,0,0),p.packet(emptyMap<String,Any>()))
        assertEquals(0,p.legacy(emptyMap<String,Any>()));assertFalse(p.requiresNewApp(emptyMap<String,Any>()))
        assertEquals("耳机交叉馈送",ViperParameters.groups.single {it.key=="v_cure"}.title)
    }
    @Test fun exactScaledInputAndBounds(){
        assertEquals(45,p.attenuation.parse("4.5"));assertEquals(46,p.attenuation.parse("4.6"))
        assertNull(p.attenuation.parse("4.55"));assertNull(p.delay.parse("0.5"))
        assertNull(p.output.parse("0.1"));assertNull(p.cutoff.parse("2001"))
        assertEquals(-120,p.output.parse("-12.0"));assertEquals(500,p.delay.parse("500"))
    }
    @Test fun corruptProfilesFallBackWithoutEnabling(){
        val v=mapOf(p.ENABLED to 1,p.cutoff.key to 999999,p.attenuation.key to 45.5,p.FILTER to "1",p.LEGACY to -1,p.mix.key to Double.NaN)
        assertArrayEquals(p.packet(emptyMap<String,Any>()),p.packet(v));assertEquals(0,p.legacy(v))
        assertTrue(p.requiresNewApp(v))
    }
    @Test fun portableSettingsCannotContainPowerOrAudition(){
        val s=p.capture(mapOf(p.ENABLED to true,p.delay.key to 137,p.attenuation.key to 46,p.FILTER to 1,"v_cure" to true,"crossfeed_audition" to 4))
        assertTrue(s.valid());assertEquals(p.newKeys+setOf(p.LEGACY)+CrossfeedAdvancedParameters.keys,s.values().keys)
        assertFalse(s.values().containsKey("v_cure"));assertFalse(s.copy(delay=501).valid())
    }
    @Test fun runtimeRejectsTornOrMalformedSnapshot(){
        val v=intArrayOf(1,2,48000,1,1,6,2,2100,-120,0,3,0,700,60,100,0,0,0,0,0)
        assertNotNull(p.decode(v));v[1]=3;assertNull(p.decode(v));v[1]=2;v[6]=4;assertNull(p.decode(v))
        assertNull(p.decode(IntArray(19)))
    }
}
