package me.timschneeberger.rootlessjamesdsp.fusion

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.eq.EqPresetStore
import me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.view.EqPreview
import me.timschneeberger.rootlessjamesdsp.view.EqQuickPresetsView
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Actual home preference rows: hint placement, shortcut taps, context menu and persistent selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EqQuickPresetsUiTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val eqPreferences get() = PreferenceCache.getPreferences(app, Constants.PREF_EQ)
    private lateinit var store: EqPresetStore
    private var controller: ActivityController<HomeEqualizerHeadersLayoutTest.HostActivity>? = null

    @Before fun prepare() {
        stopKoin()
        listOf(Constants.PREF_APP, Constants.PREF_EQ, Constants.PREF_GEQ).forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        store = EqPresetStore(app)
        store.preferences.edit().clear().commit()
        val preferences = Preferences(app)
        startKoin { modules(module { single { preferences.App() } }) }
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
        idle()
        stopKoin()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun host() = controller!!.get()
    private fun home(id: Int) = host().supportFragmentManager.findFragmentById(id) as PreferenceGroupFragment
    private fun quick() = host().findViewById<View>(R.id.card_eq).findViewById<EqQuickPresetsView>(R.id.eq_quick_presets)
    private fun buttons() = quick().getChildAt(0) as LinearLayout
    private fun items(): List<TextView> = (0 until buttons().childCount).map { buttons().getChildAt(it) as TextView }
    private fun layout(width: Int = app.resources.configuration.screenWidthDp) {
        idle()
        host().findViewById<View>(android.R.id.content).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            layout(0, 0, width, 720)
        }
        idle()
    }

    private fun launch() {
        controller = Robolectric.buildActivity(HomeEqualizerHeadersLayoutTest.HostActivity::class.java).setup().visible()
        listOf(R.id.card_eq to R.string.key_eq_enable, R.id.card_geq to R.string.key_geq_enable).forEach { (id, key) ->
            home(id).findPreference<SwitchPreferenceGroup>(app.getString(key))!!.setExpanded(true)
        }
        repeat(2) { layout() }
    }

    private fun addFive() = store.all().take(5).also { presets ->
        assertEquals(5, presets.size)
        presets.forEach { assertEquals(EqPresetStore.ShortcutResult.ADDED, store.addShortcut(it.id)) }
    }

    @Test fun shortcutsStartEmptyListenForUserAddsAndNeverAppearInPeq() {
        launch()
        assertEquals(View.GONE, quick().visibility)
        assertTrue(store.shortcuts().isEmpty())
        addFive()
        repeat(2) { layout() }
        assertTrue(quick().isShown)
        assertEquals(5, items().size)
        assertEquals(EqPresetStore.ShortcutResult.FULL, store.addShortcut(store.all()[5].id))
        assertEquals(5, items().size)
        val peq = host().findViewById<View>(R.id.card_geq)
        assertEquals(View.GONE, peq.findViewById<View>(R.id.eq_quick_presets).visibility)
        assertEquals("15 个频段 · 点击调节", host().findViewById<View>(R.id.card_eq)
            .findViewById<TextView>(R.id.node_count).text.toString())
        assertTrue(peq.findViewById<TextView>(R.id.node_count).text.toString().endsWith("个频段 · 点击调节"))
    }

    @Test fun shortcutTapDoesNotLaunchEditorOrEnableSoundAndOnlyItsPillStaysSelected() {
        val presets = addFive()
        launch()
        items()[1].performClick()
        layout()
        assertNull(shadowOf(host()).nextStartedActivity)
        assertFalse(eqPreferences.getBoolean(app.getString(R.string.key_eq_enable), false))
        assertEquals(presets[1].id, store.selectedShortcutId())
        assertEquals(1, items().count { it.isSelected })
        items().forEach { button ->
            assertEquals(button.isSelected, button.background is GradientDrawable)
            if (!button.isSelected) assertNull(button.background)
        }
        items()[3].performClick()
        layout()
        assertEquals(presets[3].id, store.selectedShortcutId())
        assertEquals(1, items().count { it.isSelected })
        assertTrue(items().single { it.isSelected }.tag == presets[3].id)

        val bandKey = app.getString(R.string.key_eq_bands)
        val changed = EqPreview.parse(eqPreferences.getString(bandKey, EqPreview.defaultValue)!!)
        changed[0] = 1.1
        eqPreferences.edit().putString(bandKey, EqPreview.encode(changed)).commit()
        layout()
        assertNull(store.selectedShortcutId())
        assertTrue(items().none { it.isSelected || it.background != null })
        assertFalse(eqPreferences.getBoolean(app.getString(R.string.key_eq_enable), false))
    }

    @Test fun selectedShortcutSurvivesRecreationAndLongPressRemovesOnlyTheShortcut() {
        val presets = addFive()
        launch()
        items()[2].performClick()
        layout()
        controller!!.recreate()
        repeat(2) { layout() }
        assertEquals(5, items().size)
        assertEquals(presets[2].id, items().single { it.isSelected }.tag)
        val before = eqPreferences.all.toMap()
        quick().scrollTo(items()[2].left, 0)
        idle()
        assertTrue(items()[2].performLongClick())
        val popup = ReflectionHelpers.getField<PopupMenu>(quick(), "shortcutMenu")
        assertEquals("移除快捷预设", popup.menu.getItem(0).title.toString())
        popup.menu.performIdentifierAction(popup.menu.getItem(0).itemId, 0)
        layout()
        assertEquals(4, store.shortcuts().size)
        assertEquals(4, items().size)
        assertFalse(store.shortcuts().any { it.id == presets[2].id })
        assertEquals("已移除快捷预设", ShadowToast.getTextOfLatestToast())
        assertEquals("Removing a shortcut leaves the current EQ parameters intact", before, eqPreferences.all)
        assertNull(shadowOf(host()).nextStartedActivity)
    }

    @Test fun compactPhoneKeepsTheHintVisibleWhileAllFiveShortcutsScroll() = checkRow(320)

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-port-mdpi")
    fun regularPhoneKeepsTheHintVisibleWhileAllFiveShortcutsScroll() = checkRow(411)

    private fun checkRow(width: Int) {
        addFive()
        launch()
        items()[0].performClick()
        repeat(2) { layout(width) }
        val hint = host().findViewById<View>(R.id.card_eq).findViewById<TextView>(R.id.node_count)
        val row = hint.parent as ViewGroup
        val hintBounds = bounds(row, hint)
        val viewportBounds = bounds(row, quick())
        assertTrue(hintBounds.right <= viewportBounds.left)
        assertEquals(hintBounds.exactCenterY(), viewportBounds.exactCenterY(), 1f)
        assertEquals(1, hint.lineCount)
        assertEquals(0, hint.layout.getEllipsisCount(0))
        assertTrue(hint.layout.getLineWidth(0) <= hint.width - hint.compoundPaddingLeft - hint.compoundPaddingRight + 1f)
        assertTrue(hintBounds.left >= 0 && viewportBounds.right <= row.width)
        assertTrue(quick().width > 0)
        assertTrue("All five names remain reachable by horizontal scrolling", buttons().width > quick().width)
        items().forEach {
            assertEquals(0, it.layout.getEllipsisCount(0))
            assertTrue(it.layout.getLineWidth(0) <= it.width - it.compoundPaddingLeft - it.compoundPaddingRight + 1f)
        }
        saveScreen("eq-shortcuts-$width")
        quick().scrollTo(buttons().width, 0)
        idle()
        assertTrue(quick().scrollX > 0)
        saveScreen("eq-shortcuts-$width-scrolled")
        val last = bounds(row, items().last())
        assertTrue("Last shortcut $last fits viewport $viewportBounds; scroll=${quick().scrollX}, " +
            "content=${buttons().width}, viewportWidth=${quick().width}, childScroll=${items().last().scrollX}",
            last.right <= viewportBounds.right && last.left >= viewportBounds.left)
        assertEquals("Hint position is independent of shortcut scrolling", hintBounds, bounds(row, hint))
    }

    private fun bounds(parent: ViewGroup, child: View) = Rect().also {
        // Coordinate conversion subtracts each view's own scroll offset, including TextView's.
        child.getDrawingRect(it)
        parent.offsetDescendantRectToMyCoords(child, it)
    }

    private fun saveScreen(name: String) {
        val root = host().findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(MaterialColors.getColor(root, android.R.attr.colorBackground))
            root.draw(Canvas(bitmap))
            val directory = File("build/reports/ui38-fix3").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
