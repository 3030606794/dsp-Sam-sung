package me.timschneeberger.rootlessjamesdsp.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceThemeItemBinding
import me.timschneeberger.rootlessjamesdsp.delegates.ThemingDelegate
import me.timschneeberger.rootlessjamesdsp.model.preference.AppTheme
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getResourceColor
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ThemesPreferenceAdapter(private val clickListener: OnItemClickListener) :
    RecyclerView.Adapter<ThemesPreferenceAdapter.ThemeViewHolder>(), KoinComponent {

    private var themes = emptyList<AppTheme>()
    private val preferences: Preferences.App by inject()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        val isAmoled = preferences.get<Boolean>(R.string.key_appearance_pure_black)
        val themeResIds = ThemingDelegate.getThemeResIds(themes[viewType], isAmoled)
        val themedContext = themeResIds.fold(parent.context) {
                context, themeResId ->
            ContextThemeWrapper(context, themeResId)
        }

        val binding = PreferenceThemeItemBinding.inflate(LayoutInflater.from(themedContext), parent, false)
        return ThemeViewHolder(binding)
    }

    override fun getItemViewType(position: Int): Int = position

    override fun getItemCount(): Int = themes.size

    override fun onBindViewHolder(holder: ThemesPreferenceAdapter.ThemeViewHolder, position: Int) {
        holder.bind(themes[position])
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(themes: List<AppTheme>) {
        if (this.themes == themes) return
        this.themes = themes
        notifyDataSetChanged()
    }

    inner class ThemeViewHolder(private val binding: PreferenceThemeItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val view: View get() = binding.root

        private val selectedColor = view.context.getResourceColor(com.google.android.material.R.attr.colorPrimary)
        private val unselectedColor = view.context.getResourceColor(android.R.attr.divider)
        private var initializedTheme: AppTheme? = null

        fun bind(appTheme: AppTheme) {
            binding.name.text = view.context.getString(appTheme.titleResId!!)

            // For rounded corners
            binding.badges.clipToOutline = true

            val storedAppTheme = AppTheme.valueOf(preferences.get(R.string.key_appearance_app_theme))
            val isSelected = storedAppTheme == appTheme
            binding.themeCard.isChecked = isSelected
            binding.themeCard.strokeColor = if (isSelected) selectedColor else unselectedColor

            if (initializedTheme != appTheme) {
                when (appTheme) {
                    AppTheme.LIQUID_GLASS -> bindGlassPreview(isSelected)
                    AppTheme.LIQUID_GLASS_2 -> bindGlass2Preview(isSelected)
                    AppTheme.LIQUID_GLASS_3 -> bindGlass3Preview(isSelected)
                    else -> Unit
                }
                initializedTheme = appTheme
            } else if (appTheme == AppTheme.LIQUID_GLASS || appTheme == AppTheme.LIQUID_GLASS_2 ||
                appTheme == AppTheme.LIQUID_GLASS_3) {
                // Selection changes only the border. Keep the existing preview drawables,
                // nested cards and themed context when an unrelated setting rebinds this row.
                binding.themeCard.strokeColor = if (isSelected) selectedColor else view.context.getColor(
                    if (appTheme == AppTheme.LIQUID_GLASS) R.color.glass_edge else R.color.glass2_edge)
                binding.themeCard.strokeWidth = ((if (isSelected) 3f else 1f) *
                    view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            }

            listOf(binding.root, binding.themeCard).forEach {
                it.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        clickListener.onItemClick(position)
                    }
                }
            }
        }

        private fun bindGlassPreview(isSelected: Boolean) {
            val context = view.context
            val density = context.resources.displayMetrics.density
            val edge = context.getColor(R.color.glass_edge)
            binding.themePreviewCanvas.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    context.getColor(R.color.glass_background_top),
                    context.getColor(R.color.glass_background_middle),
                    context.getColor(R.color.glass_background_bottom),
                ),
            )
            binding.themeCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.themeCard.strokeColor = if (isSelected) selectedColor else edge
            binding.themeCard.strokeWidth = ((if (isSelected) 3f else 1f) * density).toInt()
            binding.themeCard.radius = 19f * density

            // Two overlapping translucent sheets and their bright edges identify glass
            // even before the user applies the theme.
            binding.coverContainer.apply {
                setCardBackgroundColor(context.getColor(R.color.glass_card))
                radius = 11f * density
                strokeColor = edge
                strokeWidth = density.toInt().coerceAtLeast(1)
                cardElevation = 0f
            }
            binding.coverContainer.foreground = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    context.getColor(R.color.glass_card_start),
                    Color.TRANSPARENT,
                    context.getColor(R.color.glass_glow_blue),
                ),
            ).apply { cornerRadius = 11f * density }
            binding.bottomNav.apply {
                setCardBackgroundColor(context.getColor(R.color.glass_bar))
                radius = 9f * density
                strokeColor = edge
                strokeWidth = density.toInt().coerceAtLeast(1)
                cardElevation = 0f
            }
            binding.topNav.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(context.getColor(R.color.glass_card_start), Color.TRANSPARENT),
            )
            binding.badges.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    context.getColor(R.color.glass_primary),
                    context.getColor(R.color.glass_tertiary),
                ),
            ).apply { cornerRadius = 6f * density }
            for (index in 0 until binding.badges.childCount) {
                binding.badges.getChildAt(index).setBackgroundColor(Color.TRANSPARENT)
            }
        }

        private fun bindGlass2Preview(isSelected: Boolean) {
            val context = view.context
            val density = context.resources.displayMetrics.density
            val edge = context.getColor(R.color.glass2_edge)
            val blue = context.getColor(R.color.glass2_glow_blue)
            val violet = context.getColor(R.color.glass2_glow_violet)
            binding.themePreviewCanvas.background = Glass2PreviewBackdrop(
                context.getColor(R.color.glass2_background_top),
                context.getColor(R.color.glass2_background_bottom), blue, violet,
                context.getColor(R.color.glass2_glow_cyan),
            )
            binding.themeCard.apply {
                setCardBackgroundColor(Color.TRANSPARENT)
                strokeColor = if (isSelected) selectedColor else edge
                strokeWidth = ((if (isSelected) 3f else 1f) * density).toInt().coerceAtLeast(1)
                radius = 19f * density
            }
            binding.coverContainer.apply {
                setCardBackgroundColor(context.getColor(R.color.glass2_card))
                radius = 11f * density
                strokeColor = edge
                strokeWidth = density.toInt().coerceAtLeast(1)
                cardElevation = 2f * density
                foreground = Glass2PreviewLens(density, 11f, edge,
                    context.getColor(R.color.glass2_edge_shadow))
            }
            binding.bottomNav.apply {
                setCardBackgroundColor(context.getColor(R.color.glass2_bar))
                radius = 9f * density
                strokeColor = edge
                strokeWidth = density.toInt().coerceAtLeast(1)
                cardElevation = 2f * density
                foreground = Glass2PreviewLens(density, 9f, edge,
                    context.getColor(R.color.glass2_edge_shadow))
            }
            binding.topNav.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(context.getColor(R.color.glass2_card_start), Color.TRANSPARENT),
            )
            binding.topNavText.apply {
                setCardBackgroundColor(context.getColor(R.color.glass2_on_surface))
                alpha = 0.88f
            }
            binding.bottomNavSelectedItem.apply {
                setCardBackgroundColor(context.getColor(R.color.glass2_primary))
                strokeColor = edge
                strokeWidth = density.toInt().coerceAtLeast(1)
            }
            binding.bottomNavUnselectedItem.apply {
                setCardBackgroundColor(context.getColor(R.color.glass2_on_surface))
                alpha = 0.52f
            }
            binding.badges.apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(context.getColor(R.color.glass2_primary),
                        context.getColor(R.color.glass2_tertiary))).apply {
                    cornerRadius = 6f * density
                    setStroke(density.toInt().coerceAtLeast(1), edge)
                }
                for (index in 0 until childCount) {
                    getChildAt(index).setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }

        private fun bindGlass3Preview(isSelected: Boolean) {
            // A separate holder and a flat original canvas distinguish III from the colorful II.
            bindGlass2Preview(isSelected)
            val context = view.context
            val density = context.resources.displayMetrics.density
            val primary = context.getResourceColor(com.google.android.material.R.attr.colorPrimary)
            val foreground = context.getResourceColor(com.google.android.material.R.attr.colorOnSurface)
            binding.themePreviewCanvas.setBackgroundColor(context.getResourceColor(android.R.attr.colorBackground))
            binding.topNavText.setCardBackgroundColor(foreground)
            binding.bottomNavSelectedItem.setCardBackgroundColor(primary)
            binding.bottomNavUnselectedItem.setCardBackgroundColor(foreground)
            binding.badges.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 185),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 85))).apply {
                cornerRadius = 6f * density
                setStroke(density.toInt().coerceAtLeast(1), context.getColor(R.color.glass2_edge))
            }
        }
    }

    /** A richer canvas makes the separate clear-glass theme identifiable in the picker. */
    private class Glass2PreviewBackdrop(
        private val top: Int, private val bottom: Int,
        private val blue: Int, private val violet: Int, private val cyan: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds)
            if (rect.isEmpty) return
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
            listOf(Triple(.18f, .48f, blue), Triple(.91f, .59f, violet),
                Triple(.72f, .12f, cyan)).forEach { (x, y, color) ->
                paint.shader = RadialGradient(rect.left + rect.width() * x,
                    rect.top + rect.height() * y, rect.width() * .82f,
                    color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(rect, paint)
            }
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Border-only highlight leaves all preview content visible through the material. */
    private class Glass2PreviewLens(
        private val density: Float, private val radiusDp: Float,
        private val edge: Int, private val shadow: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds)
            if (rect.isEmpty) return
            rect.inset(density * .65f, density * .65f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density * 1.3f
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(edge, Color.TRANSPARENT, shadow, edge),
                floatArrayOf(0f, .43f, .72f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radiusDp * density, radiusDp * density, paint)
            rect.inset(density * 1.2f, density * 1.2f)
            paint.strokeWidth = density * .5f
            paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(edge, Color.TRANSPARENT, edge),
                floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, (radiusDp - 1.2f) * density,
                (radiusDp - 1.2f) * density, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int)
    }
}
