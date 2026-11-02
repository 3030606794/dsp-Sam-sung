package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.fusion.ConvolutionAdvancedParameters.IrOptions
import kotlin.math.*

/** Every operation owns a work buffer; no source file or caller array is modified. */
object ConvolutionAdvancedIr {
    data class Result(val samples: FloatArray,val channels: Int,val frames: Int,val commonDelay: Int)
    fun edit(source: FloatArray,channels: Int,rate: Int,o: IrOptions): Result {
        require(channels in listOf(1,2,4) && source.isNotEmpty() && source.size%channels==0 && rate in 8000..768000)
        if(!o.matrix && o.route==0 && !o.fractional && !o.shape)return Result(source,channels,source.size/channels,0)
        val frames=source.size/channels
        if(o.shape)require(o.splitUs*rate.toDouble()/1e6 < frames){"前后段分界超出工作 IR 长度"}
        val common=if(o.fractional)if(o.method==0)1 else 16 else 0
        val logical=if(channels==1)listOf(0,3) else if(channels==2)listOf(0,3) else (0..3).toList()
        val delays=logical.map {if(o.fractional)o.delays[it]*rate.toDouble()/1e6 else 0.0}
        val extra=if(o.fractional)ceil(delays.max()+if(o.method==0)3 else 32).toInt() else 0
        val outFrames=frames+extra
        val outputChannels=if(o.route==1)4 else if(channels==1 && (o.matrix || o.fractional))2 else channels
        require(outFrames.toLong()*outputChannels<=8_388_608){"精调后的工作 IR 超过驱动容量"}
        val heap=Runtime.getRuntime();val available=heap.maxMemory()-heap.totalMemory()+heap.freeMemory()
        require(outFrames.toLong()*(logical.size+outputChannels)*4<available*.7){"IR 精调工作副本所需内存过大，请缩短时间窗"}
        val paths=Array(4){FloatArray(0)}
        logical.forEachIndexed {channel,path->
            val inputChannel=if(channels==1)0 else channel
            val result=FloatArray(outFrames)
            val gain=if(o.matrix)if(o.muted[path])0.0 else 10.0.pow(o.gains[path]/200.0)*(if(o.inverted[path])-1 else 1) else 1.0
            val delay=delays[channel];val integer=floor(delay).toInt();val fraction=delay-integer
            val taps=when { !o.fractional->doubleArrayOf(1.0);o.method==0->DoubleArray(4){k->
                val d=1+fraction;var w=1.0;for(j in 0..3)if(j!=k)w*=(d-j)/(k-j);w
            };else->{
                val taps=DoubleArray(33){k->val x=k-16-fraction
                    (if(abs(x)<1e-12)1.0 else sin(PI*x)/(PI*x))*(.42-.5*cos(2*PI*k/32)+.08*cos(4*PI*k/32))}
                val sum=taps.sum();taps.map {it/sum}.toDoubleArray()
            }}
            for(n in 0 until frames) {
                if(n%4096==0 && Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException()
                var envelope=1.0
                if(o.shape) {
                    val us=n*1e6/rate;val t=((us-o.splitUs)/o.blendUs+.5).coerceIn(0.0,1.0)
                    val blend=.5-.5*cos(PI*t)
                    val db=o.early/10.0*(1-blend)+o.late/10.0*blend-o.decay/10.0*max(0.0,(us-o.splitUs)/1e6)
                    envelope=10.0.pow(db/20)
                }
                val sample=source[n*channels+inputChannel]*gain*envelope
                for(k in taps.indices)result[n+integer+k]+=(sample*taps[k]).toFloat()
            }
            paths[path]=result
        }
        val output=FloatArray(outFrames*outputChannels)
        for(n in 0 until outFrames) {
            fun path(i: Int)=paths[i].getOrElse(n){0f}
            if(o.route==1) {
                val l=(path(0)+path(2))*.5f;val r=(path(1)+path(3))*.5f
                output[n*4]=l;output[n*4+1]=r;output[n*4+2]=l;output[n*4+3]=r
            } else if(outputChannels==4)for(c in 0..3)output[n*4+c]=path(c)
            else if(outputChannels==2){output[n*2]=path(0);output[n*2+1]=path(3)}
            else output[n]=path(0)
        }
        require(output.all {it.isFinite()}){"IR 精调产生无效样本"}
        return Result(output,outputChannels,outFrames,common)
    }
}
