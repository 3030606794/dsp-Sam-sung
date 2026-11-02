package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppName

/** Compact About-only header; the shared application-icon preference stays unchanged. */
class AboutHeaderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_about_header
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.about_header_icon) as? ImageView)?.setImageDrawable(
            context.applicationInfo.loadIcon(context.packageManager),
        )
        (holder.findViewById(R.id.about_header_title) as? TextView)?.text = context.getAppName()
        (holder.findViewById(R.id.about_header_version) as? TextView)?.text = BuildConfig.VERSION_NAME
    }
}
