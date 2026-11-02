package me.timschneeberger.rootlessjamesdsp.interop

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Pure, non-destructive IR editing. An identity configuration returns the original decoded samples. */
object ConvolutionIrPreprocessor {
    val keys=setOf("conv1_window_enabled","conv1_start_us","conv1_length_us","conv1_fade_in_us","conv1_fade_out_us",
        "conv1_norm_mode","conv1_norm_target_tenths","conv1_norm_attenuate_only")
    data class Options(val window: Boolean=false,val startUs: Int=0,val lengthUs: Int=0,val fadeInUs: Int=0,val fadeOutUs: Int=0,
        val normalize: Boolean=false,val targetTenths: Int=-10,val attenuateOnly: Boolean=true) {
        companion object {
            fun read(values: Map<String,*>): Options {
                fun number(key: String,default: Int,min: Int=0,max: Int=Int.MAX_VALUE): Int =
                    (values[key] as? Number)?.toDouble()?.takeIf {it.isFinite() && it%1.0==0.0 && it in min.toDouble()..max.toDouble()}?.toInt() ?: default
                return Options(values["conv1_window_enabled"]==true,number("conv1_start_us",0),number("conv1_length_us",0),
                    number("conv1_fade_in_us",0,max=50000),number("conv1_fade_out_us",0,max=500000),
                    number("conv1_norm_mode",0,max=1)==1,number("conv1_norm_target_tenths",-10,-120,-10),values["conv1_norm_attenuate_only"]!=false)
            }
        }
    }
    data class Edited(val samples: FloatArray,val frames: Int,val originalFrames: Int,val startFrame: Int,
        val normalizationGainDb: Float,val crc: Int,val peakFrames: List<Int>)
    data class Metadata(val sampleRate: Int=0,val channels: Int=0,val frames: Long=0)
    data class Analysis(val waveform: Array<FloatArray>,val frequencyHz: FloatArray,val magnitudesDb: Array<FloatArray>)
    private fun interrupted(){if(Thread.currentThread().isInterrupted)throw CancellationException()}
    fun edit(source: FloatArray,channels: Int,rate: Int,options: Options): Edited {
        require(channels in listOf(1,2,4) && source.isNotEmpty() && source.size%channels==0 && rate in 8000..768000){"IR格式或处理采样率无效"}
        require(source.all {it.isFinite()}){"IR包含无效样本"}
        val count=source.size/channels
        fun frames(us: Int)=(us*rate.toDouble()/1e6).roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val start=if(options.window)frames(options.startUs) else 0
        require(start in 0 until count){"裁剪起点超出IR长度"}
        val length=if(options.window && options.lengthUs>0)frames(options.lengthUs) else count-start
        require(length>0 && length<=count-start){"保留长度超出剩余IR范围"}
        val fadeIn=if(options.window)frames(options.fadeInUs) else 0
        val fadeOut=if(options.window)frames(options.fadeOutUs) else 0
        require(fadeIn<=length && fadeOut<=length){"淡入或淡出不能超过保留的IR长度"}
        var gain=1.0
        val identity=start==0 && length==count && fadeIn==0 && fadeOut==0
        var samples=if(identity)source else FloatArray(length*channels)
        if(!identity)for(frame in 0 until length) {
            if(frame%4096==0)interrupted()
            var envelope=1.0
            if(fadeIn>0 && frame<fadeIn)envelope*=.5-.5*cos(PI*frame/fadeIn)
            val remaining=length-1-frame
            if(fadeOut>0 && remaining<fadeOut)envelope*=.5-.5*cos(PI*remaining/fadeOut)
            for(c in 0 until channels)samples[frame*channels+c]=(source[(frame+start)*channels+c]*envelope).toFloat()
        }
        if(options.normalize) {
            val peak=samples.maxOf {abs(it).toDouble()}
            if(peak>0)gain=10.0.pow(options.targetTenths/200.0)/peak
            if(options.attenuateOnly)gain=min(1.0,gain)
            require(gain.isFinite() && gain.toFloat().isFinite()){"归一化补偿超出有效范围"}
            if(gain!=1.0){if(samples===source)samples=source.copyOf();for(i in samples.indices)samples[i]=(samples[i]*gain).toFloat()}
        }
        val positions=MutableList(channels){0};val peaks=FloatArray(channels)
        for(frame in 0 until length)for(c in 0 until channels){val value=abs(samples[frame*channels+c]);if(value>peaks[c]){peaks[c]=value;positions[c]=frame}}
        return Edited(samples,length,count,start,(20*log10(gain)).toFloat(),crc(samples),positions)
    }
    fun crc(samples: FloatArray): Int {
        // The legacy JamesDSP protocol hashes each entire float word with eight
        // signed shifts. Standard byte-stream CRC32 is a different identifier.
        var crc=-1
        for(i in samples.indices){if(i%4096==0)interrupted();crc=crc xor samples[i].toRawBits()
            repeat(8){val mask=-(crc and 1);crc=(crc shr 1) xor (0xEDB88320.toInt() and mask)}}
        return crc.inv()
    }
    /** Full-kernel DTFT at a bounded FFT grid: folding coefficients is exact at those grid frequencies. */
    fun analyze(samples: FloatArray,channels: Int,rate: Int): Analysis {
        require(channels in listOf(1,2,4) && samples.size%channels==0 && samples.isNotEmpty())
        val frames=samples.size/channels;val n=65536;val envelopePoints=min(256,frames)
        val waveform=Array(channels){FloatArray(envelopePoints)}
        val maximum=min(20000.0,rate/2.0-rate.toDouble()/n)
        val bins=(0 until 128).map {(20*(maximum/20).pow(it/127.0)*n/rate).roundToInt().coerceIn(1,n/2-1)}.distinct().sorted()
        val frequencies=FloatArray(bins.size){(bins[it]*rate.toDouble()/n).toFloat()}
        val responses=Array(channels){FloatArray(bins.size)}
        val real=DoubleArray(n);val imaginary=DoubleArray(n)
        for(c in 0 until channels) {
            real.fill(0.0);imaginary.fill(0.0)
            for(frame in 0 until frames) {
                if(frame%4096==0)interrupted()
                val sample=samples[frame*channels+c];real[frame and (n-1)]+=sample
                val bucket=(frame.toLong()*envelopePoints/frames).toInt()
                if(abs(sample)>abs(waveform[c][bucket]))waveform[c][bucket]=sample
            }
            for(i in 1 until n){var bit=n shr 1;var j=0;var value=i;while(bit>0){if(value and 1!=0)j=j or bit;value=value shr 1;bit=bit shr 1};if(i<j){val t=real[i];real[i]=real[j];real[j]=t}}
            var size=2
            while(size<=n) {
                interrupted();val angle=-2*PI/size;val wr=cos(angle);val wi=sin(angle)
                for(start in 0 until n step size){var ar=1.0;var ai=0.0;for(k in 0 until size/2){val p=start+k;val q=p+size/2
                    val r=ar*real[q]-ai*imaginary[q];val im=ar*imaginary[q]+ai*real[q]
                    real[q]=real[p]-r;imaginary[q]=imaginary[p]-im;real[p]+=r;imaginary[p]+=im
                    val next=ar*wr-ai*wi;ai=ar*wi+ai*wr;ar=next}}
                size=size shl 1
            }
            bins.forEachIndexed {i,k->val magnitude=hypot(real[k],imaginary[k]);responses[c][i]=if(magnitude==0.0)Float.NEGATIVE_INFINITY else (20*log10(magnitude)).toFloat()}
        }
        return Analysis(waveform,frequencies,responses)
    }
    /** Header inspection only; decoding/resampling remains the existing native loader. Unknown metadata stays unknown. */
    fun metadata(file: File): Metadata = runCatching { RandomAccessFile(file,"r").use { r ->
        fun le32(): Long = (0..3).fold(0L){v,i->v or (r.readUnsignedByte().toLong() shl (i*8))}
        fun le16()=r.readUnsignedByte() or (r.readUnsignedByte() shl 8)
        fun tag(): String {val b=ByteArray(4);r.readFully(b);return String(b,Charsets.US_ASCII)}
        when(tag()) {
            "RIFF"->{le32();if(tag()!="WAVE")return@use Metadata();var rate=0;var channels=0;var align=0;var data=0L;var fact=0L
                while(r.filePointer+8<=r.length()){val id=tag();val length=le32();val end=r.filePointer+length+(length and 1)
                    if(end>r.length()+1)break
                    when(id){"fmt "->if(length>=16){le16();channels=le16();rate=le32().toInt();le32();align=le16()};"data"->data=length;"fact"->if(length>=4)fact=le32()}
                    r.seek(end)}
                Metadata(rate,channels,if(fact>0)fact else if(align>0)data/align else 0)}
            "fLaC"->{val header=r.readUnsignedByte();val length=(r.readUnsignedByte() shl 16) or (r.readUnsignedByte() shl 8) or r.readUnsignedByte()
                if(header and 127!=0 || length<34)return@use Metadata();r.skipBytes(10);var packed=0L;repeat(8){packed=(packed shl 8) or r.readUnsignedByte().toLong()}
                Metadata((packed ushr 44).toInt(),((packed ushr 41) and 7).toInt()+1,packed and 0xfffffffffL)}
            else->Metadata()
        }
    }}.getOrDefault(Metadata())
}
