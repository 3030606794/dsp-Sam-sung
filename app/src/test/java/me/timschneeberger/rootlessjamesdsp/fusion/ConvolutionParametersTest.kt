package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ConvolutionParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ConvolutionController.preferences(app)
    private val p=ConvolutionParameters
    @Before fun prepare(){prefs.edit().clear().commit();ConvolutionController.stopAudition(app);ConvolutionController.publishRuntime(app,emptyList());ConvolutionController.publishPrepared(app,null);ConvolutionController.publishCapabilities(app,8191)}
    @Test fun missingNewKeysAreNeutralAndNeverRewriteLegacyProfile() {
        prefs.edit().putString(p.LEGACY_ADVANCED,"-75;-98;17;0;2;0").putString(p.LEGACY_MODE,"1").commit()
        val before=prefs.all.toMap();p.normalize(prefs)
        assertEquals(before,prefs.all);assertFalse(p.requiresNewApp(prefs.all))
        assertArrayEquals(intArrayOf(1000,0,0,0,0,0,0,0,0,0),p.runtimePacket(prefs.all))
        assertFalse(p.enabled(prefs.all,p.WINDOW_ENABLED));assertTrue(p.enabled(prefs.all,p.NORM_ONLY_ATTENUATION));assertTrue(p.enabled(prefs.all,p.CHANNEL_LINK))
        assertArrayEquals(intArrayOf(-75,-98,17,0,2,0),p.legacy(prefs.all));assertFalse(prefs.getBoolean(p.ENABLED,false))
    }
    @Test fun newIntegerParsingAndCorruptNormalizationDoNotTouchOldText() {
        prefs.edit().putFloat(p.mix.key,1.5f).putInt(p.wet.key,121).putLong(p.left.key,-10).putString(p.WINDOW_ENABLED,"true")
            .putInt(p.NORM_MODE,2).putString(p.LEGACY_ADVANCED,"bad;old;text").commit()
        p.normalize(prefs)
        assertEquals(1000,prefs.getInt(p.mix.key,-1));assertEquals(0,prefs.getInt(p.wet.key,-1));assertEquals(-10,prefs.getInt(p.left.key,0))
        assertFalse(prefs.getBoolean(p.WINDOW_ENABLED,true));assertEquals(0,p.normMode(prefs.all));assertEquals("bad;old;text",p.legacyText(prefs.all));assertNull(p.legacy(prefs.all))
        assertEquals(1,p.mix.parse("0.1"));assertNull(p.mix.parse("0.01"));assertNull(p.mix.parse("NaN"));assertNull(p.wet.parse("12.1"))
        p.all.forEach {c->assertEquals(c.min,p.value(mapOf(c.key to c.min),c));assertEquals(c.max,p.value(mapOf(c.key to c.max),c))}
    }
    @Test fun controllerSendsRuntimeOnlyAndRearmsAuditionAfterConfiguration() {
        val id=Any();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.mix.key,251).putInt(p.predelay.key,23).commit()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(bulk()))))
        try {
            assertTrue(ConvolutionController.startAudition(app,this,3));val packets=mutableListOf<Pair<Int,IntArray>>()
            assertTrue(ConvolutionController.sync(app,8191,id){k,v->packets+=k to v.copyOf();true})
            assertEquals(listOf(1400,1401,1401),packets.map {it.first});assertEquals(listOf(0,3),packets.filter {it.first==1401}.map {it.second.single()})
            assertEquals(251,packets[0].second[0]);assertEquals(23,packets[0].second[3]);assertTrue(packets.none {it.first==1205})
            val old=ConvolutionController.pollState();ConvolutionController.sessionEnabled(app,id,false)
            assertEquals(0,ConvolutionController.audition)
            ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,64,ConvolutionRuntimeReader.decode(bulk()))),old.auditionRevision,old.runtimeRevision,old.sentRevisionToken)
            assertFalse(ConvolutionController.masterEnabled)
        }finally{ConvolutionController.sessionClosed(app,id)}
    }
    @Test fun oldDriverPreservesTargetsAndFailedConfigurationCancelsAudition() {
        val id=Any();prefs.edit().putBoolean(p.ENABLED,true).putInt(p.wet.key,25).commit()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(bulk()))))
        try {
            assertTrue(ConvolutionController.startAudition(app,this,2));assertFalse(ConvolutionController.sync(app,8191,id){k,_->k!=1400});assertEquals(0,ConvolutionController.audition)
            ConvolutionController.publishCapabilities(app,4095);var calls=0
            assertTrue(ConvolutionController.sync(app,4095,id){_,_->calls++;true});assertEquals(0,calls);assertEquals(25,prefs.getInt(p.wet.key,0))
            assertFalse(ConvolutionController.startAudition(app,this,1));assertTrue(prefs.all.keys.none {it.contains("audition")})
        }finally{ConvolutionController.sessionClosed(app,id)}
    }
    @Test fun nativeBulkRejectsTornUnknownAndInvalidPacketsAndKeepsInternalSampleRate() {
        val s=ConvolutionRuntimeReader.decode(bulk())!!;assertEquals(44100,s.rate);assertTrue(s.loaded);assertTrue(s.ready)
        assertEquals(-300,s.input.rms);assertEquals(1,s.delaySamples);assertEquals(23,s.delayUs)
        assertNull(ConvolutionRuntimeReader.decode(bulk().copyOf(31)))
        for((index,value) in listOf(0 to 2,1 to 1,2 to 0,3 to 16,4 to 3,7 to -1,9 to 17,10 to 4,11 to 1001,12 to 121,13 to 1,16 to 2,21 to 10001,25 to -10001,31 to -1))
            assertNull("field $index",ConvolutionRuntimeReader.decode(bulk().apply {this[index]=value}))
        val quiet=ConvolutionRuntimeReader.decode(bulk().apply {this[21]=Int.MIN_VALUE;this[22]=ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY})!!
        assertEquals(Int.MIN_VALUE,quiet.input.correlation);assertEquals(ConvolutionRuntimeReader.FOLD_NEGATIVE_INFINITY,quiet.input.fold)
    }
    @Test fun obsoleteNativeAuditionAcknowledgementCannotCancelNewOwner() {
        val id=Any();prefs.edit().putBoolean(p.ENABLED,true).commit()
        ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,0,ConvolutionRuntimeReader.decode(bulk()))))
        try {
            assertTrue(ConvolutionController.startAudition(app,this,1));ConvolutionController.sync(app,8191,id){_,_->true}
            val old=ConvolutionController.pollState();val other=Any();assertTrue(ConvolutionController.startAudition(app,other,3))
            ConvolutionController.stopAudition(app,this);assertEquals(3,ConvolutionController.audition)
            ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,48000,64,ConvolutionRuntimeReader.decode(bulk().apply {this[10]=1}))),old.auditionRevision,old.runtimeRevision,old.sentRevisionToken)
            assertEquals(3,ConvolutionController.audition)
        }finally{ConvolutionController.sessionClosed(app,id)}
    }
    @Test fun actualEndpointUsesIntegerSamplesEvenWhenItsMicrosecondsExceedSavedTarget() {
        val v=bulk().apply {this[2]=48003;this[7]=9601;this[8]=200008}
        val decoded=ConvolutionRuntimeReader.decode(v)!!;assertEquals(9601,decoded.delaySamples);assertEquals(200008,decoded.delayUs)
        assertNull(ConvolutionRuntimeReader.decode(v.copyOf().apply {this[8]=200000}))
        assertNull(ConvolutionRuntimeReader.decode(v.copyOf().apply {this[7]=9602;this[8]=200029}))
        assertEquals(200000,p.value(mapOf(p.predelay.key to 200000),p.predelay))
    }
    @Test fun idleHostRateKeepsKernelCrcAndRetainedDelayWithoutAFalseMeterWindow() {
        val idle=bulk().apply {this[2]=0;this[3]=3;this[7]=9601;this[8]=200008;this[31]=0}
        val decoded=ConvolutionRuntimeReader.decode(idle)!!
        assertEquals(0,decoded.rate);assertTrue(decoded.loaded);assertFalse(decoded.available);assertFalse(decoded.ready)
        assertEquals(1234,decoded.crc);assertEquals(9601,decoded.delaySamples);assertEquals(200008,decoded.delayUs)
        // Without a host rate the previous kernel's quantised fields need only meet absolute bounds.
        assertNotNull(ConvolutionRuntimeReader.decode(idle.copyOf().apply {this[8]=199999}))
        assertNotNull(ConvolutionRuntimeReader.decode(idle.copyOf().apply {this[7]=153600;this[8]=200000}))
        for((index,value) in listOf(1 to 3,2 to -1,2 to 7999,3 to 7,3 to 11,7 to 153601,8 to 200064,8 to -1))
            assertNull("idle field $index",ConvolutionRuntimeReader.decode(idle.copyOf().apply {this[index]=value}))
    }
    @Test fun idleLoadedCrcConfirmsTheStagedKernelWithoutClaimingActiveAudio() {
        val work=ConvolutionController.PreparedIr("stereo.irs",44100,48000,2,16383,17832,1234,
            status=ConvolutionController.Preparation.STAGED)
        ConvolutionController.publishPrepared(app,work)
        val id=Any()
        val idle=bulk().apply {this[2]=0;this[3]=3;this[5]=17832;this[31]=0}
        try {
            ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,0,0,ConvolutionRuntimeReader.decode(idle))))
            assertTrue(ConvolutionController.kernelConfirmed())
            assertEquals(ConvolutionController.Preparation.STAGED,ConvolutionController.prepared!!.status)
            assertFalse(ConvolutionController.runtime.single().snapshot!!.available)
            ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,0,0,ConvolutionRuntimeReader.decode(idle.copyOf().apply {this[6]=1235}))))
            assertFalse(ConvolutionController.kernelConfirmed())
            ConvolutionController.publishRuntime(app,listOf(ConvolutionController.Reading(id,true,0,0,ConvolutionRuntimeReader.decode(idle.copyOf().apply {this[3]=2}))))
            assertFalse(ConvolutionController.kernelConfirmed())
        }finally{ConvolutionController.sessionClosed(app,id);ConvolutionController.publishPrepared(app,null)}
    }
    companion object {
        fun bulk()=IntArray(32).apply {
            this[0]=1;this[1]=2;this[2]=44100;this[3]=15;this[4]=2;this[5]=4410;this[6]=1234;this[7]=1;this[8]=23;this[11]=1000
            for(i in listOf(19,23,27)){this[i]=-200;this[i+1]=-300;this[i+2]=10000;this[i+3]=0};this[31]=4410
        }
    }
}
