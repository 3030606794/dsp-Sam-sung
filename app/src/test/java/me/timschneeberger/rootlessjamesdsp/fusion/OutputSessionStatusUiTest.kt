package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.fragment.OutputControlFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h820dp-port")
@LooperMode(LooperMode.Mode.PAUSED)
class OutputSessionStatusUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<HostActivity>
    private val fragment get() = controller.get().supportFragmentManager.findFragmentByTag("output") as OutputControlFragment
    private val label get() = fragment.requireView().findViewById<TextView>(R.id.output_sample_rate)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun prepare() {
        app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(Constants.PREF_OUTPUT, Context.MODE_PRIVATE).edit().clear().commit()
        stopKoin()
        val preferences = Preferences(app)
        val route = RoutingObserver(app)
        startKoin { modules(module {
            single { preferences.App() }
            single { route }
        }) }
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        idle()
    }
    @After fun close() { controller.pause().stop().destroy(); idle(); stopKoin() }

    @Test fun topStatusPairsEachActualSidWithItsRateAndClearsItWhenPaused() {
        val tracker = OutputRuntimeStatus.Tracker()
        val player = OutputRuntimeStatus.Session("effect125", true, 192000, 0, 125)
        val other = OutputRuntimeStatus.Session("effect200", true, 44100, 0, 200)
        fragment.renderRuntimeStatus(tracker.update(listOf(player, other)))
        assertFalse(label.text.contains("192"))
        assertEquals(app.getString(R.string.output_rate_unknown), label.text.toString())
        fragment.renderRuntimeStatus(tracker.update(listOf(player.copy(processedFrames = 512),
            other.copy(processedFrames = 512))))
        assertEquals(app.getString(R.string.output_runtime_session_rate, 125, "192") + "\n" +
            app.getString(R.string.output_runtime_session_rate, 200, "44.1"), label.text.toString())
        fragment.renderRuntimeStatus(tracker.update(listOf(player.copy(processedFrames = 512),
            other.copy(processedFrames = 512))))
        assertEquals(app.getString(R.string.output_runtime_standby), label.text.toString())
        assertEquals(com.google.android.material.color.MaterialColors.getColor(label,
            com.google.android.material.R.attr.colorOnSurfaceVariant), label.currentTextColor)
        assertEquals(app.getString(R.string.output_driver_connected),
            fragment.requireView().findViewById<TextView>(R.id.output_driver_status).text.toString())
        assertFalse(label.text.contains("Session"))
        assertFalse(label.text.contains("192"))
    }

    @Test fun globalZeroIsShownOnlyWhenConfirmedAndPlayerModeWithoutSessionsExplainsWaiting() {
        val tracker = OutputRuntimeStatus.Tracker()
        val global = OutputRuntimeStatus.Session("effect0", true, 48000, 0, 0)
        tracker.update(listOf(global))
        fragment.renderRuntimeStatus(tracker.update(listOf(global.copy(processedFrames = 512))))
        assertEquals(app.getString(R.string.output_runtime_session_rate, 0, "48"), label.text.toString())
        val empty = tracker.update(emptyList())
        fragment.renderRuntimeStatus(empty, waitingForPlayerSession = true)
        assertEquals(app.getString(R.string.output_runtime_waiting_session), label.text.toString())
        assertFalse(label.text.contains("48"))
        fragment.renderRuntimeStatus(empty, waitingForPlayerSession = false)
        assertEquals(app.getString(R.string.output_rate_idle), label.text.toString())
    }

    @Test fun actualSystemGlobalAndPlayerSessionsHaveDistinctAccurateLabels() {
        val player = OutputRuntimeStatus.Snapshot(true, OutputRuntimeStatus.Processing.ACTIVE,
            listOf(192000), listOf(OutputRuntimeStatus.ActiveSession(353, 192000)),
            OutputRuntimeStatus.Mode.PLAYER_SESSION)
        val global = player.copy(mode = OutputRuntimeStatus.Mode.GLOBAL, rates = listOf(48000),
            activeSessions = listOf(OutputRuntimeStatus.ActiveSession(0, 48000)))
        assertNotEquals(player, global)
        fragment.renderRuntimeStatus(player)
        assertEquals(app.getString(R.string.output_runtime_session_rate, 353, "192"), label.text.toString())
        fragment.renderRuntimeStatus(global)
        assertEquals(app.getString(R.string.output_runtime_global_rate, 0, "48"), label.text.toString())
        fragment.renderRuntimeStatus(player)
        assertEquals(app.getString(R.string.output_runtime_session_rate, 353, "192"), label.text.toString())
    }

    @Test fun attachedButUnprocessedGlobalChainReportsSuspensionOrBypassWithoutInventingARate() {
        val global = OutputRuntimeStatus.Snapshot(true, OutputRuntimeStatus.Processing.IDLE,
            mode = OutputRuntimeStatus.Mode.GLOBAL)
        for ((issue, text) in listOf(
            OutputRuntimeStatus.GlobalIssue.SUSPENDED to R.string.output_global_suspended,
            OutputRuntimeStatus.GlobalIssue.DIFFERENT_OUTPUT to R.string.output_global_different_output,
            OutputRuntimeStatus.GlobalIssue.NO_PROCESSING to R.string.output_global_not_processing)) {
            fragment.renderRuntimeStatus(global.copy(globalIssue = issue))
            assertEquals(app.getString(text), label.text.toString())
            assertFalse(label.text.contains("48"))
            assertFalse(label.text.contains("192"))
        }
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { id = CONTENT })
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .add(CONTENT, OutputControlFragment(), "output").commitNow()
        }
        companion object { const val CONTENT = 0x6f65 }
    }
}
