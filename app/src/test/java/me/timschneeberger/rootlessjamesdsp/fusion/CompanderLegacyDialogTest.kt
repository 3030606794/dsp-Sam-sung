package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.CompanderDialogFragment
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import me.timschneeberger.rootlessjamesdsp.view.CompanderSurface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CompanderLegacyDialogTest: CompanderUiFixture() {
    private val original="83.125;217.50;431.25;877;1777;3777;7999;0.100;-0.200;0.300;-0.400;0.500;-0.600;0.700;"
    private data class Opened(val fragment:CompanderDialogFragment,val surface:CompanderSurface)
    private fun open():Opened {
        prefs.edit().putString(p.BASE_CURVE,original).commit();launch()
        assertTrue(fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!.isEnabled)
        assertTrue(row(p.BASE_CURVE).performClick());idle()
        controller!!.get().supportFragmentManager.executePendingTransactions();idle()
        val dialog=controller!!.get().supportFragmentManager.fragments.filterIsInstance<CompanderDialogFragment>().single()
        val surface=dialog.requireDialog().findViewById<CompanderSurface>(R.id.compander_surface)!!
        surface.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
        surface.layout(0,0,320,400)
        return Opened(dialog,surface)
    }
    private fun drag(opened:Opened,y:Float=.2f) {
        val surface=opened.surface;val start=SystemClock.uptimeMillis()
        for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
            val event=MotionEvent.obtain(start,start+index,action,surface.width*.45f,surface.height*y,0)
            try {assertTrue(surface.dispatchTouchEvent(event))} finally {event.recycle()}
        }
        idle()
    }
    private fun close(opened:Opened,positive:Boolean) {
        val dialog=opened.fragment.requireDialog() as AlertDialog
        dialog.getButton(if(positive)AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick();idle()
    }

    @Test fun draggingAndSavingTheLegacyCurvePreservesAllSevenCustomFrequencies() {
        val opened=open();val frequencies=requireNotNull(p.parseCurve(original)).frequencies
        assertArrayEquals(frequencies.toDoubleArray(),opened.surface.frequencyScale,0.0)
        drag(opened)
        val preview=prefs.getString(p.BASE_CURVE,null)!!;assertNotEquals(original,preview)
        assertEquals(frequencies,requireNotNull(p.parseCurve(preview)).frequencies)
        assertNotEquals(p.parseCurve(original)!!.coefficients,p.parseCurve(preview)!!.coefficients)
        close(opened,true)
        assertEquals(frequencies,requireNotNull(p.parseCurve(prefs.getString(p.BASE_CURVE,null))).frequencies)
        assertEquals(preview,prefs.getString(p.BASE_CURVE,null))
    }

    @Test fun cancellingAfterLivePreviewRestoresTheExactOriginalTextAndSingleTrailingSemicolon() {
        val opened=open();drag(opened)
        assertNotEquals(original,prefs.getString(p.BASE_CURVE,null));close(opened,false)
        assertEquals(original,prefs.getString(p.BASE_CURVE,null))
        assertEquals(original,fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!.initialValue)
    }

    @Test fun professionalTakeoverBlocksLateDraggingAndCancellationEvenAfterControlsAreRestored() {
        val opened=open();drag(opened)
        val preview=prefs.getString(p.BASE_CURVE,null)
        prefs.edit().putBoolean(p.PROFESSIONAL,true).putBoolean(p.CORE_ON,true).commit();layout()
        assertFalse(fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!.isEnabled)
        drag(opened,.8f);assertEquals(preview,prefs.getString(p.BASE_CURVE,null))
        prefs.edit().putBoolean(p.PROFESSIONAL,false).putBoolean(p.CORE_ON,false).commit();layout()
        assertTrue(fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!.isEnabled)
        drag(opened,.6f);assertEquals(preview,prefs.getString(p.BASE_CURVE,null))
        close(opened,false);assertEquals(preview,prefs.getString(p.BASE_CURVE,null))
    }

    @Test fun anExternallyAppliedNewCurveSurvivesLateDraggingAndCancellationOfTheOldDialog() {
        val opened=open();drag(opened)
        val replacement="71;191;391;791;1591;3391;7491;-0.1;0.2;-0.3;0.4;-0.5;0.6;-0.7;"
        prefs.edit().putString(p.BASE_CURVE,replacement).commit();layout()
        drag(opened,.8f);assertEquals(replacement,prefs.getString(p.BASE_CURVE,null))
        close(opened,false);assertEquals(replacement,prefs.getString(p.BASE_CURVE,null))
        assertEquals(replacement,fragment().findPreference<CompanderPreference>(p.BASE_CURVE)!!.initialValue)
    }
}
