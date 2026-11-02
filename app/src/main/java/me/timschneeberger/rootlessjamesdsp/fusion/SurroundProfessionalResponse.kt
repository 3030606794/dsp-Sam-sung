package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** The complete linear 2x2 transfer matrix, including fractional delay and recursion. */
object SurroundProfessionalResponse {
    data class Complex(val real:Double,val imaginary:Double=0.0) {
        operator fun plus(b:Complex)=Complex(real+b.real,imaginary+b.imaginary)
        operator fun minus(b:Complex)=Complex(real-b.real,imaginary-b.imaginary)
        operator fun times(b:Complex)=Complex(real*b.real-imaginary*b.imaginary,real*b.imaginary+imaginary*b.real)
        operator fun times(b:Double)=Complex(real*b,imaginary*b)
        operator fun div(b:Complex):Complex {val d=b.real*b.real+b.imaginary*b.imaginary;return Complex((real*b.real+imaginary*b.imaginary)/d,(imaginary*b.real-real*b.imaginary)/d)}
        fun power(n:Int):Complex {var v=Complex(1.0);repeat(n){v=v*this};return v}
        fun magnitude()=hypot(real,imaginary)
    }
    data class Curve(val frequencies:DoubleArray,val labels:List<String>,val decibels:List<DoubleArray>)
    private fun butter(z:Complex,hz:Double,fs:Double,slope:Int,hp:Boolean):Complex {
        val s=Complex(1.0)-z;val t=(Complex(1.0)+z)*tan(PI*hz/fs);val order=1 shl slope
        val den=when(order){1->s+t;2->s.power(2)+(s*t)*sqrt(2.0)+t.power(2);else->s.power(4)+(s.power(3)*t)*2.613125929752753+(s.power(2)*t.power(2))*3.414213562373095+(s*t.power(3))*2.613125929752753+t.power(4)}
        return (if(hp)s else t).power(order)/den
    }
    /** LL, RR, LR (left input -> right output), RL. RMS/limiting are nonlinear and excluded. */
    fun matrix(s:SurroundController.Settings,fs:Double,hz:Double):List<Complex> {
        require(fs.isFinite() && fs in 8000.0..768000.0 && hz>0 && hz<fs*.5)
        val p=SurroundProfessionalParameters;val professional=p.on(s,"enabled");val linked=p.on(s,"link")
        val z=Complex(cos(2*PI*hz/fs),-sin(2*PI*hz/fs));val scale=min(1.0,fs*.45/s.highHz)
        val b=butter(z,s.lowHz*scale,fs,if(professional)p.value(s,"hp_slope")else 1,true)*butter(z,s.highHz*scale,fs,if(professional)p.value(s,"lp_slope")else 1,false)
        fun branch(right:Boolean):Complex {
            val other=right && !linked;val attenuation=if(professional)p.value(s,if(other)"rl_attenuation" else "lr_attenuation")else s.attenuationMilliDb
            val us=if(professional)p.value(s,if(other)"rl_delay" else "lr_delay")/10.0 else s.delayUs.toDouble()
            val delay=fs*us/1e6;val whole=floor(delay).toInt();val fraction=delay-whole
            return b*(Complex(1.0-fraction)+z*fraction)*z.power(whole)*10.0.pow(-attenuation/20000.0)
        }
        val a=branch(false);val b2=branch(true);val denominator=Complex(1.0)-a*b2
        val direct=Complex(1.0)/denominator;val wet=if(professional)p.value(s,"mix")/1000.0 else 1.0
        val gain=if(professional)10.0.pow((p.value(s,"pre_db")+p.value(s,"output_db"))/200.0)else 1.0
        val d=(Complex(1.0-wet)+direct*wet)*gain
        return listOf(d,d,((a* -1.0)/denominator)*(wet*gain),((b2* -1.0)/denominator)*(wet*gain))
    }
    fun calculate(s:SurroundController.Settings,fs:Double,view:Int):Curve {
        val max=min(20000.0,fs*.45);val frequencies=DoubleArray(256){20.0*(max/20).pow(it/255.0)}
        val values=List(4){DoubleArray(256)}
        frequencies.forEachIndexed {i,hz->val h=matrix(s,fs,hz);val curves=if(view==2)listOf(h[0]+h[3],h[1]+h[2],h[0]-h[3],h[2]-h[1])else h
            curves.forEachIndexed {j,c->values[j][i]=20*log10(c.magnitude().coerceAtLeast(1e-12))}}
        return Curve(frequencies,if(view==2)listOf("同相 L","同相 R","反相 L","反相 R")else listOf("直达 L","直达 R","左→右","右→左"),values)
    }
    fun band(s:SurroundController.Settings,fs:Double):Curve {
        val high=min(20000.0,fs*.45);val frequencies=DoubleArray(256){20.0*(high/20).pow(it/255.0)};val scale=min(1.0,fs*.45/s.highHz);val p=SurroundProfessionalParameters
        val values=DoubleArray(256){i->val w=2*PI*frequencies[i]/fs;val z=Complex(cos(w),-sin(w));val b=butter(z,s.lowHz*scale,fs,p.value(s,"hp_slope"),true)*butter(z,s.highHz*scale,fs,p.value(s,"lp_slope"),false);20*log10(b.magnitude().coerceAtLeast(1e-12))}
        return Curve(frequencies,listOf("抵消处理频段"),listOf(values))
    }
}
