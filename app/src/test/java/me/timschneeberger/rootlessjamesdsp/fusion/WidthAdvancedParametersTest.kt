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
class WidthAdvancedParametersTest {
    private val app: Application get()=RuntimeEnvironment.getApplication()
    private val p=WidthAdvancedParameters
    private val prefs get()=WidthController.preferences(app)
    @Before fun prepare(){prefs.edit().clear().commit();WidthController.clearComparisons(app);WidthController.publishRuntime(app,emptyList());WidthController.publishCapabilities(app,65535)}
    @After fun cleanup(){WidthController.clearComparisons(app);WidthController.publishRuntime(app,emptyList())}
    @Test fun missingValuesRemainAbsentAndAllNewProcessingDefaultsOff() {
        prefs.edit().putFloat(WidthParameters.LEGACY,58.5f).commit();val before=prefs.all.toMap();p.normalize(prefs);assertEquals(before,prefs.all)
        val packet=p.packet(prefs.all);assertEquals(60,packet.size);assertEquals(1,packet[0]);p.entries.forEachIndexed {i,e->assertEquals(e.control.default,packet[i+1]);if(e.kind=="switch")assertEquals(0,packet[i+1])}
        p.all.forEach {c->assertEquals(c.min,c.parse(c.number(c.min)));assertEquals(c.max,c.parse(c.number(c.max)));assertNull(c.parse("NaN"));assertNull(c.parse(c.number(c.max+1)))}
    }
    @Test fun capabilityGatesAreIndependentAndSyncNeverTurnsPowerOn() {
        prefs.edit().putBoolean("width2_dyn0_on",true).putInt("width2_dyn0_threshold",-99).commit();val packets=mutableListOf<Int>()
        assertTrue(WidthController.sync(app,32767,this){id,_->packets+=id;true});assertEquals(listOf(1410,1411),packets);packets.clear()
        assertTrue(WidthController.sync(app,65535,this){id,_->packets+=id;true});assertEquals(listOf(1410,1412,1411,1413),packets);assertFalse(prefs.getBoolean(WidthParameters.ENABLED,false));assertEquals(-99,prefs.getInt("width2_dyn0_threshold",0))
    }
    @Test fun rejectsMalformedSnapshotsAndCopiesValidPayload() {
        val raw=bulk().data.toIntArray();assertNotNull(WidthAdvancedRuntime.decode(raw));val s=WidthAdvancedRuntime.decode(raw)!!;raw[7]=-9000;assertEquals(3000,s.data[7])
        for(i in listOf(0,1,2,3,4,5,6,7,19,43,44,45,46,48)){val bad=bulk().data.toIntArray();bad[i]=when(i){0->2;1->3;2->1;3,5,45->2;4->3;6->-1;7->10001;19->1001;43->61;44->10;46->20001;else->1000001};assertNull("field $i",WidthAdvancedRuntime.decode(bad))}
        assertNull(WidthAdvancedRuntime.decode(raw.copyOf(175)))
    }
    companion object {
        fun bulk()=WidthAdvancedRuntime.decode(IntArray(176).apply {
            this[0]=1;this[1]=2;this[2]=44100;this[3]=1;this[4]=2;this[5]=1;this[6]=4410
            for(i in 7..12)this[i]=3000;for(i in 13..18)this[i]=-90;this[45]=1;this[46]=400;this[47]=6000
            for(i in 0 until 64){this[48+2*i]=(sin(i*.7)*120000).toInt();this[49+2*i]=(sin(i*.8)*100000).toInt()}
        })!!
        private fun sin(x: Double)=kotlin.math.sin(x)
    }
}
