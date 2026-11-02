package me.timschneeberger.rootlessjamesdsp.interop

import kotlin.math.*

/** Full-IR DTFT and its analytical derivative at a bounded grid. No phase-difference unwrapping is assumed. */
object ConvolutionAdvancedAnalysis {
    data class Data(val hz: FloatArray,val originalDb: List<FloatArray>,val workingDb: List<FloatArray>,
        val phaseDegrees: List<FloatArray>,val groupDelayMs: List<FloatArray>,val timeMs: FloatArray,
        val energyDb: List<FloatArray>)
    private fun fft(r: DoubleArray,i: DoubleArray) {
        val n=r.size;var j=0
        for(k in 1 until n) {var bit=n shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit
            if(k<j){var t=r[k];r[k]=r[j];r[j]=t;t=i[k];i[k]=i[j];i[j]=t}}
        var size=2
        while(size<=n) {
            if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
            val w=-2*PI/size;val wr=cos(w);val wi=sin(w)
            for(start in 0 until n step size) {var ar=1.0;var ai=0.0
                for(k in 0 until size/2) {val p=start+k;val q=p+size/2
                    val vr=r[q]*ar-i[q]*ai;val vi=r[q]*ai+i[q]*ar
                    r[q]=r[p]-vr;i[q]=i[p]-vi;r[p]+=vr;i[p]+=vi
                    val next=ar*wr-ai*wi;ai=ar*wi+ai*wr;ar=next}}
            size=size shl 1
        }
    }
    fun analyze(original: FloatArray,originalChannels: Int,working: FloatArray,channels: Int,rate: Int): Data {
        require(originalChannels in listOf(1,2,4) && channels in listOf(1,2,4) && original.isNotEmpty() && working.isNotEmpty())
        val n=65536;val maximum=min(20000.0,rate*.499)
        val bins=(0 until 512).map {(20*(maximum/20).pow(it/511.0)*n/rate).roundToInt().coerceIn(1,n/2-1)}.distinct().sorted()
        val hz=FloatArray(bins.size){(bins[it]*rate.toDouble()/n).toFloat()}
        val real=DoubleArray(n);val imag=DoubleArray(n)
        fun transform(samples: FloatArray,count: Int,c: Int,derivative: Boolean) {
            real.fill(0.0);imag.fill(0.0)
            for(frame in 0 until samples.size/count) {
                if(frame%4096==0 && Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
                real[frame and (n-1)]+=samples[frame*count+c]*(if(derivative)frame.toDouble() else 1.0)
            }
            fft(real,imag)
        }
        val source=(0 until originalChannels).map {c->transform(original,originalChannels,c,false)
            FloatArray(bins.size){j->(20*log10(hypot(real[bins[j]],imag[bins[j]]))).toFloat()}}
        val mags=mutableListOf<FloatArray>();val phases=mutableListOf<FloatArray>();val delays=mutableListOf<FloatArray>()
        for(c in 0 until channels) {
            transform(working,channels,c,false)
            val hr=DoubleArray(bins.size){real[bins[it]]};val hi=DoubleArray(bins.size){imag[bins[it]]}
            val magnitude=DoubleArray(bins.size){hypot(hr[it],hi[it])};val floor=max(1e-15,(magnitude.maxOrNull() ?: 0.0)*1e-6)
            mags+=FloatArray(bins.size){(20*log10(magnitude[it])).toFloat()}
            phases+=FloatArray(bins.size){if(magnitude[it]>floor)(atan2(hi[it],hr[it])*180/PI).toFloat() else Float.NaN}
            transform(working,channels,c,true)
            delays+=FloatArray(bins.size){j->if(magnitude[j]<=floor)Float.NaN else {
                val k=bins[j];((real[k]*hr[j]+imag[k]*hi[j])/(magnitude[j]*magnitude[j])*1000/rate).toFloat()
            }}
        }
        val frames=working.size/channels;val points=min(256,frames)
        val time=FloatArray(points){if(points==1)0f else (it*(frames-1.0)/(points-1)*1000/rate).toFloat()}
        val energy=(0 until channels).map {c->
            var total=0.0;for(frame in 0 until frames)total+=working[frame*channels+c].toDouble().pow(2)
            val result=FloatArray(points){Float.NaN};var remaining=0.0;var j=points-1
            for(frame in frames-1 downTo 0){remaining+=working[frame*channels+c].toDouble().pow(2)
                while(j>=0 && frame<=(if(points==1)0 else (j*(frames-1.0)/(points-1)).roundToInt())) {
                    result[j]=if(total==0.0)Float.NaN else (10*log10(remaining/total)).toFloat();j--
                }}
            result
        }
        return Data(hz,source,mags,phases,delays,time,energy)
    }
    /** Display-only power averaging over the requested octave width. */
    fun smooth(hz: FloatArray,db: FloatArray,mode: Int): FloatArray {
        if(mode==0)return db
        val half=if(mode==1)1.0/24 else 1.0/12
        return FloatArray(db.size){i->var energy=0.0;var count=0
            for(j in db.indices)if(abs(log2(hz[j].toDouble()/hz[i]))<=half && !db[j].isNaN()){energy+=10.0.pow(db[j]/10.0);count++}
            if(count==0)Float.NaN else (10*log10(energy/count)).toFloat()}
    }
}
