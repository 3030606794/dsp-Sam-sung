package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SurroundProfessionalParametersTest {
    private val p=SurroundProfessionalParameters
    private val s=SurroundController.defaults[0]
    @Test fun nativeContractAndScaledStepsAgree(){
        val header=File("../native/surround/SurroundProfessionalContract.h").let {if(it.isFile)it else File("native/surround/SurroundProfessionalContract.h")}.readText()
        fun values(name:String)=Regex("$name\\{([^}]+)}").find(header)!!.groupValues[1].split(',').map {it.trim().toInt()}
        val packet=p.packet(s,false);assertEquals(27,packet.size);assertEquals(values("kSurroundProfessionalDefaults"),packet.drop(6))
        val low=values("kSurroundProfessionalLow");val high=values("kSurroundProfessionalHigh")
        p.entries.forEach {e->e.index?.let {assertEquals(low[it],e.control.min);assertEquals(high[it],e.control.max)}
            if(e.kind=="number"){assertEquals(e.control.min,e.control.parse(e.control.number(e.control.min)));assertEquals(e.control.max,e.control.parse(e.control.number(e.control.max)));assertNull(e.control.parse("NaN"));assertNull(e.control.parse("1e99"))}}
        assertFalse(p.on(s,"enabled"));assertFalse(p.on(s,"rms_on"));assertFalse(p.on(s,"peak_on"));assertFalse(p.on(s,"meters"))
    }
    @Test fun factoryAndOldValuesRemainExactAndNewFieldsAreIndependent(){
        for(slot in 0..7){val old=SurroundController.defaults[slot].copy(attenuationMilliDb=8753,delayUs=97)
            val v=p.packet(old,true);assertEquals(8753,v[4]);assertEquals(97,v[5]);assertEquals(8753,v[12]);assertEquals(8753,v[13]);assertEquals(970,v[14]);assertEquals(970,v[15])
            val next=p.with(old,"lr_delay",971);assertEquals(97,next.delayUs);assertEquals(971,p.value(next,"lr_delay"));assertEquals(970,p.value(next,"rl_delay"))}
        assertEquals(350,p.byKey.getValue("lr_delay").control.parse("35.0"));assertNull(p.byKey.getValue("lr_delay").control.parse("35.01"))
        assertEquals(8753,p.byKey.getValue("lr_attenuation").control.parse("8.753"))
    }
    @Test fun geometryUsesPathDifferenceAndRequiresExplicitApplication(){
        val result=p.geometry(s);val expected=(Math.hypot(2000.0,390.0)-Math.hypot(2000.0,210.0))/343000*1e7
        assertEquals(expected,result.delayTenthsUs.toDouble(),.5);assertTrue(result.applicable)
        val next=p.applyGeometry(s)!!;assertEquals(35,next.delayUs);assertFalse(p.on(next,"enabled"));assertEquals(result.delayTenthsUs,p.value(next,"lr_delay"));assertEquals(result.delayTenthsUs,p.value(next,"rl_delay"))
        val tooNarrow=p.with(p.with(s,"speaker_spacing",50),"listening_distance",5000);assertFalse(p.geometry(tooNarrow).applicable);assertNull(p.applyGeometry(tooNarrow))
    }
    @Test fun completeResponseIncludesAttenuationDelayAndActualRate(){
        val a=SurroundProfessionalResponse.matrix(s,48000.0,1000.0)
        val b=SurroundProfessionalResponse.matrix(s.copy(delayUs=100),48000.0,1000.0)
        assertNotEquals(a,b);assertNotEquals(a,SurroundProfessionalResponse.matrix(s.copy(attenuationMilliDb=9000),48000.0,1000.0));assertEquals(a[2],a[3])
        var dry=p.with(s,"enabled",1);dry=p.with(dry,"mix",0)
        val h=SurroundProfessionalResponse.matrix(dry,48000.0,1000.0);assertEquals(1.0,h[0].magnitude(),1e-12);assertEquals(0.0,h[2].magnitude(),0.0)
        val curve=SurroundProfessionalResponse.calculate(s,8000.0,2);assertEquals(3600.0,curve.frequencies.last(),1e-9);assertEquals(4,curve.labels.size);assertTrue(curve.decibels.all {it.all(Double::isFinite)})
        val professional=p.with(s,"enabled",1);val steeper=p.with(professional,"hp_slope",2)
        val band1=SurroundResponse.calculate(professional);val band2=SurroundResponse.calculate(steeper)
        val at=band1.frequencies.indices.minBy {kotlin.math.abs(band1.frequencies[it]-100.0)}
        assertTrue(band2.decibels[at]<band1.decibels[at]-8)
    }
}
