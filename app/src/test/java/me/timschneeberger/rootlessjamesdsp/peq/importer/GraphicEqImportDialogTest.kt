package me.timschneeberger.rootlessjamesdsp.peq.importer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.peq.PeqBand
import me.timschneeberger.rootlessjamesdsp.peq.PeqModel
import me.timschneeberger.rootlessjamesdsp.peq.PeqPresetStore
import me.timschneeberger.rootlessjamesdsp.peq.PeqRepository
import me.timschneeberger.rootlessjamesdsp.peq.PeqState
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixAtomicFile
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private typealias DialogFitTask = (GraphicEqCurve, PeqState, GraphicEqFitOptions,
    (GraphicEqFitProgress) -> Unit, () -> Boolean) -> GraphicEqFitResult

/** Exercises the real importer and atomic favorite store without touching a live DSP session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi", shadows = [ShadowPosixAtomicFile::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GraphicEqImportDialogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)
    private val repository get() = PeqRepository(app)
    private val original = PeqState(listOf(PeqBand("current-band", frequency = 650.0, gain = 2.75, q = 1.25)))
    private val flatCurve = "GraphicEQ: 20 -6; 1000 -6; 20000 -6"
    private lateinit var store: PeqPresetStore
    private lateinit var scope: CoroutineScope
    private lateinit var originalPreferences: Map<String, *>
    private var controller: ActivityController<HostActivity>? = null
    private var importer: GraphicEqImportDialog? = null
    private lateinit var main: AlertDialog
    private var sampleRate = 48000.0
    private var pickCount = 0
    private var savedCount = 0
    private val releases = mutableListOf<CountDownLatch>()
    private val workers = mutableListOf<CountDownLatch>()

    @Before fun prepare() {
        prefs.edit().clear().commit()
        assertTrue(repository.save(original))
        prefs.edit().putBoolean(app.getString(R.string.key_geq_enable), true)
            .putString("unrelated-setting", "retain").commit()
        originalPreferences = prefs.all.toMap()
        store = PeqPresetStore(File(temporary.newFolder(), "peq-presets.json"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun cleanup() {
        importer?.dismissAll()
        scope.cancel()
        releases.forEach { it.countDown() }
        workers.forEach { it.await(3, TimeUnit.SECONDS) }
        controller?.pause()?.stop()?.destroy()
        idle()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }

    private fun launch(task: DialogFitTask? = null) {
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        val activity = controller!!.get()
        val defaultTask: DialogFitTask = { curve, state, options, progress, cancelled ->
            GraphicEqFitter.fit(curve, state, options, progress, cancelled)
        }
        importer = GraphicEqImportDialog(activity, LayoutInflater.from(activity), scope,
            readCurrent = { repository.load() }, readSampleRate = { sampleRate },
            onPickDocument = { pickCount++ }, onSaved = { savedCount++ },
            store = store, fitTask = task ?: defaultTask)
        importer!!.show()
        idle()
        main = ShadowDialog.getLatestDialog() as AlertDialog
        layout()
    }

    private fun layout() {
        repeat(2) {
            idle()
            main.window!!.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
                layout(0, 0, measuredWidth, measuredHeight)
            }
        }
        idle()
    }

    private fun text(id: Int) = main.findViewById<EditText>(id)!!
    private fun view(id: Int) = main.findViewById<View>(id)!!
    private fun saveButton() = main.getButton(AlertDialog.BUTTON_POSITIVE)
    private fun status() = main.findViewById<TextView>(R.id.geq_import_status)!!.text.toString()

    private fun fit(source: String = flatCurve, name: String = "导入校正") {
        text(R.id.geq_import_name).setText(name)
        text(R.id.geq_import_text).setText(source)
        assertFalse(saveButton().isEnabled)
        assertTrue(view(R.id.geq_import_fit).performClick())
        await("Fitting did not produce a savable preview: ${status()}") { saveButton().isEnabled }
        assertEquals(View.VISIBLE, view(R.id.geq_import_result).visibility)
    }

    private fun confirmation(): AlertDialog {
        assertTrue(saveButton().isEnabled)
        assertTrue(saveButton().performClick())
        idle()
        return (ShadowDialog.getLatestDialog() as AlertDialog).also {
            assertNotSame("A separate confirmation must precede a library write", main, it)
            assertTrue(it.isShowing)
        }
    }

    private fun assertCurrentUnchanged() {
        assertEquals(original, repository.load())
        assertEquals(originalPreferences, prefs.all)
        assertTrue(prefs.getBoolean(app.getString(R.string.key_geq_enable), false))
    }

    @Test fun pastedCurveFitsAndSavesOnlyANewFavoriteWithoutApplyingOrEnablingAnything() {
        launch()
        fit()
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
        val confirm = confirmation()
        assertTrue(store.load().isEmpty())
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val saved = store.load().single()
        assertEquals("导入校正", saved.name)
        assertEquals(2, saved.state.bands.size)
        assertTrue(saved.state.bands.none { it.id == "current-band" })
        assertEquals(1, savedCount)
        assertFalse(main.isShowing)
        assertCurrentUnchanged()
    }

    @Test fun documentPickerCallbackReplacesTextAndNameButNeverAppliesIt() {
        launch()
        fit()
        assertTrue(view(R.id.geq_import_pick).performClick())
        assertEquals(1, pickCount)
        importer!!.setDocument("GraphicEQ: 20 -3; 20000 -3", "耳机校正.txt")
        assertEquals("耳机校正", text(R.id.geq_import_name).text.toString())
        assertEquals("GraphicEQ: 20 -3; 20000 -3", text(R.id.geq_import_text).text.toString())
        assertFalse(saveButton().isEnabled)
        assertEquals(View.GONE, view(R.id.geq_import_result).visibility)
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
        importer!!.dismissAll()
        importer!!.setDocument(flatCurve, "迟到的文件.txt")
        idle()
        assertFalse(main.isShowing)
        assertTrue(store.load().isEmpty())
    }

    @Test fun documentResultsAreBoundToTheirOriginalWindowInputAndRequest() {
        launch()
        val beforeEdit = importer!!.documentReceiver()
        text(R.id.geq_import_text).setText(flatCurve)
        beforeEdit("GraphicEQ: 20 5; 20000 5", "旧请求.txt")
        assertEquals(flatCurve, text(R.id.geq_import_text).text.toString())

        val beforeClose = importer!!.documentReceiver()
        importer!!.dismissAll()
        importer!!.show()
        idle()
        main = ShadowDialog.getLatestDialog() as AlertDialog
        beforeClose(flatCurve, "旧窗口.txt")
        assertEquals("", text(R.id.geq_import_text).text.toString())

        val superseded = importer!!.documentReceiver()
        val latest = importer!!.documentReceiver()
        superseded(flatCurve, "被替代.txt")
        assertEquals("", text(R.id.geq_import_text).text.toString())
        latest(flatCurve, "有效请求.txt")
        assertEquals(flatCurve, text(R.id.geq_import_text).text.toString())
        assertEquals("有效请求", text(R.id.geq_import_name).text.toString())
        latest("GraphicEQ: 20 5; 20000 5", "重复回调.txt")
        assertEquals(flatCurve, text(R.id.geq_import_text).text.toString())
        assertEquals("有效请求", text(R.id.geq_import_name).text.toString())
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun editingTextOrFitOptionsDiscardsTheOldPreviewButRenamingDoesNot() {
        launch()
        fit()
        text(R.id.geq_import_name).setText("只改名称")
        assertTrue(saveButton().isEnabled)
        text(R.id.geq_import_text).setText("GraphicEQ: 20 -2; 20000 -2")
        assertFalse(saveButton().isEnabled)
        assertEquals(View.GONE, view(R.id.geq_import_result).visibility)
        fit()
        main.findViewById<ChipGroup>(R.id.geq_import_bands)!!.check(R.id.geq_import_band_20)
        assertFalse(saveButton().isEnabled)
        fit()
        main.findViewById<ChipGroup>(R.id.geq_import_errors)!!.check(R.id.geq_import_error_025)
        assertFalse(saveButton().isEnabled)
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun changingTextWhileConfirmationIsOpenCannotSaveTheObsoleteResult() {
        launch()
        fit()
        val confirm = confirmation()
        text(R.id.geq_import_text).setText("GraphicEQ: 20 -2; 20000 -2")
        idle()
        assertFalse(confirm.isShowing)
        // Even a queued click from the dismissed confirmation must not commit its captured result.
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(store.load().isEmpty())
        assertEquals(0, savedCount)
        assertFalse(saveButton().isEnabled)
        assertCurrentUnchanged()
    }

    @Test fun aSampleRateChangeInvalidatesThePreviewBeforeSaving() {
        launch()
        fit()
        val confirm = confirmation()
        sampleRate = 44100.0
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(confirm.isShowing)
        assertFalse(saveButton().isEnabled)
        assertTrue(status().contains("采样率"))
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun invalidTextAndDuplicateNameLeaveTheExistingLibraryUntouched() {
        val existing = store.save("已有预设", original)
        launch()
        text(R.id.geq_import_text).setText("GraphicEQ: 20 NaN; 20000 0")
        view(R.id.geq_import_fit).performClick()
        await("Invalid text was not rejected") {
            view(R.id.geq_import_fit).isEnabled && status().isNotEmpty()
        }
        assertFalse(saveButton().isEnabled)
        fit(name = "已有预设")
        saveButton().performClick()
        idle()
        assertTrue(main.findViewById<TextInputLayout>(R.id.geq_import_name_box)!!.error!!.contains("存在"))
        assertEquals(listOf(existing), store.load())
        assertTrue(main.isShowing)
        assertTrue(saveButton().isEnabled)
        text(R.id.geq_import_name).setText("新的名称")
        assertNull(main.findViewById<TextInputLayout>(R.id.geq_import_name_box)!!.error)
        assertCurrentUnchanged()
    }

    @Test fun capacityIsRecheckedAtConfirmationAndNeverOverwritesTwentyExistingPresets() {
        launch()
        fit()
        val confirm = confirmation()
        repeat(PeqPresetStore.MAX_PRESETS) { store.save("预设 ${it + 1}", original) }
        val before = store.load()
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(confirm.isShowing)
        assertEquals(before, store.load())
        assertEquals(0, savedCount)
        assertTrue(status().contains("20"))
        assertTrue(main.isShowing)
        assertTrue(saveButton().isEnabled)
        assertCurrentUnchanged()
    }

    private fun cancellationTask(entered: CountDownLatch, cancelled: CountDownLatch): DialogFitTask {
        val release = CountDownLatch(1).also { releases.add(it) }
        workers.add(cancelled)
        return { _, _, _, progress, isCancelled ->
            entered.countDown()
            try {
                progress(GraphicEqFitProgress(.1, "计算中"))
                while (!isCancelled()) {
                    check(!release.await(5, TimeUnit.MILLISECONDS)) { "Test released an uncancelled worker" }
                }
                throw CancellationException("Cancelled by dialog")
            } finally { cancelled.countDown() }
        }
    }

    private fun startBlockedFit(entered: CountDownLatch) {
        text(R.id.geq_import_text).setText(flatCurve)
        view(R.id.geq_import_fit).performClick()
        assertTrue("Background fit did not begin", entered.await(3, TimeUnit.SECONDS))
        idle()
        assertFalse(view(R.id.geq_import_fit).isEnabled)
    }

    @Test fun cancelButtonStopsTheWorkerAndKeepsTheDialogReadyToRetry() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        launch(cancellationTask(entered, stopped))
        startBlockedFit(entered)
        view(R.id.geq_import_cancel_fit).performClick()
        assertTrue("Cancel did not reach the background computation", stopped.await(3, TimeUnit.SECONDS))
        idle()
        assertTrue(main.isShowing)
        assertTrue(view(R.id.geq_import_fit).isEnabled)
        assertFalse(saveButton().isEnabled)
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun anObsoleteWorkerCannotPublishAResultAfterTheInputHasChanged() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1).also { workers.add(it) }
        val release = CountDownLatch(1).also { releases.add(it) }
        val result = GraphicEqFitter.fit(GraphicEqParser.parse(flatCurve), original)
        launch { _, _, _, progress, _ ->
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                // Model a numerical operation completing just after cancellation was requested.
                progress(GraphicEqFitProgress(1.0, "旧结果完成"))
                result
            } finally { stopped.countDown() }
        }
        startBlockedFit(entered)
        text(R.id.geq_import_text).setText("GraphicEQ: 20 -2; 20000 -2")
        release.countDown()
        assertTrue(stopped.await(3, TimeUnit.SECONDS))
        await("The obsolete fit did not finish its completion callback") {
            scope.coroutineContext[Job]!!.children.none { !it.isCompleted }
        }
        assertFalse(saveButton().isEnabled)
        assertEquals(View.GONE, view(R.id.geq_import_result).visibility)
        assertTrue(view(R.id.geq_import_fit).isEnabled)
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun dismissingTheImporterStopsItsWorkerAndIgnoresLateCallbacks() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        launch(cancellationTask(entered, stopped))
        startBlockedFit(entered)
        main.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue("Dismiss did not cancel the background computation", stopped.await(3, TimeUnit.SECONDS))
        importer!!.setDocument(flatCurve, "迟到的文件.txt")
        idle()
        assertFalse(main.isShowing)
        assertEquals(0, savedCount)
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun cancellingTheHostScopeStopsItsWorkerAndClosesTheImporter() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        launch(cancellationTask(entered, stopped))
        startBlockedFit(entered)
        scope.cancel()
        assertTrue("Destroyed host did not cancel the fit", stopped.await(3, TimeUnit.SECONDS))
        await("The importer remained attached after host scope cancellation") { !main.isShowing }
        assertTrue(store.load().isEmpty())
        assertCurrentUnchanged()
    }

    @Test fun largeFontAt320DpKeepsControlsWithinTheDialogAndDrawsTheResponseChart() {
        RuntimeEnvironment.setFontScale(1.5f)
        launch()
        fit()
        view(R.id.geq_import_advanced).performClick()
        layout()
        for (id in listOf(R.id.geq_import_pick, R.id.geq_import_text, R.id.geq_import_name,
            R.id.geq_import_advanced, R.id.geq_import_fit, R.id.geq_import_chart)) {
            val control = view(id)
            assertTrue("Control $id has no usable width", control.width > 0)
            assertTrue("Control $id has no usable height", control.height > 0)
            val parent = control.parent as ViewGroup
            assertTrue("Control $id overflows left", control.left >= 0)
            assertTrue("Control $id overflows right", control.right <= parent.width)
        }
        for (groupId in listOf(R.id.geq_import_bands, R.id.geq_import_errors)) {
            val group = main.findViewById<ChipGroup>(groupId)!!
            for (index in 0 until group.childCount) {
                val chip = group.getChildAt(index)
                assertTrue(chip.width > 0 && chip.height > 0)
                assertTrue("Fit option clips at large font", chip.left >= 0 && chip.right <= group.width)
            }
        }
        assertTrue(saveButton().isShown)
        assertTrue(main.getButton(AlertDialog.BUTTON_NEGATIVE).isShown)
        val chart = view(R.id.geq_import_chart)
        assertTrue(chart.contentDescription.contains("dB"))
        val bitmap = Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
        try { chart.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        assertCurrentUnchanged()
    }

    class HostActivity : FragmentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this))
        }
    }
}
