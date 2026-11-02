package me.timschneeberger.rootlessjamesdsp.fusion

import kotlin.math.*

/** Predicted module-only linear response. No microphone data or dynamic gain. */
object SurroundDiagnostics {
    data class Result(val x:DoubleArray,val values:List<DoubleArray>,val labels:List<String>,val unit:String,val logarithmic:Boolean,val gainDb:Double)
    private val labels=listOf("直达 L","直达 R","左→右","右→左")
    fun calculate(s:SurroundController.Settings,fs:Double,view:Int,windowMs:Int,cancel:()->Unit={}):Result {
        require(fs in 8000.0..768000.0 && view in 0..2 && windowMs in 1..100)
        val response=SurroundProfessionalResponse
        val maxHz=min(20000.0,fs*.45)
        val frequencies=DoubleArray(512){20*(maxHz/20).pow(it/511.0)}
        var maxGain=0.0
        // Grid estimate of the largest singular value; not a sample/true-peak bound.
        for(i in 0..4096){if(i%256==0)cancel();val hz=(fs*.499999*(i+.001)/4096.001).coerceAtLeast(.0001);val h=response.matrix(s,fs,hz)
            val powers=h.map {it.real*it.real+it.imaginary*it.imaginary};val trace=powers.sum();val det=h[0]*h[1]-h[2]*h[3];val determinant=det.real*det.real+det.imaginary*det.imaginary
            val singular=sqrt(max(0.0,(trace+sqrt(max(0.0,trace*trace-4*determinant)))/2));maxGain=max(maxGain,singular)
        }
        val gainDb=20*log10(maxGain.coerceAtLeast(1e-12))
        if(view==2){val values=impulses(s,fs,windowMs,cancel);return Result(DoubleArray(values[0].size){it*1000/fs},values,labels,"幅度",false,gainDb)}
        val values=List(4){DoubleArray(512){Double.NaN}};val last=DoubleArray(4){Double.NaN};val turns=DoubleArray(4)
        frequencies.forEachIndexed {i,hz->if(i%128==0)cancel();val h=response.matrix(s,fs,hz)
            h.forEachIndexed {j,c->if(c.magnitude()>1e-6){
                if(view==0){val angle=atan2(c.imaginary,c.real);if(last[j].isFinite()){val delta=angle-last[j];if(delta>PI)turns[j]-=2*PI else if(delta< -PI)turns[j]+=2*PI};last[j]=angle;values[j][i]=(angle+turns[j])*180/PI}
                else {val d=max(.001,hz*1e-5);val a=response.matrix(s,fs,hz-d)[j];val b=response.matrix(s,fs,hz+d)[j];val derivative=(b-a)*(1/(2*d));val quotient=derivative/c;values[j][i]=-quotient.imaginary*1000/(2*PI)}
            }else last[j]=Double.NaN}
        }
        return Result(frequencies,values,labels,if(view==0)"°（展开相位）" else "ms",true,gainDb)
    }
    /** Time recurrence avoids circular FFT wrap-around at low cutoff frequencies. */
    fun impulses(s:SurroundController.Settings,fs:Double,windowMs:Int,cancel:()->Unit={}):List<DoubleArray> {
        val p=SurroundProfessionalParameters;val pro=p.on(s,"enabled");val scale=min(1.0,fs*.45/s.highHz)
        data class Coeff(val b0:Double,val b1:Double,val b2:Double,val a1:Double,val a2:Double)
        fun section(hz:Double,hp:Boolean,q:Double,first:Boolean):Coeff {
            val w=2*PI*hz/fs
            if(first){val k=tan(w/2);val b=if(hp)1/(1+k) else k/(1+k);return Coeff(b,if(hp)-b else b,0.0,(k-1)/(1+k),0.0)}
            val c=cos(w);val a=sin(w)/(2*q);val inv=1/(1+a);val b=(if(hp)1+c else 1-c)*.5*inv
            return Coeff(b,if(hp)-2*b else 2*b,b,-2*c*inv,(1-a)*inv)
        }
        val sections=buildList {
            for(edge in 0..1){val slope=if(pro)p.value(s,if(edge==0)"hp_slope" else "lp_slope")else 1;val hz=(if(edge==0)s.lowHz else s.highHz)*scale
                if(slope==2){add(section(hz,edge==0,.541196100146197,false));add(section(hz,edge==0,1.306562964876377,false))}
                else add(section(hz,edge==0,1/sqrt(2.0),slope==0))}
        }
        val b0=sections.fold(1.0){v,c->v*c.b0};val gains=DoubleArray(2);val fractions=DoubleArray(2);val delays=IntArray(2)
        for(ch in 0..1){val route=if(ch==1 && !p.on(s,"link"))"rl" else "lr";gains[ch]=10.0.pow(-(if(pro)p.value(s,"${route}_attenuation")else s.attenuationMilliDb)/20000.0)
            val delay=fs*(if(pro)p.value(s,"${route}_delay")/10.0 else s.delayUs.toDouble())/1e6;delays[ch]=floor(delay).toInt();fractions[ch]=delay-delays[ch]}
        val direct=if(pro)10.0.pow(p.value(s,"pre_db")/200.0)else 1.0;val trim=if(pro)10.0.pow(p.value(s,"output_db")/200.0)else 1.0;val mix=if(pro)p.value(s,"mix")/1000.0 else 1.0
        val size=ceil(windowMs*fs/1000).toInt()+1;val result=List(4){DoubleArray(size)}
        repeat(2){source->val states=Array(2){Array(sections.size){DoubleArray(2)}};val ring=Array(2){DoubleArray(256)};var position=0
            fun offset(ch:Int):Double {var v=0.0;sections.forEachIndexed {i,c->v=c.b0*v+states[ch][i][0]};return v}
            fun previous(ch:Int,delay:Int)=ring[ch][(position+256-delay)%256]
            fun delayed(ch:Int)=(1-fractions[ch])*(if(delays[ch]==0)offset(ch)else previous(ch,delays[ch]))+fractions[ch]*previous(ch,delays[ch]+1)
            fun filter(ch:Int,input:Double):Double {var v=input;sections.forEachIndexed {i,c->val st=states[ch][i];val out=c.b0*v+st[0];st[0]=c.b1*v-c.a1*out+st[1];st[1]=c.b2*v-c.a2*out;v=out};return v}
            for(n in 0 until size){if(n%256==0)cancel();val dl=if(n==0 && source==0)direct else 0.0;val dr=if(n==0 && source==1)direct else 0.0
                val cl=if(delays[1]==0)gains[1]*(1-fractions[1])*b0 else 0.0;val cr=if(delays[0]==0)gains[0]*(1-fractions[0])*b0 else 0.0
                val l=dl-gains[1]*delayed(1);val r=dr-gains[0]*delayed(0);val den=1-cl*cr;val yl=(l-cl*r)/den;val yr=(r-cr*l)/den
                ring[0][position]=filter(0,yl);ring[1][position]=filter(1,yr);position=(position+1)%256
                result[if(source==0)0 else 3][n]=(dl+mix*(yl-dl))*trim;result[if(source==0)2 else 1][n]=(dr+mix*(yr-dr))*trim
            }
        }
        return result
    }
}
