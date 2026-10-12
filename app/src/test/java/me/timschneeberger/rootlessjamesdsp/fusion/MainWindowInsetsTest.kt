package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.MainWindowInsets
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises real system-inset dispatch through the shipping home CoordinatorLayout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class MainWindowInsetsTest {
    private var controller: ActivityController<InsetsHostActivity>? = null

    @After fun cleanUp() { controller?.pause()?.stop()?.destroy() }

    private fun launch(): InsetsHostActivity =
        Robolectric.buildActivity(InsetsHostActivity::class.java).also {
            controller = it
            it.setup().visible()
        }.get()

    private fun insets(statusTop: Int, navigationBottom: Int, cutout: Insets = Insets.NONE): WindowInsetsCompat {
        val builder = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBottom))
        if (cutout != Insets.NONE) {
            builder.setDisplayCutout(DisplayCutoutCompat(
                Rect(cutout.left, cutout.top, cutout.right, cutout.bottom), emptyList()
            ))
        }
        return builder.build()
    }

    private fun dispatch(activity: InsetsHostActivity, insets: WindowInsetsCompat, width: Int = 1080, height: Int = 2200) {
        ViewCompat.dispatchApplyWindowInsets(activity.root, insets)
        activity.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        activity.root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun statusBarAndCutoutKeepFirstCardBelowSystemAreaWithoutRestoringToolbar() {
        val activity = launch()
        val content = activity.findViewById<View>(R.id.main_content)
        val baseTop = activity.baseTop
        dispatch(activity, insets(statusTop = 72, navigationBottom = 60, cutout = Insets.of(0, 100, 0, 0)))
        assertEquals(baseTop + 100, content.paddingTop)
        assertEquals(60, content.paddingBottom)
        assertEquals("Coordinator must not inset this already protected child a second time", 0, content.top)
        assertNull((content.layoutParams as CoordinatorLayout.LayoutParams).behavior)
        val card = activity.findViewById<View>(R.id.pinned_output_card)
        val bounds = Rect(0, 0, card.width, card.height)
        activity.root.offsetDescendantRectToMyCoords(card, bounds)
        assertTrue("FIIO/device card must clear the cutout plus a small visual gap", bounds.top > 100)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.appBarLayout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.toolbar).visibility)
    }

    @Test fun repeatedDispatchAndLandscapeUseFreshInsetsWithoutAccumulatingPadding() {
        val activity = launch()
        val content = activity.findViewById<View>(R.id.main_content)
        repeat(3) { dispatch(activity, insets(72, 60)) }
        assertEquals(activity.baseTop + 72, content.paddingTop)
        assertEquals(60, content.paddingBottom)
        dispatch(activity, insets(40, 30, Insets.of(96, 0, 0, 0)), width = 2200, height = 1080)
        assertEquals(activity.baseTop + 40, content.paddingTop)
        assertEquals(96, content.paddingLeft)
        assertEquals(0, content.paddingRight)
        assertEquals(30, content.paddingBottom)
        dispatch(activity, insets(72, 60))
        assertEquals(activity.baseTop + 72, content.paddingTop)
        assertEquals(0, content.paddingLeft)
    }

    @Test fun recreationStartsWithOriginalPaddingAndTopInsetsDoNotMoveBottomNavigation() {
        var activity = launch()
        dispatch(activity, insets(40, 60))
        val bar = activity.findViewById<View>(R.id.bar)
        val originalBounds = Rect(bar.left, bar.top, bar.right, bar.bottom)
        val originalPadding = Rect(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom)
        dispatch(activity, insets(120, 60))
        assertEquals(originalBounds, Rect(bar.left, bar.top, bar.right, bar.bottom))
        assertEquals(originalPadding, Rect(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bar.paddingBottom))
        controller!!.recreate()
        activity = controller!!.get()
        dispatch(activity, insets(72, 60))
        assertEquals(activity.baseTop + 72, activity.findViewById<View>(R.id.main_content).paddingTop)
        dispatch(activity, WindowInsetsCompat.Builder().build())
        assertEquals(activity.baseTop, activity.findViewById<View>(R.id.main_content).paddingTop)
    }

    class InsetsHostActivity : Activity() {
        lateinit var root: CoordinatorLayout
        var baseTop = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Keep real layout geometry; replace only fragment hosts to avoid starting DSP/service code.
            layoutInflater.factory2 = object : LayoutInflater.Factory2 {
                override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                    onCreateView(name, context, attrs)
                override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                    if (name == "androidx.fragment.app.FragmentContainerView") FrameLayout(context, attrs) else null
            }
            root = layoutInflater.inflate(R.layout.activity_dsp_main, null) as CoordinatorLayout
            setContentView(root)
            val content = findViewById<View>(R.id.main_content)
            baseTop = content.paddingTop
            MainWindowInsets.install(content)
            layoutInflater.inflate(R.layout.fragment_dsp, findViewById<FrameLayout>(R.id.dsp_fragment_container), true)
            findViewById<FrameLayout>(R.id.card_device_profiles).addView(TextView(this).apply {
                text = "FIIO KA17"
                minimumHeight = 48
            })
        }
    }
}
