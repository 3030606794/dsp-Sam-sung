package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WidthParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=WidthParameters
    private val prefs get()=WidthController.preferences(app)
    @Before fun prepare(){prefs.edit().clear().commit();WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,32767)}
    @After fun cleanup(){WidthController.stopAudition(app);WidthController.publishRuntime(app,emptyList())}
    @Test fun neutralPacketAndNormalizationPreserveOldFloatAndPowerWithoutCreatingExtensions() {
        prefs.edit().putFloat(p.LEGACY,58.5f).putBoolean(p.ENABLED,true).commit();val before=prefs.all.toMap();p.normalize(prefs);assertEquals(before,prefs.all)
        assertArrayEquals(intArrayOf(1,0,1000,1000,1000,1000,180,3000,1,0,120,1000,1,0,1000,0,0,400,30,0,0,5,20,400,1000,300),p.packet(prefs.all))
        prefs.edit().putString(p.master.key,"broken").putFloat(p.autoWindow.key,401f).putInt(p.MODE,9).putInt(p.MONO,1).commit();p.normalize(prefs)
        assertEquals(1000,prefs.getInt(p.master.key,0));assertEquals(400,prefs.getInt(p.autoWindow.key,0));assertEquals(0,prefs.getInt(p.MODE,-1));assertFalse(prefs.getBoolean(p.MONO,true));assertEquals(58.5f,prefs.getFloat(p.LEGACY,0f))
    }
    @Test fun everyNumericUsesDeclaredDecimalPrecisionAndStrictBounds() {
        assertEquals(19,p.all.size)
        p.all.forEach {c->assertEquals(c.min,c.parse(c.number(c.min)));assertEquals(c.max,c.parse(c.number(c.max)));assertNull(c.parse("NaN"));assertNull(c.parse(c.number(c.max+1)));assertEquals(c.default,p.value(mapOf(c.key to Double.NaN),c))}
        assertNull(p.master.parse("100.01"));assertEquals(1001,p.master.parse("100.1"));assertNull(p.threshold.parse("0.001"));assertEquals(-1,p.threshold.parse("-0.01"))
    }
    @Test fun oldDriverSendsNoExtensionsAndNewDriverNeverEnablesPowerDuringParameterSync() {
        val sent=mutableListOf<Pair<Int,IntArray>>();prefs.edit().putInt(p.MODE,2).putInt(p.master.key,1250).commit()
        assertTrue(WidthController.sync(app,16383,this){id,v->sent+=id to v;true});assertTrue(sent.isEmpty());assertEquals(1250,prefs.getInt(p.master.key,0))
        assertTrue(WidthController.sync(app,32767,this){id,v->sent+=id to v;true});assertEquals(listOf(1410,1411),sent.map {it.first});assertEquals(1250,sent.first().second[2]);assertFalse(prefs.getBoolean(p.ENABLED,false));assertTrue(sent.none {it.first==1204})
    }
    @Test fun runtimeRejectsInvalidOrTornSnapshotsAndOwnsItsCopiedPayload() {
        val v=bulk();val s=WidthRuntime.decode(v)!!;v[5]=-1234;assertEquals(5000,s.data[5])
        for(i in listOf(0,1,2,3,4,5,15,19,20,24,25,26,28,30)){val bad=bulk();bad[i]=when(i){0->2;1->3;2->1;3->2;4->3;5->10001;15->4001;19->1001;20->61;24->10;25->2;26->-1;28->1001;else->2};assertNull("Reject field $i",WidthRuntime.decode(bad))}
        assertNull(WidthRuntime.decode(bulk().copyOf(31)));assertNull(WidthRuntime.decode(bulk().apply {this[22]=21000}))
    }
    @Test fun auditionIsOwnedEphemeralAndStaleSessionPollCannotUndoDisable() {
        val id=Any();prefs.edit().putBoolean(p.ENABLED,true).commit();WidthController.publishRuntime(app,listOf(WidthController.Reading(id,true,0,WidthRuntime.decode(bulk()))))
        assertTrue(WidthController.startAudition(app,this,2));val other=Any();WidthController.stopAudition(app,other);assertEquals(2,WidthController.audition)
        val sent=mutableListOf<Int>();assertTrue(WidthController.sync(app,32767,id){key,v->if(key==1411)sent+=v[0];true});assertEquals(listOf(0,2),sent)
        assertFalse(prefs.all.keys.any {it.contains("audition")});val stale=WidthController.pollState();WidthController.sessionEnabled(app,id,false)
        WidthController.publishRuntime(app,listOf(WidthController.Reading(id,true,1,WidthRuntime.decode(bulk()))),stale);assertEquals(0,WidthController.audition);assertFalse(WidthController.runtime.any {it.enabled})
        WidthController.sessionClosed(app,id);assertTrue(WidthController.runtime.isEmpty())
    }
    companion object {
        fun bulk()=IntArray(32).apply {
            this[0]=1;this[1]=2;this[2]=44100;this[3]=1;this[4]=2;this[5]=5000;this[6]=2000
            for(i in 7..14)this[i]=-180;this[11]=-20
            this[15]=1250;this[16]=1000;this[17]=1250;this[18]=1500;this[19]=200;this[20]=-10
            this[21]=180;this[22]=3000;this[23]=120;this[25]=1;this[26]=4410;this[28]=1000;this[31]=-180
        }
    }
}
