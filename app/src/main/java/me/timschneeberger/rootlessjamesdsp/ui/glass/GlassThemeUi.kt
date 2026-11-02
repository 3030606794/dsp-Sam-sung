package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationBarView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Presentation only: no effect settings, control listeners, or touch targets are replaced. */
object GlassThemeUi {
    enum class Role { PANEL, HEADER, NAVIGATION, DIALOG }

    private val sessions = WeakHashMap<Activity, Session>()
    private val dialogObservers = WeakHashMap<View, MaterialObserver>()
    private val fallbackEnvironments = WeakHashMap<View, GlassEnvironment>()
    @Volatile private var appearancePreferences: SharedPreferences? = null
    @Volatile private var currentThemeName: String? = null
    private var themePreferenceKey = ""
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == themePreferenceKey)
            currentThemeName = prefs.getString(themePreferenceKey, "DEFAULT")
    }

    fun isEnabled(context: Context): Boolean = when (themeName(context)) {
        "LIQUID_GLASS", "LIQUID_GLASS_2", "LIQUID_GLASS_3" -> true
        else -> false
    }

    fun isSecondVersion(context: Context): Boolean = themeName(context) == "LIQUID_GLASS_2"

    fun isThirdVersion(context: Context): Boolean = themeName(context) == "LIQUID_GLASS_3"

    fun hasLensMaterial(context: Context): Boolean = when (themeName(context)) {
        "LIQUID_GLASS_2", "LIQUID_GLASS_3" -> true
        else -> false
    }

    private fun themeName(context: Context): String? {
        if (appearancePreferences == null) observeAppearance(context)
        return currentThemeName
    }

    @Synchronized private fun observeAppearance(context: Context) {
        if (appearancePreferences != null) return
        val application = context.applicationContext
        themePreferenceKey = application.getString(R.string.key_appearance_app_theme)
        val preferences = application.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        currentThemeName = preferences.getString(themePreferenceKey, "DEFAULT")
        preferences.registerOnSharedPreferenceChangeListener(appearanceListener)
        appearancePreferences = preferences
    }

    fun install(activity: Activity) {
        if (!isEnabled(activity)) return
        if (isThirdVersion(activity)) Glass3Motion.install(activity.window)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        sessions[activity]?.let { session ->
            if (session.root.get() === root) { session.checkContainers(); return }
            session.dispose()
        }
        val session = Session(root)
        sessions[activity] = session
        root.background = GlassBackdropDrawable(session.environment)
        activity.window.setBackgroundDrawable(GlassBackdropDrawable(session.environment))
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        session.walk(root)
        session.start()
    }

    fun dispose(activity: Activity) {
        sessions.remove(activity)?.dispose()
        Glass3Motion.dispose(activity.window)
    }

    fun decorateSurface(view: View, role: Role = Role.PANEL) {
        if (!isEnabled(view.context) || view.background is GlassSurfaceDrawable) return
        val environment = environmentFor(view)
        val radius = when (role) {
            Role.HEADER -> 16f
            Role.NAVIGATION -> 0f
            Role.DIALOG -> 28f
            Role.PANEL -> 22f
        } * environment.density
        view.background = GlassSurfaceDrawable(view, environment, role, radius)
    }

    fun decorateCard(card: MaterialCardView) {
        if (!isEnabled(card.context) || card.id == R.id.theme_card || card.childCount == 0) return
        val environment = environmentFor(card)
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeWidth = 0
        card.cardElevation = (if (environment.thirdVersion) 0f else if (environment.secondVersion) 4f else 3f) * environment.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.outlineAmbientShadowColor = if (environment.night) 0x41000000 else 0x20354d7e
            card.outlineSpotShadowColor = if (environment.night) 0x54000000 else 0x24314970
        }
        // The existing content is the card's only measured child. Keeping it in place preserves
        // MaterialCardView's shape, foreground, accessibility, and all existing view references.
        val child = card.getChildAt(0)
        if (child.background !is GlassSurfaceDrawable) {
            child.background = GlassSurfaceDrawable(child, environment, Role.PANEL, card.radius)
        }
        card.clipToOutline = true
    }

    fun decorateDialog(window: Window?) {
        if (window == null || !isEnabled(window.context)) return
        val decor = window.decorView
        val environment = environmentFor(decor)
        if (decor.background !is GlassSurfaceDrawable) {
            val padding = Rect()
            decor.background?.getPadding(padding)
            window.setBackgroundDrawable(GlassSurfaceDrawable(decor, environment, Role.DIALOG,
                28f * environment.density, padding))
        }
        window.setDimAmount(if (environment.secondVersion) {
            if (environment.night) .24f else .13f
        } else if (environment.night) .30f else .17f)
        // Only the separate window backdrop is blurred; dialog text and controls stay sharp.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(((if (environment.secondVersion) 26f else 18f) * environment.density).toInt())
        }
        if (environment.secondVersion) decorateDialogControls(decor)
        if (environment.thirdVersion) {
            Glass3Motion.install(window)
            if (!dialogObservers.containsKey(decor)) {
                val observer = MaterialObserver(decor)
                dialogObservers[decor] = observer
                observer.start()
                decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = observer.start()
                    override fun onViewDetachedFromWindow(view: View) = observer.dispose()
                })
            }
        }
    }

    private fun decorateDialogControls(view: View) {
        if (view.id == R.id.themes_list || view.id == R.id.theme_card) return
        GlassControlUi.decorate(view)
        if (view is MaterialCardView) decorateCard(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) decorateDialogControls(view.getChildAt(index))
        }
    }

    internal fun environmentFor(view: View): GlassEnvironment {
        val activity = activityOf(view.context)
        activity?.let { sessions[it] }?.let { return it.environment }
        val root = activity?.findViewById<View>(android.R.id.content) ?: view.rootView
        // Early preference binding and dialog creation precede Session.install(). Reuse the
        // same backdrop there too, instead of allocating a bitmap environment per control.
        return fallbackEnvironments.getOrPut(root) { GlassEnvironment(root) }
    }

    private fun activityOf(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return current as? Activity
    }

    private fun refreshMaterial(view: View) {
        if (view.id == R.id.themes_list || view.id == R.id.theme_card) return
        view.background?.invalidateSelf()
        view.foreground?.invalidateSelf()
        GlassControlUi.decorate(view)
        view.invalidate()
        if (view is ViewGroup) for (index in 0 until view.childCount) refreshMaterial(view.getChildAt(index))
    }

    /** Defer redecoration until slider listener dispatch has finished. */
    private class MaterialObserver(view: View) : SharedPreferences.OnSharedPreferenceChangeListener {
        private val root = WeakReference(view)
        private val preferences = view.context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE)
        private var observing = false
        private var pending = false
        fun start() {
            if (observing) return
            observing = true
            preferences.registerOnSharedPreferenceChangeListener(this)
        }
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (!observing || pending || (key != null && !key.startsWith(Glass3Preferences.PREFIX))) return
            val view = root.get() ?: return
            pending = true
            view.post {
                pending = false
                if (observing) root.get()?.let(::refreshMaterial)
            }
        }
        fun dispose() {
            observing = false
            preferences.unregisterOnSharedPreferenceChangeListener(this)
        }
    }

    private class Children(view: ViewGroup) {
        val count = view.childCount
        val first = WeakReference(view.getChildAt(0))
        val last = WeakReference(view.getChildAt(count - 1))
        fun changed(view: ViewGroup) = count != view.childCount ||
            first.get() !== view.getChildAt(0) || last.get() !== view.getChildAt(view.childCount - 1)
    }

    private class Session(content: ViewGroup) : ViewTreeObserver.OnGlobalLayoutListener {
        val root = WeakReference(content)
        val environment = fallbackEnvironments.remove(content) ?: GlassEnvironment(content)
        private val containers = WeakHashMap<ViewGroup, Children>()
        private val recyclers = WeakHashMap<RecyclerView, RecyclerView.OnChildAttachStateChangeListener>()
        private var observing = false
        private var checking = false
        private val materialObserver = if (environment.thirdVersion) MaterialObserver(content) else null

        fun start() {
            if (observing) return
            root.get()?.viewTreeObserver?.addOnGlobalLayoutListener(this)
            materialObserver?.start()
            observing = true
        }

        override fun onGlobalLayout() = checkContainers()

        fun checkContainers() {
            if (checking) return
            checking = true
            try {
                // Flat child-identity checks catch later fragment attachment. Only a changed
                // branch is visited, instead of repeatedly walking all sliders during a drag.
                val changed = containers.entries.filter { (view, children) -> children.changed(view) }
                    .map { it.key }
                changed.forEach { walk(it) }
            } finally { checking = false }
        }

        fun walk(view: View) {
            // Theme picker thumbnails must continue showing each theme's own appearance.
            if (view.id == R.id.themes_list || view.id == R.id.theme_card) return
            when (view) {
                is MaterialCardView -> decorateCard(view)
                is BottomAppBar -> {
                    view.backgroundTint = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                    decorateSurface(view, Role.NAVIGATION)
                }
                is MaterialToolbar -> decorateSurface(view, Role.NAVIGATION)
                is NavigationBarView -> decorateSurface(view, Role.NAVIGATION)
                is AppBarLayout -> view.setBackgroundColor(Color.TRANSPARENT)
            }
            if (environment.secondVersion) GlassControlUi.decorate(view)
            if (view is RecyclerView && !recyclers.containsKey(view)) {
                val listener = object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) { walk(view) }
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
                view.addOnChildAttachStateChangeListener(listener)
                recyclers[view] = listener
            }
            if (view is ViewGroup) {
                containers[view] = Children(view)
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }

        fun dispose() {
            materialObserver?.dispose()
            root.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
            recyclers.forEach { (recycler, listener) -> recycler.removeOnChildAttachStateChangeListener(listener) }
            recyclers.clear()
            containers.clear()
            observing = false
        }
    }
}
