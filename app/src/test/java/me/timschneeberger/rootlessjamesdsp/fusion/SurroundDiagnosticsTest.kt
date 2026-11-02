package me.timschneeberger.rootlessjamesdsp.fusion

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SurroundDiagnosticsTest {
    private val p=SurroundProfessionalParameters
    @Test fun unityHasZeroPhaseAndDelayAndOneSampleImpulse(){
        val s=p.with(p.with(SurroundController.defaults[0],"enabled",1),"mix",0)
        for(view in 0..2){val r=SurroundDiagnostics.calculate(s,48000.0,view,20);assertEquals(0.0,r.gainDb,1e-12)
            if(view<2){assertTrue(r.values[0].all {abs(it)<1e-12});assertTrue(r.values[2].all {it.isNaN()})}
            else {assertEquals(1.0,r.values[0][0],0.0);assertTrue(r.values[0].drop(1).all {it==0.0});assertTrue(r.values[2].all {it==0.0});assertEquals(20.0,r.x.last(),0.0)}}
    }
    @Test fun timeRecurrenceAgreesWithIndependentComplexMatrixAcrossRatesAndSlopes(){
        var cases=0
        for(fs in listOf(8000.0,44100.0,48000.0,96000.0,192000.0))for(hp in 0..2)for(lp in 0..2){
            var s=p.with(p.with(SurroundController.defaults[0],"enabled",1),"link",0)
            mapOf("hp_slope" to hp,"lp_slope" to lp,"lr_delay" to 351,"rl_delay" to 1179,"lr_attenuation" to 2200,"rl_attenuation" to 7100,"mix" to 730,"pre_db" to -30,"output_db" to 20).forEach {(k,v)->s=p.with(s,k,v)}
            val impulse=SurroundDiagnostics.impulses(s,fs,100);val h=SurroundProfessionalResponse.matrix(s,fs,1000.0)
            for(ch in 0..3){var real=0.0;var imaginary=0.0;impulse[ch].forEachIndexed {n,v->real+=v*cos(2*PI*1000*n/fs);imaginary-=v*sin(2*PI*1000*n/fs)};assertEquals("real fs=$fs hp=$hp lp=$lp ch=$ch",h[ch].real,real,1e-5);assertEquals(h[ch].imaginary,imaginary,1e-5);cases++}
        }
        assertEquals(180,cases)
    }
    @Test fun diagnosticsUseHostBandwidthAndCancellationIsCooperative(){
        val s=SurroundController.defaults[0];for(view in 0..2){val r=SurroundDiagnostics.calculate(s,8000.0,view,1);assertEquals(4,r.values.size);assertTrue(r.values.all {it.all(Double::isFinite)});if(view<2)assertEquals(3600.0,r.x.last(),1e-8)}
        assertThrows(IllegalStateException::class.java){SurroundDiagnostics.calculate(s,192000.0,2,100){error("cancelled")}}
    }
}
