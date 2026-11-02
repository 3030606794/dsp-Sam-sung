package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R

/** Displays the original image in the scrolling page at its complete aspect ratio. */
class AuthorBusinessCardPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    init {
        layoutResource = R.layout.preference_author_business_card
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.author_business_card_image) as? ImageView)?.apply {
            setImageResource(R.drawable.author_business_card)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
    }
}
