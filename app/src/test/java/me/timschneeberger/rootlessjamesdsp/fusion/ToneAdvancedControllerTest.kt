package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],application=Application::class)
class ToneAdvancedControllerTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=ToneAdvancedParameters; private val c=ToneAdvancedController
    private val prefs get()=c.preferences(app)
    private val identity=Any(); private val owner=Any(); private val caps=1
    @Before fun prepare() {
        c.stopAudition(app,clearSnapshots=true); c.publishRuntime(app,emptyList()); prefs.edit().clear().commit()
        c.publishCapabilities(app,caps); p.apply(prefs,p.defaults().copy(professional=true)); prefs.edit().putBoolean(p.ENABLED,true).commit()
    }
    @After fun cleanup() { c.sessionClosed(app,identity); c.stopAudition(app,clearSnapshots=true); c.publishRuntime(app,emptyList()) }
    private fun reading(frames: Int,mode: Int=c.audition,stage: Int=0,epoch: Int=c.epoch,ready: Boolean=true,rate: Int=48000): ToneAdvancedController.Reading {
        val v=IntArray(64).apply { this[0]=1;this[1]=2;this[2]=rate;this[3]=1;this[4]=1;this[5]=mode;this[7]=stage
            this[11]=epoch;this[13]=if(ready) 1 else 0;this[14]=100;this[31]=2000;this[35]=50;this[36]=70;this[37]=20;this[38]=45;this[39]=-800 }
        return ToneAdvancedController.Reading(identity,true,frames,rate,p.decode(v))
    }
    private fun active() { c.publishRuntime(app,listOf(reading(0))); c.publishRuntime(app,listOf(reading(512))) }
    @Test fun configurationAtomicallyCarriesBasisPowerThenToolsWithoutLegacyReplay() {
        p.apply(prefs,p.defaults().copy(professional=true,coreOn=true,delta=List(10){-237}))
        val packets=linkedMapOf<Int,IntArray>()
        assertTrue(c.sync(app,caps,identity) { id,v -> packets[id]=v;true })
        assertEquals(listOf(p.PACKET,p.TOOLS),packets.keys.toList()); assertEquals(80,packets[p.PACKET]!!.size)
        assertEquals(1,packets[p.PACKET]!![2]);assertEquals(164,packets[p.TOOLS]!!.size)
        prefs.edit().putBoolean(p.ENABLED,false).commit();c.sync(app,caps,identity) { id,v -> packets[id]=v;true }
        assertEquals(0,packets[p.PACKET]!![2]);assertFalse(p.moduleEnabled(prefs.all))
    }
    @Test fun oldDriverReceivesNoNewCommandsAndEachSendFailureStopsTools() {
        val sent=mutableListOf<Int>();assertTrue(c.sync(app,0,identity) { id,_ -> sent+=id;true });assertTrue(sent.isEmpty())
        active();for(bad in listOf(p.PACKET,p.TOOLS)) {
            assertTrue(c.startAudition(app,owner,3));assertFalse(c.sync(app,caps,identity) { id,_ -> id!=bad });assertEquals(0,c.audition)
        }
    }
    @Test fun activityReadinessAndProfessionalModuleStateGateListening() {
        assertFalse(c.startAudition(app,owner,1));active()
        for(mode in listOf(1,2,3)) assertTrue(c.startAudition(app,owner,mode))
        assertFalse(c.startAudition(app,owner,9));c.publishRuntime(app,listOf(reading(1024,ready=false)));assertEquals(0,c.audition)
        active();prefs.edit().putBoolean(p.ENABLED,false).commit();c.sync(app,caps,identity) { _,_ -> true };assertFalse(c.canListen(app))
        prefs.edit().putBoolean(p.ENABLED,true).commit();active();assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1));p.setProfessional(prefs,false);assertTrue(c.sync(app,caps,identity){_,_->true});assertFalse(c.hasComparison(0));assertFalse(c.hasComparison(1));assertEquals(0,c.audition)
    }
    @Test fun originalSnapshotRetainsSelectedPresetAndStrengthWithoutActivatingFineValues() {
        p.apply(prefs,p.defaults().copy(base=List(10){13},baseRange="40",coreOn=true,amount=1317))
        assertTrue(c.saveComparison(app,owner,0));p.setProfessional(prefs,true);assertTrue(c.saveComparison(app,owner,1))
        val packets=linkedMapOf<Int,IntArray>();c.sync(app,caps,identity) { id,v -> packets[id]=v;true }
        assertArrayEquals(p.defaults().copy(base=List(10){13},baseRange="40",coreOn=true,amount=1317).packet(true),packets[p.TOOLS]!!.copyOfRange(4,84))
        assertArrayEquals(p.capture(prefs.all).packet(true),packets[p.TOOLS]!!.copyOfRange(84,164))
    }
    @Test fun abMatchingRequiresBothSnapshotsWarmupAndTwoSecondMeasurement() {
        active();assertFalse(c.measure(app,owner));assertFalse(c.startAudition(app,owner,4))
        c.saveComparison(app,owner,0);prefs.edit().putInt(p.amount.key,1317).commit();c.saveComparison(app,owner,1)
        assertTrue(c.measure(app,owner));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(1024,6,1)));assertFalse(c.matchingReady())
        c.publishRuntime(app,listOf(reading(1536,6,2)));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(2048,6,3)));assertTrue(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,4))
        assertFalse(prefs.all.keys.any { it.contains("snapshot") || it.contains("audition") || it.contains("match") })
    }
    @Test fun silenceNewSnapshotAndRemeasurementInvalidateOldMatch() {
        active();c.saveComparison(app,owner,0);c.saveComparison(app,owner,1);c.measure(app,owner)
        c.publishRuntime(app,listOf(reading(1024,6,4)));assertFalse(c.setMatch(app,true))
        c.publishRuntime(app,listOf(reading(1536,6,3)));assertTrue(c.setMatch(app,true));val epoch=c.epoch
        c.measure(app,owner);assertNotEquals(epoch,c.epoch);assertFalse(c.match);c.saveComparison(app,owner,1);assertFalse(c.matchingReady())
    }
    @Test fun anyAudibleNamespaceChangeStopsListeningAndCapabilityLossClearsSnapshots() {
        active();c.sync(app,caps,identity) { _,_ -> true };c.saveComparison(app,owner,0);c.startAudition(app,owner,2)
        PreferenceCache.getPreferences(app,Constants.PREF_TUBE).edit().putBoolean("tube_enable",true).commit()
        c.sync(app,caps,identity) { _,_ -> true };assertEquals(0,c.audition);assertTrue(c.hasComparison(0))
        c.publishCapabilities(app,0);assertFalse(c.hasComparison(0));assertFalse(c.supported(app))
    }
    @Test fun stalePollingCannotReactivateClosedSessionAndRateChangesStopListening() {
        active();c.startAudition(app,owner,2);val poll=c.pollState();c.sessionClosed(app,identity)
        c.publishRuntime(app,listOf(reading(1024,2)),poll);assertEquals(0,c.audition);assertFalse(c.canListen(app))
        c.sessionEnabled(app,identity,false);c.publishRuntime(app,listOf(reading(1536)));assertFalse(c.canListen(app))
        c.sessionEnabled(app,identity,true);c.publishRuntime(app,listOf(reading(2048)));assertFalse(c.canListen(app))
        c.publishRuntime(app,listOf(reading(2560)));assertTrue(c.canListen(app));c.startAudition(app,owner,1)
        c.publishRuntime(app,listOf(reading(3072,1,rate=44100)));assertEquals(0,c.audition);assertFalse(c.hasComparison(0))
    }
    @Test fun meterAndGraphVisibilityDoNotInvalidateMatchedComparisonButAudioChangesDo() {
        active();c.saveComparison(app,owner,0);c.saveComparison(app,owner,1);c.measure(app,owner)
        c.publishRuntime(app,listOf(reading(1024,6,3)));assertTrue(c.setMatch(app,true));assertTrue(c.startAudition(app,owner,4))
        c.sync(app,caps,identity) { _,_ -> true };val epoch=c.epoch
        for(key in listOf(p.METERS,p.RESPONSE)) for(on in listOf(true,false)) {
            prefs.edit().putBoolean(key,on).commit();val packets=linkedMapOf<Int,IntArray>();c.sync(app,caps,identity) { id,v -> packets[id]=v;true }
            assertEquals(4,c.audition);assertTrue(c.match);assertEquals(epoch,c.epoch);assertTrue(c.matchingReady())
            assertEquals(if(on) 1 else 0,packets[p.PACKET]!![if(key==p.METERS) 70 else 71])
        }
        prefs.edit().putInt(p.delta[0].key,137).commit();c.sync(app,caps,identity) { _,_ -> true };assertEquals(0,c.audition)
    }
    @Test fun invalidBasisClearsToolsWithoutReplacingValidProcessingOrTriggeringRecovery() {
        active();c.saveComparison(app,owner,0);c.startAudition(app,owner,2)
        prefs.edit().putString(p.baseKeys[0],"malformed").commit()
        val sent=linkedMapOf<Int,IntArray>();assertTrue(c.sync(app,caps,identity){id,v->sent[id]=v;true})
        assertEquals(listOf(p.CLEAR),sent.keys.toList());assertArrayEquals(intArrayOf(0),sent[p.CLEAR]);assertEquals(0,c.audition);assertFalse(c.hasComparison(0));assertFalse(c.saveComparison(app,owner,0))
        assertEquals("malformed",prefs.getString(p.baseKeys[0],null));assertFalse(c.canListen(app))
        sent.clear();prefs.edit().putBoolean(p.ENABLED,false).commit();assertTrue(c.sync(app,caps,identity){id,v->sent[id]=v;true});assertEquals(listOf(p.CLEAR,p.OFF),sent.keys.toList());assertArrayEquals(intArrayOf(0),sent[p.OFF])
        assertFalse(c.sync(app,caps,identity){_,_->false})
    }

    @Test fun closingOneOfSeveralSessionsClearsSnapshotsAndMatch() {
        val second=Any();active();c.publishRuntime(app,listOf(reading(1024),reading(0).copy(identity=second)));c.publishRuntime(app,listOf(reading(1536),reading(512).copy(identity=second)))
        c.saveComparison(app,owner,0);c.saveComparison(app,owner,1);assertTrue(c.hasComparison(0));c.sessionClosed(app,second);assertFalse(c.hasComparison(0));assertFalse(c.hasComparison(1));assertEquals(0,c.audition)
        c.saveComparison(app,owner,0);c.sessionEnabled(app,identity,false);assertFalse(c.hasComparison(0))
    }

    @Test fun audioPauseWithAnUnchangedFrameCounterClearsBothSnapshots() {
        active();assertTrue(c.saveComparison(app,owner,0));assertTrue(c.saveComparison(app,owner,1));assertTrue(c.startAudition(app,owner,2))
        c.publishRuntime(app,listOf(reading(512,2)))
        assertFalse(c.hasComparison(0));assertFalse(c.hasComparison(1));assertEquals(0,c.audition);assertFalse(c.match);assertFalse(c.canListen(app))
        c.publishRuntime(app,listOf(reading(1024)));assertTrue(c.canListen(app));assertFalse(c.hasComparison(0))
    }

}
