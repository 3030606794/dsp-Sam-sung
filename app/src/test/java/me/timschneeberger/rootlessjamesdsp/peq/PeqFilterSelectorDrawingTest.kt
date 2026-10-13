package me.timschneeberger.rootlessjamesdsp.peq

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeqFilterSelectorDrawingTest {
    @Test fun firstAndLastSavedAndMonitoredFiltersKeepRoundedCornersAndAnUncoveredBorder() {
        checkRoundedEdges()
        exportCompactEditorLayouts()
    }

    @Test
    @Config(qualifiers = "w320dp-h720dp-port-night-mdpi")
    fun darkThemeKeepsTheSameClippingAndUsesItsOwnOutlineColor() {
        checkRoundedEdges()
    }

    @Test fun unmonitoredDraftOutlineFollowsBothEndCurvesAndKeepsOuterStrokeVisible() {
        checkDraftOutline()
    }

    @Test
    @Config(qualifiers = "w320dp-h720dp-port-night-mdpi")
    fun darkUnmonitoredDraftOutlineAlsoFollowsBothEndCurves() {
        checkDraftOutline()
    }

    @Test fun mirroredLayoutRoundsThePhysicalEndsOfTheDraftOutline() {
        checkDraftOutline(rtl = true)
    }

    @Test fun everyPhysicalDividerIsDrawnInBothLeftToRightAndRightToLeftLayouts() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val selector = PeqFilterSelector(context).apply { layoutDirection = direction }
            measure(selector, 280)
            val bitmap = Bitmap.createBitmap(selector.width, selector.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.MAGENTA)
                selector.draw(Canvas(bitmap))
                writeRaster(bitmap, "dividers-${if (direction == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"}")
                val boundaries = (0 until selector.childCount).map { selector.getChildAt(it).left }
                    .filter { it > selector.paddingLeft }
                assertEquals(6, boundaries.size)
                // A one-pixel stroke can occupy two half-covered pixels. Probe the
                // rendered seam away from text, without requiring an opaque color.
                boundaries.forEach { x ->
                    assertTrue("Missing physical divider at $x in direction $direction",
                        (x - 1..x).any { bitmap.getPixel(it, 3) != Color.MAGENTA })
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun optionalInlineIconsRenderBelowAllSevenLabelsAtBothPhoneWidths() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val band = PeqBand("footer-raster", frequency = 12345.0, gain = 7.5, q = 2.5,
            qualityDisplay = PeqQualityDisplay.BW)
        val preferences = context.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        PeqBandLockStore(context).setBandLocked(band.id, false)
        val adapter = PeqBandAdapter(onBandChanged = { true }, onEdit = {})
        adapter.submitState(PeqState(listOf(band), showQSlider = true, showFrequencySlider = true))
        adapter.submitInlineFilter(true)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        adapter.onViewAttachedToWindow(holder)
        val card = holder.itemView as ViewGroup
        val inline = card.findViewById<PeqInlineFilterRow>(R.id.peq_inline_filter)
        fun rectangle(view: View) = Rect().also {
            view.getDrawingRect(it)
            card.offsetDescendantRectToMyCoords(view, it)
        }
        fun visibleAgainstBackground(pixel: Int, background: Int): Boolean = Color.alpha(pixel) > 100 &&
            abs(Color.red(pixel) - Color.red(background)) + abs(Color.green(pixel) - Color.green(background)) +
                abs(Color.blue(pixel) - Color.blue(background)) >= 75
        try {
            for (width in listOf(320, 411)) {
                var textOnlyCardHeight = 0
                for (icons in listOf(false, true)) {
                    adapter.submitCardFilterIcons(icons)
                    measure(card, width)
                    assertEquals("Only the footer grows when curves are shown", if (icons) 56 else 40, inline.height)
                    assertEquals(40, card.findViewById<View>(R.id.peq_band_header).height)
                    if (icons) assertEquals(textOnlyCardHeight + 16, card.height)
                    else textOnlyCardHeight = card.height
                    assertEquals(card.height, rectangle(inline).bottom)
                    val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface))
                        card.draw(Canvas(bitmap))
                        writeRaster(bitmap, "compact-card-footer-${if (icons) "icons" else "text"}-$width")
                        val footerBounds = rectangle(inline)
                        val choices = card.findViewById<ViewGroup>(R.id.peq_inline_choices)
                        for (index in 0 until choices.childCount) {
                            val cellBounds = rectangle(choices.getChildAt(index))
                            assertTrue("Inline cell $index must fit entirely inside its footer: $cellBounds in $footerBounds",
                                footerBounds.contains(cellBounds))
                        }
                        PeqFilterType.entries.forEach { type ->
                            val label = card.findViewById<TextView>(PeqFilterTypeDialog.buttonId(type))
                            val area = rectangle(label)
                            assertTrue("The entire $type label fits its own equal-width cell at $width dp",
                                label.paint.measureText(label.text.toString()) <= label.width)
                            // Thin Chinese glyphs are mostly antialiased rather than the exact
                            // text color. Measure visible contrast with this cell's actual fill.
                            val background = bitmap.getPixel(area.left + 3, area.top + 3)
                            val inkRows = (3 until area.height() - 3).filter { y ->
                                (area.left + 3 until area.right - 3).any { x ->
                                    visibleAgainstBackground(bitmap.getPixel(x, area.top + y), background)
                                }
                            }
                            assertTrue("$type label has visible rendered pixels", inkRows.isNotEmpty())
                            if (icons) {
                                assertCompactTextCurveGap(inkRows, "Footer $type at $width dp")
                            } else {
                                assertTrue("Text-only mode leaves no stale icon at the bottom of $type",
                                    inkRows.none { it >= area.height() - 7 })
                            }
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            adapter.onViewDetachedFromWindow(holder)
            preferences.edit().clear().commit()
        }
    }

    @Test fun collapsedInlineRowRendersAllHeaderCurvesWithTheSameCompactGapInsideItsFixedSlot() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val band = PeqBand("header-raster", frequency = 1000.0, gain = 3.0)
        val preferences = context.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        PeqBandLockStore(context).setBandLocked(band.id, false)
        val adapter = PeqBandAdapter(onBandChanged = { true }, onEdit = {})
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val card = holder.itemView as ViewGroup
        try {
            for (width in listOf(320, 411)) for (type in PeqFilterType.entries) {
                adapter.submitState(PeqState(listOf(band.copy(type = type))))
                adapter.onBindViewHolder(holder, 0)
                measure(card, width)
                val label = card.findViewById<PeqFilterLabelView>(R.id.peq_band_title)
                val area = Rect().also { label.getDrawingRect(it); card.offsetDescendantRectToMyCoords(label, it) }
                assertEquals("Header stays fixed even with an icon", 40, area.height())
                assertEquals(type, label.filterIcon)
                assertEquals(View.GONE, card.findViewById<View>(R.id.peq_inline_filter).visibility)
                val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSurface))
                    card.draw(Canvas(bitmap))
                    writeRaster(bitmap, "compact-card-header-${type.name.lowercase()}-$width")
                    val background = bitmap.getPixel(area.left + 3, area.top + 3)
                    val inkRows = (3 until area.height() - 3).filter { y ->
                        (area.left + 3 until area.right - 3).any { x ->
                            val pixel = bitmap.getPixel(x, area.top + y)
                            abs(Color.red(pixel) - Color.red(background)) +
                                abs(Color.green(pixel) - Color.green(background)) +
                                abs(Color.blue(pixel) - Color.blue(background)) >= 75
                        }
                    }
                    assertCompactTextCurveGap(inkRows, "Header $type at $width dp")
                    assertTrue("Header text and curve have padding inside their fixed slot",
                        inkRows.first() >= 4 && inkRows.last() <= area.height() - 5)
                } finally { bitmap.recycle() }
            }
        } finally { preferences.edit().clear().commit() }
    }

    private fun assertCompactTextCurveGap(inkRows: List<Int>, description: String) {
        assertTrue("$description renders visible text and a visible curve", inkRows.size >= 6)
        val separation = inkRows.zipWithNext().maxByOrNull { (before, after) -> after - before }!!
        val gap = separation.second - separation.first - 1
        // Pixel coverage of a 3 dp geometric gap varies at antialiased glyph edges.
        // This rejects the old 16 dp compound-drawable gap as well as overlapping ink.
        assertTrue("$description keeps a compact 3 dp visible gap, found $gap raster rows", gap in 2..5)
        assertTrue("$description has text above the gap", inkRows.count { it <= separation.first } >= 3)
        assertTrue("$description has a curve below the gap", inkRows.count { it >= separation.second } >= 3)
    }

    private fun checkDraftOutline(rtl: Boolean = false) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val selector = PeqFilterSelector(context)
        selector.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        selector.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        selector.committedType = PeqFilterType.HIGH_SHELF
        val outline = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorOutline)
        val draftColor = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorOnPrimaryContainer)
        fun draw() = Bitmap.createBitmap(selector.width, selector.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
            selector.draw(Canvas(this))
        }
        val baseline = draw()
        val theme = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val direction = if (rtl) "rtl" else "ltr"
        writeRaster(baseline, "baseline-$theme-$direction")
        try {
            for (type in listOf(PeqFilterType.PEAK, PeqFilterType.BAND_PASS)) {
                selector.selectedType = type
                selector.monitoring = false
                val bitmap = draw()
                writeRaster(bitmap, "draft-$type-$theme-$direction")
                try {
                    val right = bitmap.width - 1
                    val bottom = bitmap.height - 1
                    val leftEnd = (type == PeqFilterType.PEAK) != rtl
                    // A square outline leaves these inner curved regions empty. Check
                    // the actual raster, not just the drawable's configured radii.
                    val curvedPixels = (4..12).sumOf { x -> (4..8).count { y ->
                        bitmap.getPixel(if (leftEnd) x else right - x, y) == draftColor
                    } }
                    assertTrue("Draft outline must follow the rounded end: $type", curvedPixels > 3)
                    for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                        val outerEdge = x < 2 || x > right - 2 || y < 2 || y > bottom - 2
                        if (outerEdge && baseline.getPixel(x, y) == outline)
                            assertEquals("Preview must not obscure the capsule border: $type at $x,$y",
                                outline, bitmap.getPixel(x, y))
                    }
                    listOf(2 to 2, right - 2 to 2, 2 to bottom - 2, right - 2 to bottom - 2).forEach { (x, y) ->
                        assertEquals(Color.MAGENTA, bitmap.getPixel(x, y))
                    }
                } finally { bitmap.recycle() }
            }
        } finally { baseline.recycle() }
    }

    private fun writeRaster(bitmap: Bitmap, name: String) {
        val directory = File("build/reports/peq-filter-raster").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** Review artifacts use the actual adapter-bound card and title resources at both phone widths. */
    private fun exportCompactEditorLayouts() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val band = PeqBand("raster-card", frequency = 12345.0, gain = 7.5, q = 2.5,
            qualityDisplay = PeqQualityDisplay.BW)
        PeqBandLockStore(context).setBandLocked(band.id, false)
        val adapter = PeqBandAdapter(onBandChanged = { true }, onEdit = {})
        adapter.submitState(PeqState(listOf(band), showQSlider = true, showFrequencySlider = true))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val title = LayoutInflater.from(context).inflate(R.layout.peq_band_dialog_title, null)
        title.findViewById<TextView>(R.id.peq_band_dialog_title).text = context.getString(R.string.peq_fine_band_title, 170)
        title.findViewById<MaterialButton>(R.id.peq_band_dialog_power).iconTint = ColorStateList.valueOf(Color.rgb(46, 170, 83))
        title.findViewById<MaterialButton>(R.id.peq_band_dialog_lock).iconTint = ColorStateList.valueOf(Color.GRAY)
        fun export(view: View, width: Int, name: String) {
            measure(view, width)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
                view.draw(Canvas(bitmap))
                writeRaster(bitmap, name)
            } finally { bitmap.recycle() }
        }
        for (width in listOf(320, 411)) {
            export(holder.itemView, width, "compact-card-$width")
            export(title, width - 48, "fine-title-$width")
        }
        context.getSharedPreferences(PeqBandLockStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun checkRoundedEdges() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RootlessJamesDSP)
        val selector = PeqFilterSelector(context)
        selector.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        selector.layout(0, 0, selector.measuredWidth, selector.measuredHeight)
        val outline = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorOutline)
        val purple = MaterialColors.getColor(selector, com.google.android.material.R.attr.colorPrimaryContainer)
        val backdrop = Color.MAGENTA
        for (type in listOf(PeqFilterType.PEAK, PeqFilterType.BAND_PASS)) {
            for (monitoring in listOf(false, true)) {
                selector.committedType = if (monitoring) PeqFilterType.HIGH_SHELF else type
                selector.setSelection(type)
                selector.monitoring = monitoring
                val option = selector.findViewById<View>(PeqFilterTypeDialog.buttonId(type))
                for (pressed in listOf(false, true)) {
                    option.isPressed = pressed
                    option.jumpDrawablesToCurrentState()
                    val bitmap = Bitmap.createBitmap(selector.width, selector.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(backdrop)
                        selector.draw(Canvas(bitmap))
                        val message = "$type / monitoring=$monitoring / pressed=$pressed"
                        val right = selector.width - 1
                        val bottom = selector.height - 1
                        // These pixels lie inside rectangular child backgrounds but outside
                        // the rounded parent. A highlight or ripple must never reach them.
                        listOf(2 to 2, right - 2 to 2, 2 to bottom - 2, right - 2 to bottom - 2).forEach { (x, y) ->
                            assertEquals("Rounded corner must reveal the backdrop: $message", backdrop, bitmap.getPixel(x, y))
                        }
                        assertEquals("Left outline remains above the child: $message", outline,
                            bitmap.getPixel(0, selector.height / 2))
                        assertEquals("Right outline remains above the child: $message", outline,
                            bitmap.getPixel(right, selector.height / 2))
                        if (!pressed) {
                            val interiorX = if (type == PeqFilterType.PEAK) 2 else right - 2
                            assertEquals("Clipping must preserve the actual selected fill: $message",
                                if (monitoring) Color.rgb(46, 125, 50) else purple,
                                bitmap.getPixel(interiorX, selector.height / 2))
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                option.isPressed = false
            }
        }
    }
}
