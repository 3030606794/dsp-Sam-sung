package me.timschneeberger.rootlessjamesdsp.fusion

import me.timschneeberger.rootlessjamesdsp.interop.ConvolutionIrPreprocessor as Ir
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class ConvolutionIrPreprocessorTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun neutralIsBitIdenticalAndCrcMatchesHistoricalDriverVectors() {
        val data=FloatArray(4096){sin(it*.37).toFloat()}
        for(ch in listOf(1,2,4)) {
            val result=Ir.edit(data,ch,44100,Ir.Options())
            assertSame(data,result.samples);assertEquals(data.size/ch,result.frames)
            assertEquals(Ir.crc(data),result.crc)
        }
        assertEquals(117632909,Ir.crc(floatArrayOf(0f)))
        assertEquals(121466765,Ir.crc(floatArrayOf(1f)))
        assertEquals(426054378,Ir.crc(floatArrayOf(.5f,-.25f,1f,0f)))
        assertEquals(-497513696,Ir.crc(floatArrayOf(1f,-1f,.125f,-.125f)))
    }
    @Test fun windowAndFadesUseOneSharedEnvelopeWithoutChangingSource() {
        val data=FloatArray(200*4){1f};val before=data.copyOf()
        val result=Ir.edit(data,4,8000,Ir.Options(window=true,startUs=1000,lengthUs=10000,fadeInUs=1000,fadeOutUs=2000))
        assertArrayEquals(before,data,0f);assertEquals(8,result.startFrame);assertEquals(80,result.frames)
        for(i in 0 until 80)for(c in 0..3){assertEquals(result.samples[4*i],result.samples[4*i+c],0f);assertTrue(result.samples[4*i+c] in 0f..1f)}
        assertEquals(0f,result.samples.first(),0f);assertEquals(0f,result.samples.last(),0f);assertEquals(1f,result.samples[40*4],0f)
    }
    @Test fun normalizationUsesFinalWindowPeakAndKeepsAllMatrixRatios() {
        val data=FloatArray(80*4){i->floatArrayOf(1f,.5f,-.25f,.125f)[i%4]};data[0]=100f
        val result=Ir.edit(data,4,8000,Ir.Options(window=true,fadeInUs=1000,normalize=true,targetTenths=-60,attenuateOnly=false))
        val expected=10.0.pow(-6.0/20).toFloat()
        assertEquals(expected,result.samples.maxOf {abs(it)},1e-6f)
        for(i in 10 until 80){assertEquals(result.samples[i*4]*.5f,result.samples[i*4+1],1e-7f);assertEquals(-result.samples[i*4]*.25f,result.samples[i*4+2],1e-7f)}
        assertEquals(100f,data[0],0f)
        val small=floatArrayOf(.01f,-.005f)
        assertSame(small,Ir.edit(small,1,48000,Ir.Options(normalize=true,attenuateOnly=true)).samples)
        assertEquals(0f,Ir.edit(FloatArray(10),1,48000,Ir.Options(normalize=true,attenuateOnly=false)).normalizationGainDb,0f)
    }
    @Test fun invalidWindowsAndNonfiniteSamplesAreRejected() {
        val values=FloatArray(80){1f}
        for(o in listOf(Ir.Options(window=true,startUs=10000),Ir.Options(window=true,lengthUs=10100),Ir.Options(window=true,fadeOutUs=20000))) {
            assertThrows(IllegalArgumentException::class.java){Ir.edit(values,1,8000,o)}
        }
        assertThrows(IllegalArgumentException::class.java){Ir.edit(floatArrayOf(Float.NaN),1,48000,Ir.Options())}
        assertThrows(IllegalArgumentException::class.java){Ir.edit(values,3,48000,Ir.Options())}
    }
    @Test fun disabledWindowKeepsAllSavedEditsDormantAndPreservesExactSamples() {
        val source=floatArrayOf(.5f,-.25f)
        assertSame(source,Ir.edit(source,1,48000,Ir.Options(window=false,startUs=999999,lengthUs=999999,fadeInUs=50000,fadeOutUs=500000)).samples)
    }
    @Test fun highRateLongTimesCannotOverflowTheSampleConversion() {
        val result=Ir.edit(FloatArray(192000){1f},1,192000,
            Ir.Options(window=true,startUs=500000,lengthUs=500000,fadeInUs=50000,fadeOutUs=500000))
        assertEquals(96000,result.startFrame);assertEquals(96000,result.frames)
        assertEquals(0f,result.samples.first(),0f);assertEquals(0f,result.samples.last(),0f)
        assertTrue(result.samples.all {it.isFinite() && it in 0f..1f})
    }
    @Test fun longKernelPreviewUsesEveryCoefficientAndMatchesIndependentFrequencyFormula() {
        val length=140000;val data=FloatArray(length*2)
        for(c in 0..1){data[c]=1f;data[70000*2+c]=.5f;data[139999*2+c]=-.25f}
        val result=Ir.analyze(data,2,48000)
        assertEquals(2,result.waveform.size);assertEquals(256,result.waveform[0].size)
        result.frequencyHz.forEachIndexed {i,f->
            val w=2*PI*f.toDouble()/48000
            val re=1+.5*cos(w*70000)-.25*cos(w*139999);val im=-.5*sin(w*70000)+.25*sin(w*139999)
            val expected=20*log10(hypot(re,im))
            assertEquals(expected,result.magnitudesDb[0][i].toDouble(),.003)
            assertEquals(result.magnitudesDb[0][i],result.magnitudesDb[1][i],0f)
        }
    }
    @Test fun wavMetadataIsReadWithoutRewritingFileAndUnknownStaysUnknown() {
        val file=folder.newFile("source.irs")
        val bytes=ByteBuffer.allocate(44+32).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(68).put("WAVEfmt ".toByteArray()).putInt(16).putShort(3).putShort(2)
            .putInt(44100).putInt(352800).putShort(8).putShort(32).put("data".toByteArray()).putInt(32)
        file.writeBytes(bytes.array());val before=file.readBytes()
        val info=Ir.metadata(file);assertEquals(44100,info.sampleRate);assertEquals(2,info.channels);assertEquals(4L,info.frames)
        assertArrayEquals(before,file.readBytes());file.writeText("unknown");assertEquals(Ir.Metadata(),Ir.metadata(file))
    }
}
