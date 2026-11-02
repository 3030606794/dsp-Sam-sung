package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.EqualizerActivity
import me.timschneeberger.rootlessjamesdsp.eq.EqPreset
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetCodec
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetDocuments
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetStore
import me.timschneeberger.rootlessjamesdsp.testutil.ShadowPosixFileProviderPathStrategy
import androidx.core.content.FileProvider
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.AppFontScale
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualizerPresetsUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val audio get() = app.getSharedPreferences(Constants.PREF_EQ, Context.MODE_PRIVATE)
    private val store get() = EqPresetStore(app)
    private var controller: ActivityController<EqualizerActivity>? = null
    private val activity get() = controller!!.get()
    private var updates = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updates++ }
    }

    @Before fun setup() {
        stopKoin()
        listOf(Constants.PREF_APP, Constants.PREF_VAR, Constants.PREF_EQ, Constants.PREF_GEQ,
            EqPresetStore.PREFERENCES).forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() }; single { preferences.Var() } }) }
        app.registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
    }

    @After fun cleanup() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        idle()
        app.unregisterLocalReceiver(receiver)
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun launch() {
        controller = Robolectric.buildActivity(EqualizerActivity::class.java).setup().visible()
        layout()
    }
    private fun layout() {
        idle()
        val configuration = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val width = (configuration.screenWidthDp * density).roundToInt()
        val height = (configuration.screenHeightDp * density).roundToInt()
        activity.findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, height)
        }
        idle()
    }
    private fun latest(): AlertDialog { idle(); return ShadowDialog.getLatestDialog() as AlertDialog }
    private fun presets(): AlertDialog {
        activity.findViewById<View>(R.id.eq_presets).performClick()
        return latest()
    }
    private fun click(dialog: AlertDialog, tag: String) {
        requireNotNull(dialog.window!!.decorView.findViewWithTag<View>(tag)).performClick()
        idle()
    }
    private fun item(dialog: AlertDialog, index: Int): EqPreset = dialog.listView.adapter.getItem(index) as EqPreset
    private fun row(dialog: AlertDialog, index: Int): View = dialog.listView.adapter.getView(index, null, dialog.listView)
    private fun choose(dialog: AlertDialog, index: Int) {
        dialog.listView.performItemClick(row(dialog, index), index, dialog.listView.adapter.getItemId(index))
        idle()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("$message; latest toast=${ShadowToast.getTextOfLatestToast()}")
    }
    private fun result(requestCode: Int, uri: Uri? = null) {
        assertTrue(activity.activityResultRegistry.dispatchResult(requestCode,
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, uri?.let { Intent().setData(it) }))
        idle()
    }

    @Test fun eachIndependentPlusAddsOnlyAHomeShortcutAndEnforcesFiveWithoutApplyingOrClosing() {
        launch()
        activity.setBandGain(0, 2.7)
        idle()
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val dialog = presets()
        assertEquals(18, dialog.listView.adapter.count)
        assertEquals(ListView.CHOICE_MODE_NONE, dialog.listView.choiceMode)
        capturePresetDialog(dialog, 411)
        repeat(5) { index ->
            val preset = item(dialog, index)
            val card = row(dialog, index)
            assertFalse(card.isSelected)
            assertEquals(Color.TRANSPARENT, (card.background as ColorDrawable).color)
            card.findViewWithTag<View>("eq_preset_add_${preset.id}").performClick()
            idle()
            assertTrue(dialog.isShowing)
            assertEquals(index + 1, store.shortcuts().size)
            assertEquals("已添加到首页快捷", ShadowToast.getTextOfLatestToast())
            assertEquals(beforeAudio, audio.all)
            assertEquals(beforeUpdates, updates)
        }
        row(dialog, 0).findViewWithTag<View>("eq_preset_add_${item(dialog, 0).id}").performClick()
        idle()
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("请勿重复"))
        row(dialog, 5).findViewWithTag<View>("eq_preset_add_${item(dialog, 5).id}").performClick()
        idle()
        assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("最多 5 个"))
        assertEquals(5, store.shortcuts().size)
        assertTrue(dialog.isShowing)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
        val removed = item(dialog, 0)
        row(dialog, 0).findViewWithTag<View>("eq_preset_remove_${removed.id}").performClick()
        idle()
        assertEquals(4, store.shortcuts().size)
        assertEquals("已从首页快捷移除", ShadowToast.getTextOfLatestToast())
        row(dialog, 0).findViewWithTag<View>("eq_preset_remove_${removed.id}").performClick()
        idle()
        assertEquals("此预设尚未加入首页快捷", ShadowToast.getTextOfLatestToast())
        assertTrue(dialog.isShowing)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
        val applied = item(dialog, 1)
        row(dialog, 1).findViewWithTag<TextView>("eq_preset_name_${applied.id}").performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertArrayEquals(applied.gains.toDoubleArray(), EqPreview.parse(audio.getString(app.getString(R.string.key_eq_bands), null)!!), 0.0)
        assertEquals(beforeUpdates + 1, updates)
        assertFalse(audio.getBoolean(app.getString(R.string.key_eq_enable), false))
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
    fun compactPresetDialogKeepsAllActionsReadableAndSeparateShortcutTouchTargets() {
        launch()
        val dialog = presets()
        capturePresetDialog(dialog, 320)
        actions(dialog, 0)
        choose(latest(), 0)
        captureEditor(latest(), "preset-editor-320")
    }

    private fun actions(dialog: AlertDialog, index: Int, name: Boolean = false): AlertDialog {
        val card = row(dialog, index)
        val target = if (name) card.findViewWithTag<View>("eq_preset_name_${item(dialog, index).id}") else card
        assertTrue(target.performLongClick())
        val menu = latest()
        assertEquals(listOf("编辑", "重命名", "副本", "分享", "删除"),
            (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() })
        assertTrue(dialog.isShowing)
        return menu
    }

    private fun gain(dialog: AlertDialog, index: Int): EditText =
        requireNotNull(dialog.window!!.decorView.findViewWithTag("eq_preset_edit_gain_$index"))

    private fun captureEditor(dialog: AlertDialog, suffix: String) {
        val decor = layoutDialog(dialog)
        val density = activity.resources.displayMetrics.density
        repeat(15) { index ->
            val input = gain(dialog, index)
            val minus = decor.findViewWithTag<View>("eq_preset_edit_minus_$index")
            val plus = decor.findViewWithTag<View>("eq_preset_edit_plus_$index")
            assertTrue("Every gain field has usable width", input.width >= 56 * density)
            assertEquals(48 * density, minus.width.toFloat(), 1f)
            assertEquals(48 * density, plus.width.toFloat(), 1f)
            assertTrue(minus.right <= input.left && input.right <= plus.left)
        }
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isShown)
        assertTrue(decor.findViewWithTag<Spinner>("eq_preset_edit_filter").width > 0)
        assertTrue(decor.findViewWithTag<Spinner>("eq_preset_edit_interpolation").width > 0)
        saveDialogs(suffix)
        val scroll = decor.findViewWithTag<ScrollView>("eq_preset_edit_scroll")
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        idle()
        val visible = Rect()
        assertTrue("The fifteenth band remains reachable by scrolling", gain(dialog, 14).getGlobalVisibleRect(visible))
        assertTrue(visible.height() >= gain(dialog, 14).height - 1)
        saveDialogs("$suffix-bottom")
    }

    @Test fun builtinAndPersonalRowsOfferTheSameOrderedManagementActionsWithoutApplyingAudio() {
        launch()
        activity.setBandGain(0, 2.7)
        idle()
        val dialog = presets()
        val original = item(dialog, 0)
        store.addShortcut(original.id)
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val beforeLibrary = store.preferences.all.toMap()
        choose(actions(dialog, 0, name = true), 1)
        val canceled = latest()
        canceled.window!!.decorView.findViewWithTag<EditText>("eq_preset_name_input").setText("取消改名")
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(beforeLibrary, store.preferences.all)
        choose(actions(dialog, 0), 1)
        val rename = latest()
        val name = rename.window!!.decorView.findViewWithTag<EditText>("eq_preset_name_input")
        name.setText(" ")
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(rename.isShowing)
        assertNotNull(name.error)
        name.setText("原声改名")
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(original.id, item(dialog, 0).id)
        assertEquals("原声改名", item(dialog, 0).name)
        assertEquals(original.id, store.shortcuts().single().id)
        assertEquals("原声改名", store.shortcuts().single().name)
        choose(actions(dialog, 0), 2)
        assertEquals(19, dialog.listView.adapter.count)
        val duplicate = item(dialog, 18)
        assertFalse(duplicate.builtIn)
        assertNotEquals(original.id, duplicate.id)
        assertEquals(original.gains, duplicate.gains)
        assertTrue(duplicate.name.contains("副本"))
        choose(actions(dialog, 18, name = true), 4)
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertNotNull(store.find(duplicate.id))
        choose(actions(dialog, 18), 4)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNull(store.find(duplicate.id))
        assertEquals(18, dialog.listView.adapter.count)
        choose(actions(dialog, 0), 4)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertNull(store.find(original.id))
        assertTrue(store.shortcuts().isEmpty())
        assertEquals(17, dialog.listView.adapter.count)
        assertTrue(dialog.isShowing)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
    }

    @Test fun editingPresetsUsesAnIsolatedValidatedDraftAndAppliesOptionsOnlyAfterASeparateListClick() {
        launch()
        activity.setBandGain(0, -3.7)
        idle()
        val personal = store.saveCurrent("个人预设")
        val dialog = presets()
        val original = item(dialog, 0)
        store.addShortcut(original.id)
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val beforeLibrary = store.preferences.all.toMap()
        choose(actions(dialog, 18), 0)
        gain(latest(), 0).setText("4.2")
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(personal, store.find(personal.id))
        assertEquals(beforeLibrary, store.preferences.all)
        choose(actions(dialog, 0), 0)
        val editor = latest()
        gain(editor, 0).setText("12.1")
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(editor.isShowing)
        assertNotNull(gain(editor, 0).error)
        assertEquals(beforeLibrary, store.preferences.all)
        assertEquals(beforeAudio, audio.all)
        gain(editor, 0).setText("1.2")
        click(editor, "eq_preset_edit_plus_0")
        assertEquals("2.2", gain(editor, 0).text.toString())
        click(editor, "eq_preset_edit_minus_0")
        assertEquals("1.2", gain(editor, 0).text.toString())
        editor.window!!.decorView.findViewWithTag<Spinner>("eq_preset_edit_filter").setSelection(3)
        editor.window!!.decorView.findViewWithTag<Spinner>("eq_preset_edit_interpolation").setSelection(2)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val edited = requireNotNull(store.find(original.id))
        assertEquals(1.2, edited.gains[0], 0.0)
        assertEquals("2", edited.filterType)
        assertEquals("1", edited.interpolation)
        assertEquals(original.id, store.shortcuts().single().id)
        assertTrue(dialog.isShowing)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
        row(dialog, 0).findViewWithTag<View>("eq_preset_name_${original.id}").performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals("2", audio.getString(app.getString(R.string.key_eq_filter_type), null))
        assertEquals("1", audio.getString(app.getString(R.string.key_eq_interpolation), null))
        assertArrayEquals(edited.gains.toDoubleArray(), EqPreview.parse(audio.getString(app.getString(R.string.key_eq_bands), null)!!), 0.0)
        assertEquals(beforeUpdates + 1, updates)
    }

    @Test
    @Config(shadows = [ShadowPosixFileProviderPathStrategy::class])
    fun sharingHandsAPortableReadOnlySnapshotToAndroidChooserWithoutApplyingOrSendingIt() {
        launch()
        activity.setBandGain(0, -1.7)
        idle()
        val dialog = presets()
        val preset = item(dialog, 0)
        val beforeAudio = audio.all.toMap()
        val beforeLibrary = store.preferences.all.toMap()
        val beforeUpdates = updates
        choose(actions(dialog, 0, name = true), 3)
        var chooser: Intent? = null
        await("Sharing must open the Android chooser") {
            chooser = shadowOf(activity).nextStartedActivity
            chooser != null
        }
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        val send = requireNotNull(chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        val decoded = EqPresetCodec.decode(EqPresetDocuments.read(app.contentResolver, uri))
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".file_library_provider",
                File(activity.cacheDir, "outside.json"))
        }
        assertEquals(preset.name, decoded.name)
        assertEquals(preset.gains, decoded.gains)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeLibrary, store.preferences.all)
        assertEquals(beforeUpdates, updates)
        assertTrue(dialog.isShowing)
    }

    @Test fun editingOnlyTheFilterPreservesEveryUntouchedImportedGainAtItsOriginalPrecision() {
        launch()
        val source = EqPreset("external", "精确导入", List(15) { if (it == 0) .173 else -.287 })
        val imported = store.importJson(EqPresetCodec.encode(source))
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val dialog = presets()
        choose(actions(dialog, 18), 0)
        val editor = latest()
        editor.window!!.decorView.findViewWithTag<Spinner>("eq_preset_edit_filter").setSelection(3)
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val saved = requireNotNull(store.find(imported.id))
        assertEquals(source.gains, saved.gains)
        assertEquals("2", saved.filterType)
        assertNull(saved.interpolation)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-560dpi")
    fun highDensityNinetyPercentPresetListAndEditorKeepAllControlsReadable() {
        app.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).edit()
            .putFloat(AppFontScale.KEY, 90f).commit()
        launch()
        assertEquals(.9f, activity.resources.configuration.fontScale, .001f)
        assertEquals(3.5f, activity.resources.displayMetrics.density, .001f)
        val dialog = presets()
        capturePresetDialog(dialog, 411, "presets-411-560dpi-font90")
        val menu = actions(dialog, 0, name = true)
        saveDialogs("preset-menu-411-560dpi-font90")
        choose(menu, 0)
        captureEditor(latest(), "preset-editor-411-560dpi-font90")
    }

    private fun layoutDialog(dialog: AlertDialog): ViewGroup {
        val root = activity.findViewById<View>(android.R.id.content)
        val decor = dialog.window!!.decorView as ViewGroup
        repeat(3) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            idle()
        }
        assertTrue(decor.width in 1..root.width && decor.height in 1..root.height)
        return decor
    }

    private fun capturePresetDialog(dialog: AlertDialog, expectedWidth: Int, suffix: String = "presets-$expectedWidth") {
        val root = activity.findViewById<View>(android.R.id.content)
        assertEquals((expectedWidth * root.resources.displayMetrics.density).roundToInt(), root.width)
        val decor = layoutDialog(dialog)
        fun bounds(view: View) = Rect().also {
            view.getDrawingRect(it)
            decor.offsetDescendantRectToMyCoords(view, it)
        }
        fun readable(text: TextView) {
            val position = bounds(text)
            assertTrue("${text.text} stays within the visible dialog: $position",
                position.left >= 0 && position.top >= 0 && position.right <= decor.width && position.bottom <= decor.height)
            assertTrue(text.isShown && text.width > 0 && text.height > 0)
            val layout = requireNotNull(text.layout)
            repeat(layout.lineCount) { line ->
                assertEquals("${text.text} must remain complete", 0, layout.getEllipsisCount(line))
                assertTrue(layout.getLineWidth(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1f)
            }
            assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        val actions = listOf("eq_preset_save", "eq_preset_import", "eq_preset_export").map {
            requireNotNull(decor.findViewWithTag<TextView>(it))
        }
        assertEquals(listOf("保存当前", "导入", "导出"), actions.map { it.text.toString() })
        actions.forEach(::readable)
        actions.zipWithNext().forEach { (left, right) -> assertTrue(bounds(left).right <= bounds(right).left) }
        val list = dialog.listView
        assertTrue("The dialog must display actual preset rows", list.childCount > 0)
        val touchSize = 48 * root.resources.displayMetrics.density
        repeat(list.childCount) { childIndex ->
            val index = list.firstVisiblePosition + childIndex
            val preset = item(dialog, index)
            val card = list.getChildAt(childIndex)
            val label = requireNotNull(card.findViewWithTag<TextView>("eq_preset_name_${preset.id}"))
            val plus = requireNotNull(card.findViewWithTag<TextView>("eq_preset_add_${preset.id}"))
            val minus = requireNotNull(card.findViewWithTag<TextView>("eq_preset_remove_${preset.id}"))
            // A partially visible final row is allowed; verify complete rows and all touch sizes.
            assertEquals(touchSize, plus.width.toFloat(), 1f)
            assertEquals(touchSize, plus.height.toFloat(), 1f)
            assertEquals(touchSize, minus.width.toFloat(), 1f)
            assertEquals(touchSize, minus.height.toFloat(), 1f)
            assertTrue("The preset name leaves space before the right-hand buttons", bounds(label).right <= bounds(minus).left)
            assertEquals("The minus is immediately to the left of the rightmost plus", bounds(minus).right, bounds(plus).left)
            assertEquals("Each row fills the visible list width", list.width - list.paddingLeft - list.paddingRight, card.width)
            assertEquals("The plus stays at the far right, after the row's end padding",
                bounds(card).right - card.paddingRight, bounds(plus).right)
            if (card.top >= 0 && card.bottom <= list.height) {
                readable(label)
                readable(plus)
                readable(minus)
            }
        }
        saveDialogs(suffix)
    }

    private fun saveDialogs(suffix: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
            root.draw(canvas)
            ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { shown ->
                val decor = layoutDialog(shown as AlertDialog)
                val window = shown.window!!
                if (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((255 * window.attributes.dimAmount).roundToInt(), 0, 0, 0))
                }
                canvas.save()
                canvas.translate((root.width - decor.width) / 2f, (root.height - decor.height) / 2f)
                decor.draw(canvas)
                canvas.restore()
            }
            bitmap.setHasAlpha(false)
            val directory = File("build/reports/equalizer-presets").apply { mkdirs() }
            File(directory, "$suffix.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun savingCurrentRequiresANameAndCancelLeavesBothTheLibraryAndCurrentAudioUnchanged() {
        launch()
        activity.setBandGain(0, -3.7)
        audio.edit().putString(app.getString(R.string.key_eq_filter_type), "2")
            .putString(app.getString(R.string.key_eq_interpolation), "1").commit()
        idle()
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val dialog = presets()
        click(dialog, "eq_preset_save")
        val canceled = latest()
        canceled.window!!.decorView.findViewWithTag<EditText>("eq_preset_name_input").setText("取消的预设")
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(18, store.all().size)
        click(dialog, "eq_preset_save")
        val saved = latest()
        val input = saved.window!!.decorView.findViewWithTag<EditText>("eq_preset_name_input")
        input.setText(" ")
        saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(saved.isShowing)
        assertNotNull(input.error)
        input.setText("我的人声")
        saved.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(saved.isShowing)
        assertTrue(dialog.isShowing)
        assertEquals(19, dialog.listView.adapter.count)
        val personal = store.all().last()
        assertEquals("我的人声", personal.name)
        assertEquals(-3.7, personal.gains[0], 0.0)
        assertEquals("2", personal.filterType)
        assertEquals("1", personal.interpolation)
        assertFalse(personal.builtIn)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
    }

    @Test fun documentPickersUseSafAndCancelAfterRotationChangesNeitherAudioNorPresets() {
        launch()
        val dialog = presets()
        val beforeAudio = audio.all.toMap()
        val beforeLibrary = store.preferences.all.toMap()
        click(dialog, "eq_preset_import")
        val import = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, import.intent.action)
        assertEquals("*/*", import.intent.type)
        assertArrayEquals(arrayOf("application/json", "text/plain", "application/octet-stream"),
            import.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        result(import.requestCode)
        click(dialog, "eq_preset_export")
        choose(latest(), 0)
        val export = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.intent.action)
        assertEquals("application/json", export.intent.type)
        controller!!.recreate()
        layout()
        result(export.requestCode)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeLibrary, store.preferences.all)
        assertEquals(0, updates)
    }

    @Test fun validImportAddsAPersonalPresetAndInvalidDocumentsReportErrorsWithoutChangingAudioOrExistingPresets() {
        launch()
        activity.setBandGain(0, 1.2)
        idle()
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        val existing = store.saveCurrent("已有个人预设")
        val dialog = presets()
        val source = EqPreset("external", "导入的人声", List(15) { if (it == 0) -2.3 else 0.0 }, "2", "1")
        val valid = Uri.parse("content://eq-preset-tests/valid")
        val validInput = TrackedInput(EqPresetCodec.encode(source).toByteArray())
        shadowOf(app.contentResolver).registerInputStream(valid, validInput)
        click(dialog, "eq_preset_import")
        result(shadowOf(activity).nextStartedActivityForResult.requestCode, valid)
        await("Import must add the validated document and refresh its list") {
            store.all().size == 20 && dialog.listView.adapter.count == 20
        }
        assertTrue(validInput.closed)
        assertEquals(existing, store.find(existing.id))
        assertEquals(source.name, store.all().last().name)
        assertEquals(source.gains, store.all().last().gains)
        assertEquals(20, dialog.listView.adapter.count)
        val beforeLibrary = store.preferences.all.toMap()
        val invalidDocuments = listOf("not-json".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(EqPresetDocuments.MAX_IMPORT_BYTES + 1) { 32 })
        invalidDocuments.forEachIndexed { index, bytes ->
            val uri = Uri.parse("content://eq-preset-tests/invalid-$index")
            val input = TrackedInput(bytes)
            shadowOf(app.contentResolver).registerInputStream(uri, input)
            ShadowToast.reset()
            click(dialog, "eq_preset_import")
            result(shadowOf(activity).nextStartedActivityForResult.requestCode, uri)
            await("Invalid import must report an error") {
                ShadowToast.getTextOfLatestToast()?.toString()?.startsWith("导入失败：") == true
            }
            assertTrue(input.closed)
            assertEquals(beforeLibrary, store.preferences.all)
            assertEquals(beforeAudio, audio.all)
            assertEquals(beforeUpdates, updates)
        }
    }

    @Test fun exportingCurrentAndAnExistingPresetUsesSnapshotsAndPortableJsonWithoutApplyingThem() {
        launch()
        activity.setBandGain(0, 3.7)
        idle()
        val dialog = presets()
        click(dialog, "eq_preset_export")
        choose(latest(), 0)
        val currentRequest = shadowOf(activity).nextStartedActivityForResult
        assertEquals("application/json", currentRequest.intent.type)
        activity.setBandGain(0, -1.2)
        idle()
        val currentBytes = TrackedOutput()
        val currentUri = Uri.parse("content://eq-preset-tests/current-export")
        shadowOf(app.contentResolver).registerOutputStream(currentUri, currentBytes)
        result(currentRequest.requestCode, currentUri)
        await("Current export must close its stream") { currentBytes.closed }
        val current = EqPresetCodec.decode(currentBytes.toString(Charsets.UTF_8.name()))
        assertEquals("当前设置", current.name)
        assertEquals(3.7, current.gains[0], 0.0)
        val builtIn = item(dialog, 0)
        click(dialog, "eq_preset_export")
        choose(latest(), 1)
        val presetRequest = shadowOf(activity).nextStartedActivityForResult
        val presetBytes = TrackedOutput()
        val presetUri = Uri.parse("content://eq-preset-tests/preset-export")
        shadowOf(app.contentResolver).registerOutputStream(presetUri, presetBytes)
        val beforeAudio = audio.all.toMap()
        val beforeUpdates = updates
        result(presetRequest.requestCode, presetUri)
        await("Preset export must close its stream") { presetBytes.closed }
        val roundTrip = store.importJson(presetBytes.toString(Charsets.UTF_8.name()))
        assertEquals(builtIn.name, roundTrip.name)
        assertEquals(builtIn.gains, roundTrip.gains)
        assertFalse(roundTrip.builtIn)
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeUpdates, updates)
        assertTrue(dialog.isShowing)
    }

    @Test fun documentWriteFailureReportsTheFailureWithoutTouchingSettingsOrPersonalPresets() {
        launch()
        val dialog = presets()
        click(dialog, "eq_preset_export")
        choose(latest(), 0)
        val request = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://eq-preset-tests/write-failure")
        shadowOf(app.contentResolver).registerOutputStream(uri, object : OutputStream() {
            override fun write(value: Int) { throw IOException("模拟文件写入失败") }
        })
        val beforeAudio = audio.all.toMap()
        val beforeLibrary = store.preferences.all.toMap()
        result(request.requestCode, uri)
        await("A failed export must show its error") {
            ShadowToast.getTextOfLatestToast()?.toString()?.contains("导出失败：模拟文件写入失败") == true
        }
        assertEquals(beforeAudio, audio.all)
        assertEquals(beforeLibrary, store.preferences.all)
        assertTrue(dialog.isShowing)
    }

    private class TrackedOutput : ByteArrayOutputStream() {
        @Volatile var closed = false
        override fun close() { super.close(); closed = true }
    }

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile var closed = false
        override fun close() { super.close(); closed = true }
    }
}
