package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R


/**
 * A [ListPreference] that presents the options in a drop down menu rather than a dialog.
 */
open class DropDownPreference(
    private val mContext: Context, attrs: AttributeSet?,
    defStyleAttr: Int, defStyleRes: Int,
) :
    ListPreference(mContext, attrs, defStyleAttr, defStyleRes) {

    private var mRoot: View? = null
    private var mPopup: PopupMenu? = null

    @JvmOverloads
    constructor(
        context: Context, attrs: AttributeSet? = null,
        defStyle: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    ) : this(context, attrs, defStyle, 0) {
        layoutResource = R.layout.preference_alt
    }

    var onMenuItemClick: ((index: Int) -> Unit)? = null
    var isStatic: Boolean = false

    init {
        with(context.obtainStyledAttributes(attrs, R.styleable.DropDownPreference)) {
            isStatic = getBoolean(R.styleable.DropDownPreference_isStatic, false)
        }
    }


    override fun onClick() {
        mPopup?.show()
    }

    override fun setEntries(entries: Array<CharSequence?>) {
        super.setEntries(entries)
        updateSelectedSummary()
        createMenu()
    }

    override fun setEntryValues(entryValues: Array<CharSequence?>) {
        super.setEntryValues(entryValues)
        updateSelectedSummary()
        createMenu()
    }

    private fun createMenu() {
        mPopup = mRoot?.let { PopupMenu(mContext, it) }

        entries?.forEachIndexed { index, s ->
            mPopup?.menu?.add(Menu.NONE, index, Menu.CATEGORY_CONTAINER, s)
        }

        mPopup?.setOnMenuItemClickListener { menuItem: MenuItem ->
            if (menuItem.itemId >= 0) {
                val value = entryValues?.getOrNull(menuItem.itemId)?.toString()
                onMenuItemClick?.invoke(menuItem.itemId)

                if (value != null && value != getValue() && callChangeListener(value)) {
                    setValue(value)
                    notifyChanged()
                }
            }
            true
        }
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        updateSelectedSummary()
    }

    override fun setValueIndex(index: Int) {
        entryValues?.getOrNull(index)?.let { value = it.toString() }
    }

    /** Reflect another parameter editor's committed value without creating another edit. */
    fun setValueSilently(value: String?) {
        val persistent = isPersistent
        isPersistent = false
        try {
            setValue(value)
        } finally {
            isPersistent = persistent
        }
    }

    private fun updateSelectedSummary() {
        if (isStatic) return
        val index = entryValues?.indexOf(value) ?: -1
        summary = entries?.getOrNull(index) ?: mContext.getString(R.string.value_not_set)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        mRoot = holder.itemView
        updateSelectedSummary()
        createMenu()

        super.onBindViewHolder(holder)
    }
}
