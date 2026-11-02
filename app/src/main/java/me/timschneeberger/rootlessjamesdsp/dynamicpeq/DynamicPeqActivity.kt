package me.timschneeberger.rootlessjamesdsp.dynamicpeq

import android.os.Bundle
import android.view.View
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.BaseActivity
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding

/** Independent editor; the static PEQ activity and its preferences are never involved. */
class DynamicPeqActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.peqToolbarSummaryRow.visibility = View.GONE
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.dynamic_peq_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.params, DynamicPeqFragment(), "dynamic-peq").commit()
    }
}
