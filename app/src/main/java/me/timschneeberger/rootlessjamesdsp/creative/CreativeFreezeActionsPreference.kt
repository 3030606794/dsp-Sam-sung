package me.timschneeberger.rootlessjamesdsp.creative

import android.content.Context
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import me.timschneeberger.rootlessjamesdsp.R

/** One action row shared by the home card and the professional editor. */
class CreativeFreezeActionsPreference(context: Context) : Preference(context) {
    private var captureButton: MaterialButton? = null
    private var releaseButton: MaterialButton? = null
    init {
        key = "creative_freeze_actions"
        isPersistent = false; isSelectable = false; isIconSpaceReserved = false
        layoutResource = R.layout.preference_creative_freeze_actions
    }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        captureButton = (holder.findViewById(R.id.creative_capture) as MaterialButton).apply {
            setOnClickListener {
                if (!CreativeEffectsController.capture(context)) Toast.makeText(context,
                    if (CreativeEffectsController.supported(context) == false) CreativeEffectsController.DRIVER_REQUIRED
                    else "请先开启频谱冻结，再播放声音并点击捕获", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        releaseButton = (holder.findViewById(R.id.creative_release) as MaterialButton).apply {
            setOnClickListener { CreativeEffectsController.release(context); refresh() }
        }
        refresh()
    }
    fun refresh() {
        captureButton?.text = if (CreativeEffectsController.isHolding) "重新捕获" else "捕获并冻结"
        releaseButton?.isEnabled = CreativeEffectsController.isHolding
    }
    override fun onDetached() { captureButton = null; releaseButton = null; super.onDetached() }
}
