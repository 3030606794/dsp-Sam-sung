package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import android.view.View
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.peq.PeqFragment

class GraphicEqualizerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.fusion_peq_title)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // The PEQ-only child keeps title and summary on two aligned, bounded lines.
        // Other editors using this shell already hide peqToolbarSummaryRow entirely.
        binding.toolbar.isTitleCentered = false
        (binding.toolbar.parent as? View)?.minimumHeight =
            (56 * resources.displayMetrics.density + .5f).toInt()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, PeqFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
