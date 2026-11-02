package me.timschneeberger.rootlessjamesdsp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.appbar.MaterialToolbar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The text stays at the screen center; its icon and switch sit immediately beside it. */
class ProfessionalTitleBar private constructor(private val toolbar: MaterialToolbar) : RelativeLayout(toolbar.context) {
    val titleLabel = TextView(context).apply {
        id = View.generateViewId()
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.textAppearanceListItem))
        val appearance = attributes.getResourceId(0, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
        attributes.recycle()
        setTextAppearance(context, appearance)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val iconView = AppCompatImageView(context).apply { visibility = View.GONE }
    private var power: View? = null
    private var bassCompact = false
    private var powerTarget: CompactPowerTarget? = null
    private var trailing: LinearLayout? = null
    private var trailingViews: List<View> = emptyList()
    private var navigationDrawable: Drawable? = null
    private var navigationOpaqueRight = 1f
    private val titleInkBounds = Rect()
    private val opaqueBoundsCache = java.util.WeakHashMap<Drawable, RectF>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        isClickable = false
        addView(titleLabel, RelativeLayout.LayoutParams(-2, -2).apply { addRule(CENTER_IN_PARENT) })
        addView(iconView, RelativeLayout.LayoutParams(dp(24), dp(24)).apply {
            addRule(START_OF, titleLabel.id); addRule(CENTER_VERTICAL); marginEnd = dp(8)
        })
    }

    fun setIcon(drawable: Drawable?) {
        iconView.setImageDrawable(drawable)
        iconView.visibility = if (bassCompact || drawable == null) View.GONE else View.VISIBLE
    }

    fun setSwitch(toggle: View) {
        if (power === toggle) return
        power?.let(::removeView)
        (toggle.parent as? ViewGroup)?.removeView(toggle)
        power = toggle
        if (bassCompact) { attachCompactPower(toggle); return }
        addView(toggle, RelativeLayout.LayoutParams(-2, dp(30)).apply {
            addRule(END_OF, titleLabel.id); addRule(CENTER_VERTICAL); marginStart = dp(8)
        })
    }

    /** Opt-in for the low-frequency professional page; other title bars keep their original layout. */
    fun configureBassCompact() {
        if (bassCompact) return
        bassCompact = true
        clipChildren = false
        clipToPadding = false
        toolbar.clipChildren = false
        toolbar.clipToPadding = false
        (parent as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
        iconView.visibility = View.GONE
        titleLabel.maxLines = 1
        titleLabel.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        titleLabel.ellipsize = android.text.TextUtils.TruncateAt.END
        power?.let { toggle -> (toggle.parent as? ViewGroup)?.removeView(toggle); attachCompactPower(toggle) }
        requestLayout()
    }

    /** Reparents the existing action views; their listeners, state and accessibility labels stay intact. */
    fun setTrailingActions(actions: List<View>) {
        if (!bassCompact) return
        if (trailingViews.size == actions.size && trailingViews.indices.all { trailingViews[it] === actions[it] }) return
        val row = trailing ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            this@ProfessionalTitleBar.addView(this, RelativeLayout.LayoutParams(-2, dp(48)))
            trailing = this
        }
        row.removeAllViews()
        trailingViews = actions.toList()
        actions.forEach { action ->
            (action.parent as? ViewGroup)?.removeView(action)
            action.minimumWidth = dp(36)
            action.minimumHeight = dp(48)
            if (action is ImageView) {
                action.scaleType = ImageView.ScaleType.CENTER_INSIDE
                sizeCompactAction(action)
            }
            row.addView(action, LinearLayout.LayoutParams(dp(36), dp(48)))
        }
        row.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        requestLayout()
    }

    private fun attachCompactPower(toggle: View) {
        powerTarget?.let(::removeView)
        // Only drawing is reduced. The original switch and its checked listener remain untouched.
        toggle.scaleX = 1f
        toggle.scaleY = 1f
        powerTarget = CompactPowerTarget(toggle).also { addView(it, RelativeLayout.LayoutParams(dp(52), dp(48))) }
    }

    private inner class CompactPowerTarget(private val toggle: View) : FrameLayout(context) {
        private var previousInkCenter: Float? = null
        init {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(toggle, FrameLayout.LayoutParams(-2, dp(30), Gravity.CENTER))
            // The whole 52x48dp region remains clickable, including the padding around the small graphic.
            setOnClickListener { if (toggle.isEnabled) toggle.performClick() }
        }
        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            val desired = compactGraphicHeight()
            val switch = toggle as? androidx.appcompat.widget.SwitchCompat
            val drawableHeight = switch?.trackDrawable
                ?.intrinsicHeight?.takeIf { it > 0 }?.toFloat() ?: child.height.coerceAtLeast(1).toFloat()
            val factor = min(1f, desired / drawableHeight)
            val originalCenter = switch?.let(::switchInkCenterY) ?: previousInkCenter ?: child.height / 2f
            val scaledCenter = height / 2f + factor * (child.top + originalCenter - height / 2f)
            val correction = compactTitleInkCenterY() - top - scaledCenter
            val save = canvas.save()
            canvas.translate(0f, correction)
            canvas.scale(factor, factor, width / 2f, height / 2f)
            val drawn = super.drawChild(canvas, child, drawingTime)
            canvas.restoreToCount(save)
            // SwitchCompat establishes its track/thumb bounds during its own draw.
            // Align from those real bounds on the next frame if this was the first draw.
            switch?.let(::switchInkCenterY)?.let { center ->
                val previous = previousInkCenter
                previousInkCenter = center
                if (previous == null || abs(center - previous) > .5f) postInvalidateOnAnimation()
            }
            return drawn
        }
    }

    private fun switchInkBounds(toggle: androidx.appcompat.widget.SwitchCompat): RectF? {
        val track = toggle.trackDrawable?.let(::drawableInkBounds)
        val thumb = toggle.thumbDrawable?.let(::drawableInkBounds)
        val combined = track ?: thumb ?: return null
        if (track != null && thumb != null) combined.union(thumb)
        return combined
    }

    private fun switchInkCenterY(toggle: androidx.appcompat.widget.SwitchCompat): Float? = switchInkBounds(toggle)?.centerY()

    private fun compactPowerInkHeight(): Float {
        val desired = compactGraphicHeight()
        val toggle = power as? androidx.appcompat.widget.SwitchCompat ?: return desired
        val originalHeight = toggle.trackDrawable?.intrinsicHeight?.takeIf { it > 0 }?.toFloat()
            ?: toggle.height.coerceAtLeast(1).toFloat()
        val factor = min(1f, desired / originalHeight)
        return switchInkBounds(toggle)?.height()?.times(factor) ?: desired
    }

    private fun physicalTitleStart(): Int {
        val fiveMillimetres = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 5f, resources.displayMetrics)
        val button = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .filterIsInstance<ImageButton>().firstOrNull { it.drawable === toolbar.navigationIcon }
        val drawable = button?.drawable
        if (button == null || drawable == null || button.width <= 0 || drawable.bounds.isEmpty)
            return (dp(40) + fiveMillimetres).roundToInt()
        // Keep the 1098 horizontal anchor: only the icon's painting is scaled below.
        val geometry = (drawable as? CompactNavigationDrawable)?.source ?: drawable
        if (navigationDrawable !== geometry) {
            navigationDrawable = geometry
            navigationOpaqueRight = opaqueFractions(geometry).right
        }
        val bounds = RectF(drawable.bounds)
        bounds.right = bounds.left + bounds.width() * navigationOpaqueRight
        button.imageMatrix.mapRect(bounds)
        val position = IntArray(2); val ownPosition = IntArray(2)
        button.getLocationInWindow(position); getLocationInWindow(ownPosition)
        return (position[0] - ownPosition[0] + button.paddingLeft + bounds.right + fiveMillimetres).roundToInt()
    }

    /** Exclude transparent vector padding when matching the actual title glyph height. */
    private fun opaqueFractions(drawable: Drawable): RectF {
        opaqueBoundsCache[drawable]?.let { return it }
        val copy = drawable.constantState?.newDrawable(resources)?.mutate() ?: return RectF(0f, 0f, 1f, 1f)
        val w = copy.intrinsicWidth.coerceIn(1, 128)
        val h = copy.intrinsicHeight.coerceIn(1, 128)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            copy.state = drawable.state
            copy.setBounds(0, 0, w, h); copy.draw(Canvas(bitmap))
            var left = w; var top = h; var right = -1; var bottom = -1
            for (x in 0 until w) for (y in 0 until h) {
                if (bitmap.getPixel(x, y).ushr(24) > 16) {
                    left = min(left, x); top = min(top, y)
                    right = max(right, x); bottom = max(bottom, y)
                }
            }
            (if (right < 0) RectF(0f, 0f, 1f, 1f)
            else RectF(left.toFloat() / w, top.toFloat() / h, (right + 1f) / w, (bottom + 1f) / h)).also {
                opaqueBoundsCache[drawable] = it
            }
        } finally { bitmap.recycle() }
    }

    private fun compactGraphicHeight(): Float {
        val text = titleLabel.text.toString()
        titleLabel.paint.getTextBounds(text, 0, text.length, titleInkBounds)
        val metrics = titleLabel.paint.fontMetrics
        return max(1f, if (titleInkBounds.height() > 0) titleInkBounds.height().toFloat() else metrics.descent - metrics.ascent)
    }

    /** TextView's line box and baseline do not put the visible Chinese glyph at its box center. */
    private fun compactTitleInkCenterY(): Float {
        compactGraphicHeight()
        return if (!titleInkBounds.isEmpty && titleLabel.baseline >= 0)
            titleLabel.top + titleLabel.baseline + titleInkBounds.exactCenterY()
        else titleLabel.top + titleLabel.height / 2f
    }

    private fun drawableInkBounds(drawable: Drawable): RectF? {
        if (drawable.bounds.isEmpty) return null
        if (drawable is CompactNavigationDrawable) return drawable.drawnInkBounds()
        val fractions = opaqueFractions(drawable)
        val bounds = drawable.bounds
        return RectF(bounds.left + bounds.width() * fractions.left, bounds.top + bounds.height() * fractions.top,
            bounds.left + bounds.width() * fractions.right, bounds.top + bounds.height() * fractions.bottom)
    }

    private fun imageInkBounds(image: ImageView): RectF? {
        val ink = image.drawable?.let(::drawableInkBounds) ?: return null
        image.imageMatrix.mapRect(ink)
        ink.offset(image.paddingLeft.toFloat(), image.paddingTop.toFloat())
        return ink
    }

    private fun imageInkCenterY(image: ImageView): Float = imageInkBounds(image)?.centerY() ?: image.height / 2f

    private fun sizeCompactAction(action: ImageView) {
        val fractions = action.drawable?.let(::opaqueFractions)
        val paintedFraction = fractions?.height()?.coerceAtLeast(.01f) ?: 1f
        val side = min(dp(32).toFloat(), compactGraphicHeight() / paintedFraction)
        action.setPadding(((dp(36) - side) / 2f).roundToInt(), ((dp(48) - side) / 2f).roundToInt(),
            ((dp(36) - side) / 2f).roundToInt(), ((dp(48) - side) / 2f).roundToInt())
    }

    /** Preserve the navigation button's original bounds and 5 mm title anchor. */
    private inner class CompactNavigationDrawable(val source: Drawable) : Drawable() {
        private val ink = opaqueFractions(source)
        fun drawnInkBounds(): RectF {
            val factor = min(1f, compactGraphicHeight() / max(1f, bounds.height() * ink.height()))
            val centerX = bounds.exactCenterX(); val centerY = bounds.exactCenterY()
            return RectF(centerX + factor * (bounds.left + bounds.width() * ink.left - centerX),
                centerY + factor * (bounds.top + bounds.height() * ink.top - centerY),
                centerX + factor * (bounds.left + bounds.width() * ink.right - centerX),
                centerY + factor * (bounds.top + bounds.height() * ink.bottom - centerY))
        }
        override fun draw(canvas: Canvas) {
            source.bounds = bounds
            val factor = min(1f, compactGraphicHeight() / max(1f, bounds.height() * ink.height()))
            val save = canvas.save()
            canvas.scale(factor, factor, bounds.exactCenterX(), bounds.exactCenterY())
            source.draw(canvas)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { source.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { source.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = source.intrinsicWidth
        override fun getIntrinsicHeight(): Int = source.intrinsicHeight
        override fun isStateful(): Boolean = source.isStateful
        override fun onStateChange(state: IntArray): Boolean = source.setState(state)
        override fun setTintList(tint: android.content.res.ColorStateList?) { source.setTintList(tint); invalidateSelf() }
        override fun setTintMode(tintMode: android.graphics.PorterDuff.Mode?) { source.setTintMode(tintMode); invalidateSelf() }
    }

    private fun positionCompactRow() {
        val pixelsPerMillimetre = resources.displayMetrics.ydpi / 25.4f
        val graphicHeight = compactGraphicHeight()
        val previousUpwardShift = min(pixelsPerMillimetre * 3f, max(0f, (height - graphicHeight) / 2f))
        val titleCenter = compactTitleInkCenterY()
        // Relative to 1099, move the complete row down by a physical 2 mm.
        // At the regular font size its final offset is 1 mm above the original row.
        val minimumOffset = -titleCenter + graphicHeight / 2f
        val maximumOffset = max(minimumOffset, height - titleCenter - graphicHeight / 2f)
        val rowOffset = (-previousUpwardShift + pixelsPerMillimetre * 2f)
            .coerceIn(minimumOffset, maximumOffset)
        translationY = rowOffset
        var rowBottom = top + rowOffset + titleCenter + graphicHeight / 2f
        if (powerTarget != null) rowBottom = max(rowBottom, top + rowOffset + titleCenter + compactPowerInkHeight() / 2f)
        val icon = toolbar.navigationIcon
        if (icon != null && icon !is CompactNavigationDrawable) toolbar.navigationIcon = CompactNavigationDrawable(icon)
        val navigation = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
            .filterIsInstance<ImageButton>().firstOrNull { it.drawable === toolbar.navigationIcon }
        navigation?.let {
            it.translationY = rowOffset + top - toolbar.top + titleCenter - it.top - imageInkCenterY(it)
            val inkBottom = imageInkBounds(it)?.bottom ?: (imageInkCenterY(it) + graphicHeight / 2f)
            rowBottom = max(rowBottom, toolbar.top + it.top + it.translationY + inkBottom)
        }
        trailingViews.filterIsInstance<ImageView>().forEach { action ->
            sizeCompactAction(action)
            action.translationY = titleCenter - (trailing?.top ?: 0) - action.top - imageInkCenterY(action)
            val inkBottom = imageInkBounds(action)?.bottom ?: (imageInkCenterY(action) + graphicHeight / 2f)
            rowBottom = max(rowBottom, top + rowOffset + (trailing?.top ?: 0) + action.top + action.translationY + inkBottom)
        }
        positionCompactChartBoundary(rowBottom, pixelsPerMillimetre)
    }

    /** Reclaim the old toolbar space through layout, so both chart hosts and the list follow it. */
    private fun positionCompactChartBoundary(rowBottom: Float, pixelsPerMillimetre: Float) {
        val frame = parent as? FrameLayout ?: return
        if (frame.height <= 0 || frame.parent !is LinearLayout) return
        val parameters = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // Keep the title row fixed; move the whole chart/list boundary 3 mm above 1100.
        val margin = (rowBottom + pixelsPerMillimetre * 2f - frame.height).roundToInt()
        if (parameters.bottomMargin == margin) return
        parameters.bottomMargin = margin
        frame.layoutParams = parameters
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (bassCompact) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = resolveSize(dp(56), heightMeasureSpec)
            val exactHeight = MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY)
            val actionWidth = trailingViews.size * dp(36)
            trailing?.measure(MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY), exactHeight)
            powerTarget?.measure(MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY), exactHeight)
            val room = (w - dp(8) - actionWidth - (if (powerTarget == null) 0 else dp(56)) - physicalTitleStart()).coerceAtLeast(0)
            titleLabel.maxWidth = room
            titleLabel.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
            setMeasuredDimension(w, h)
            return
        }
        val leftWing = if (iconView.visibility == View.VISIBLE) dp(32) else 0
        val rightWing = if (power != null) dp(62) else 0
        val limit = (MeasureSpec.getSize(widthMeasureSpec) - 2 * (dp(48) + max(leftWing, rightWing))).coerceAtLeast(dp(64))
        if (titleLabel.maxWidth != limit) titleLabel.maxWidth = limit
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!bassCompact) { super.onLayout(changed, left, top, right, bottom); return }
        val end = width - dp(8)
        val actionStart = end - trailingViews.size * dp(36)
        trailing?.let { it.layout(actionStart, (height - it.measuredHeight) / 2, end, (height + it.measuredHeight) / 2) }
        val start = physicalTitleStart().coerceAtMost(actionStart - (if (powerTarget == null) 0 else dp(56)))
        val room = (actionStart - start - (if (powerTarget == null) 0 else dp(56))).coerceAtLeast(0)
        // Toolbar navigation has completed layout now, so use its real graphic edge on the first frame.
        titleLabel.maxWidth = room
        titleLabel.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        titleLabel.layout(start, (height - titleLabel.measuredHeight) / 2, start + titleLabel.measuredWidth, (height + titleLabel.measuredHeight) / 2)
        powerTarget?.let {
            val x = min(actionStart - it.measuredWidth, start + titleLabel.measuredWidth + dp(4))
            it.layout(x, (height - it.measuredHeight) / 2, x + it.measuredWidth, (height + it.measuredHeight) / 2)
        }
        positionCompactRow()
    }

    companion object {
        fun install(toolbar: MaterialToolbar, title: CharSequence): ProfessionalTitleBar {
            val parent = toolbar.parent as ViewGroup
            val index = parent.indexOfChild(toolbar)
            val parameters = toolbar.layoutParams
            parent.removeView(toolbar)
            val frame = FrameLayout(toolbar.context)
            parent.addView(frame, index, parameters)
            frame.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
            toolbar.title = ""
            return ProfessionalTitleBar(toolbar).also { bar ->
                bar.titleLabel.text = title
                frame.addView(bar, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }
}
