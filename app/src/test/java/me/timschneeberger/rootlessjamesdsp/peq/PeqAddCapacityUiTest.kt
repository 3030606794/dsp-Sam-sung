package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.time.Duration

/** The actual full bank, add-button callbacks, repository and RecyclerView navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PeqAddCapacityUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private lateinit var controller: ActivityController<HostActivity>
    private val fragment get() = controller.get().supportFragmentManager.findFragmentByTag("peq") as PeqFragment
    private fun <T : View> view(id: Int): T = fragment.requireView().findViewById(id)

    @Before fun setup() {
        prefs.edit().clear().commit()
        app.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(repository.save(PeqModel.defaultState(PeqModel.MAX_BANDS)))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        layout()
    }
    @After fun close() { controller.pause().stop().destroy(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun layout() {
        repeat(2) {
            idle()
            controller.get().window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY))
                layout(0, 0, 411, 820)
            }
        }
    }
    private fun tap(button: View) {
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, button.width / 2f, button.height / 2f, 0)
                .also { event -> try { assertTrue(button.dispatchTouchEvent(event)) } finally { event.recycle() } }
        }
        dispatch(MotionEvent.ACTION_DOWN);advance(20);dispatch(MotionEvent.ACTION_UP)
    }

    @Test fun fullBankKeepsBothButtonsEnabledAndASingleClickOnlyReportsTheLimit() {
        val before = prefs.all.toMap()
        for (id in listOf(R.id.peq_add_front, R.id.peq_add_back)) {
            val button = view<MaterialButton>(id)
            assertTrue(button.isEnabled)
            assertEquals(1f, button.alpha, 0f)
            assertTrue(button.performClick())
            assertEquals(app.getString(R.string.peq_copy_limit, PeqModel.MAX_BANDS), ShadowToast.getTextOfLatestToast())
            assertEquals(before, prefs.all)
            assertEquals(PeqModel.MAX_BANDS, repository.load().bands.size)
        }
    }

    @Test fun realDoubleTapsAtCapacityJumpToLastAndFirstWithoutAddingOrSavingAnything() {
        val before = prefs.all.toMap()
        val original = repository.load()
        val list = view<RecyclerView>(R.id.peq_band_list)
        for ((id, position) in listOf(R.id.peq_add_back to PeqModel.MAX_BANDS - 1, R.id.peq_add_front to 0)) {
            val button = view<View>(id)
            tap(button);advance(80);tap(button)
            advance(PeqAddButtonGestures.confirmationDelayMillis() + 1)
            layout()
            val manager = list.layoutManager as LinearLayoutManager
            if (position == 0) assertEquals(0, manager.findFirstVisibleItemPosition())
            else assertEquals(position, manager.findLastVisibleItemPosition())
            assertEquals(app.getString(if (position == 0) R.string.peq_jump_front else R.string.peq_jump_back,
                PeqModel.MAX_BANDS), ShadowToast.getTextOfLatestToast())
            assertEquals(original, repository.load())
            assertEquals("Jumping must not even rewrite the parameter preferences", before, prefs.all)
        }
    }

    @Test fun leavingTheEditorDuringAnUnconfirmedTouchDiscardsThePendingAdd() {
        val before = prefs.all.toMap()
        val toastCount = ShadowToast.shownToastCount()
        tap(view(R.id.peq_add_front))
        controller.pause()
        advance(PeqAddButtonGestures.confirmationDelayMillis() + 1)
        assertEquals(before, prefs.all)
        assertEquals("The canceled add must not even show its capacity warning", toastCount, ShadowToast.shownToastCount())
        controller.resume()
        assertEquals(PeqModel.MAX_BANDS, repository.load().bands.size)
    }

    class HostActivity : AppCompatActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(R.layout.activity_graphic_eq)
            setSupportActionBar(findViewById(R.id.toolbar))
            if (state == null) supportFragmentManager.beginTransaction()
                .replace(R.id.params, PeqFragment(), "peq").commitNow()
        }
    }
}
