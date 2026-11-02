package me.timschneeberger.rootlessjamesdsp.effectvisuals

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.expansion.*
import me.timschneeberger.rootlessjamesdsp.creative.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/** Actual Android canvas rendering at phone density, including the regression which produced 10 px labels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w411dp-h900dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectVisualRenderingTest {
    private fun context()=ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_RootlessJamesDSP)
    @Test fun axisAndStatusTextUseSpRatherThanTextPaintDensityAtHighResolution() {
        val ctx=context()
        for(sp in listOf(11f,12f)) {
            val view=EffectVisualView(ctx,sp)
            val paint=EffectVisualView::class.java.getDeclaredField("text").apply {isAccessible=true}.get(view) as Paint
            assertEquals(sp*ctx.resources.displayMetrics.scaledDensity,paint.textSize,.01f)
            assertTrue("High-density labels must not be clamped to ten pixels",paint.textSize>20f)
            assertTrue(view.minimumReadableHeight>=paint.fontSpacing*10)
        }
        val config=android.content.res.Configuration(ctx.resources.configuration).apply {fontScale=1.5f}
        val bigger=ContextThemeWrapper(ctx.createConfigurationContext(config),R.style.Theme_RootlessJamesDSP)
        val normal=EffectVisualView(ctx,12f);val large=EffectVisualView(bigger,12f)
        assertTrue("Larger fonts receive more vertical space",large.minimumReadableHeight>normal.minimumReadableHeight)
    }
    @Test fun allTwentyFiveThreePanelLayoutsRenderUsingTheProductionView() {
        ExpansionEffectsKind.values().forEach {kind->
            val values=mutableMapOf<String,Any>(kind.enableKey to true)
            fun set(key:String,value:Int){values[ExpansionEffectsParameters.control(kind,key).key]=value}
            when(kind){
                ExpansionEffectsKind.MS_EQ->{set("mid_0_gain",4500);set("side_3_gain",-3000)}
                ExpansionEffectsKind.FORMANT->set("shift",4000)
                ExpansionEffectsKind.TRANSIENT->set("mid_attack",6000)
                else->Unit
            }
            val focus=ExpansionEffectsParameters.controls(kind).getOrElse(1){ExpansionEffectsParameters.controls(kind).first()}.key
            val sample=runtime(kind)
            val pair=ExpansionEffectsVisuals.static(kind,values,focus)
            val history=(0..20).map {i->EffectVisualFrame(i*200L,sample.meters,sample.inputPeakDb,sample.outputPeakDb,sample.wetPeakDb,48000,i==0)}
            render(kind.name,pair,ExpansionEffectsVisuals.live(kind,values,focus,sample,history))
        }
        CreativeEffectsKind.values().forEach {kind->
            val values=mapOf<String,Any>(kind.enableKey to true)
            val focus=CreativeEffectsParameters.controls(kind)[1].key
            val sample=CreativeEffectsRuntime(kind,true,48000,48000,1,-12f,-14f,-18f,
                if(kind==CreativeEffectsKind.GRANULAR)5 else if(kind==CreativeEffectsKind.FREEZE)1 else 8,
                if(kind==CreativeEffectsKind.GRANULAR)62500 else if(kind==CreativeEffectsKind.SHIMMER)1200 else 1,
                .42f,List(16){-18f-it},true,true)
            val pair=CreativeEffectsVisuals.pair(kind,values,focus)
            val history=(0..20).map {CreativeEffectsVisuals.Sample(it*200L,sample,it==0)}
            render(kind.name,pair,CreativeEffectsVisuals.live(kind,history))
        }
    }
    @Test fun mixedOneAndTwoLineHeadingsKeepStaticPanelsAligned() {
        val ctx=context();val d=ctx.resources.displayMetrics.density
        val pair=EffectVisualPair(EffectVisual("专业参数"),EffectVisual("反射路径与移调尾声方向示意"))
        val expansion=ExpansionEffectsHeader(ctx,ExpansionEffectsKind.TAPE,true,true,true,{},{},{})
        expansion.setConfiguration(pair,emptyMap<String,Any>(),null)
        val creative=CreativeEffectsHeader(ctx,CreativeEffectsKind.SHIMMER,true,true,true,{},{},{})
        creative.setConfiguration(pair)
        for(header in listOf<View>(expansion,creative)) {
            header.measure(View.MeasureSpec.makeMeasureSpec((411*d).toInt(),View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            header.layout(0,0,header.measuredWidth,header.measuredHeight)
            fun field(name:String)=header.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(header) as View
            fun relativeTop(view:View):Int {var v=view;var y=0;while(v!==header){y+=v.top;v=v.parent as View};return y}
            assertEquals("Both static panels start on the same row",relativeTop(field("parameter")),relativeTop(field("illustration")))
            assertEquals(field("parameter").height,field("illustration").height)
        }
        expansion.close();creative.close()
    }
    private fun render(name:String,pair:EffectVisualPair,live:EffectVisual) {
        val ctx=context();val d=ctx.resources.displayMetrics.density
        val width=(387*d).toInt();val gap=(8*d).toInt();val half=(width-gap)/2
        val staticHeight=maxOf((168*d).toInt(),
            EffectVisualView(ctx).apply {setVisual(pair.professional)}.minimumReadableHeight,
            EffectVisualView(ctx).apply {setVisual(pair.illustration)}.minimumReadableHeight)
        val liveHeight=maxOf((192*d).toInt(),EffectVisualView(ctx,12f).apply {setVisual(live)}.minimumReadableHeight)
        val title=(23*d).toInt();val total=title+staticHeight+title+liveHeight
        val bitmap=Bitmap.createBitmap(width,total,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(255,251,255))
        val caption=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=13*ctx.resources.displayMetrics.scaledDensity;color=Color.BLACK}
        fun draw(visual:EffectVisual,x:Int,y:Int,w:Int,h:Int,sp:Float) {
            val view=EffectVisualView(ctx,sp);view.setVisual(visual)
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
            canvas.save();canvas.translate(x.toFloat(),y.toFloat());view.draw(canvas);canvas.restore()
        }
        canvas.drawText(pair.professional.title,0f,caption.textSize,caption)
        canvas.drawText(pair.illustration.title,(half+gap).toFloat(),caption.textSize,caption)
        draw(pair.professional,0,title,half,staticHeight,11f);draw(pair.illustration,half+gap,title,half,staticHeight,11f)
        canvas.drawText(live.title,0f,(title+staticHeight).toFloat()+caption.textSize,caption)
        draw(live,0,title+staticHeight+title,width,liveHeight,12f)
        var colored=0
        for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4)if(bitmap.getPixel(x,y)!=Color.rgb(255,251,255))colored++
        assertTrue("$name must render nonempty explanatory geometry",colored>150)
        val output=File("build/reports/effect-visuals-raster").apply {mkdirs()}
        File(output,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun runtime(kind:ExpansionEffectsKind):ExpansionEffectsRuntime {
        val meters=MutableList(116){0f};meters[0]=-12f;meters[1]=-14f;meters[2]=-18f;meters[3]=.5f
        when(kind) {
            ExpansionEffectsKind.SMART_ADAPT->{
                meters[0]=3f;meters[1]=2500f;meters[2]=1.2f;meters[3]=-.8f;meters[4]=.4f
                meters[5]=.08f;meters[6]=-1.2f;meters[11]=.7f;meters[22]=2f;meters[24]=2.5f;meters[25]=3f
            }
            ExpansionEffectsKind.MICROSHIFT->{meters[4]=.35f;meters[5]=9f;meters[6]=18f;meters[7]=1.2f}
            ExpansionEffectsKind.FORMANT,ExpansionEffectsKind.VOCODER->repeat(16){meters[4+it]=-18f-it*2}
            ExpansionEffectsKind.FREQUENCY_SHIFT->{meters[4]=.4f;meters[5]=40f;meters[6]=20f;meters[7]=5.31f}
            ExpansionEffectsKind.GATED_REVERB->{meters[4]=.7f;meters[5]=80f;meters[6]=-24f;meters[7]=1.4f}
            ExpansionEffectsKind.PINGPONG->{meters[4]=400f;meters[5]=120f;meters[6]=.35f}
            ExpansionEffectsKind.AUTO_WAH->{meters[4]=1200f;meters[5]=-24f;meters[6]=2f}
            ExpansionEffectsKind.AUTOPAN->{meters[4]=.2f;meters[5]=.6f;meters[6]=1.28f}
            ExpansionEffectsKind.LOW_PHASE->{meters[4]=100f;meters[5]=2f;meters[6]=.6f}
            ExpansionEffectsKind.TAPE->{meters[4]=.35f;meters[5]=.6f;meters[6]=-18f;meters[7]=4f}
            ExpansionEffectsKind.MS_EQ->{repeat(48){meters[20+it]=(3*sin(it*.1)).toFloat();meters[68+it]=(-2*sin(it*.1)).toFloat()}}
            ExpansionEffectsKind.MATCH_EQ->{meters[4]=1f;meters[5]=1f;repeat(32){meters[20+it]=-20f-it;meters[52+it]=-24f-it;meters[84+it]=(3*sin(it*.2)).toFloat()}}
            ExpansionEffectsKind.TRANSIENT->{meters[4]=3f;meters[5]=-2f;meters[6]=4f}
            ExpansionEffectsKind.RESONANCE->{meters[4]=2f;meters[5]=6f;meters[6]=1000f;meters[7]=6f;repeat(48){meters[20+it]=-24f-it/4;meters[68+it]=(-5*sin(it*.1).let{v->v*v}).toFloat()}}
            ExpansionEffectsKind.SUB_BASS->{meters[4]=42f;meters[5]=-18f;meters[6]=-24f}
            ExpansionEffectsKind.LOUDNESS->{meters[4]=.3f;meters[5]=4f;meters[6]=1.5f;repeat(96){meters[20+it]=(4*cosine(it/95.0)).toFloat()}}
            ExpansionEffectsKind.DEREVERB->{meters[4]=3f;repeat(48){meters[20+it]=-26f-it/3;meters[68+it]=-3f}}
            ExpansionEffectsKind.DECLICK->{meters[4]=12f;meters[5]=.15f;meters[6]=3f}
            ExpansionEffectsKind.DEHUM->{meters[4]=50.1f;meters[5]=-32f}
            ExpansionEffectsKind.HEADTRACK->{meters[4]=25f;meters[5]=4f;meters[7]=30f;meters[8]=1f;meters[9]=20f}
            ExpansionEffectsKind.VOCAL->{meters[4]=3f;meters[5]=2786.4f;meters[6]=585f;meters[8]=-15f;meters[9]=-20f;meters[10]=1f}
            ExpansionEffectsKind.SPEAKER_OPTIMIZATION->{
                meters[3]=1f;meters[4]=1f;meters[5]=1f;meters[7]=3f
                meters[8]=80f;meters[9]=13500f;meters[10]=420f
                meters[13]=.5f;meters[14]=1f;meters[15]=3.88f;meters[16]=1f;meters[17]=1f
                repeat(48){meters[20+it]=-30f-it*.3f;meters[68+it]=-33f-it*.3f+(8*sin(it*.1)).toFloat()}
            }
        }
        return ExpansionEffectsRuntime(kind,true,48000,48000,1,-12f,-14f,-18f,meters,true,true)
    }
    private fun cosine(x:Double)=kotlin.math.cos(x*kotlin.math.PI)*.5+.5
}
