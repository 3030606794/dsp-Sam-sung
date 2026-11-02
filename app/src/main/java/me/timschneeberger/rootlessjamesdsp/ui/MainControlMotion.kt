package me.timschneeberger.rootlessjamesdsp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Navigation feedback; power feedback is owned by PowerMotionController. */
class MainControlMotion(private val settings: View) {
    private var rotation: ObjectAnimator?=null
    private var closed=false

    fun openSettings(open: () -> Unit) {
        if(closed || rotation?.isRunning==true)return
        if(!ValueAnimator.areAnimatorsEnabled()) {open();return}
        rotation=ObjectAnimator.ofFloat(settings,View.ROTATION,0f,360f).apply {
            duration=360
            interpolator=AccelerateDecelerateInterpolator()
            addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settings.rotation=0f
                }
            })
            start()
        }
        open()
    }

    fun close() {
        closed=true
        rotation?.cancel()
        settings.rotation=0f
    }
}
