package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityGraphicEqBinding
import me.timschneeberger.rootlessjamesdsp.fragment.GraphicEqualizerFragment
import me.timschneeberger.rootlessjamesdsp.peq.PeqController
import me.timschneeberger.rootlessjamesdsp.peq.PeqFragment

class GraphicEqualizerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGraphicEqBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val legacy = intent.getBooleanExtra("legacy_curve", false)
        supportActionBar?.setTitle(if (legacy) R.string.fusion_peq_legacy else R.string.fusion_peq_title)
        if (legacy) {
            binding.toolbar.menu.add(R.string.fusion_peq_use_legacy).apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    PeqController.useLegacy(this@GraphicEqualizerActivity)
                    finish()
                    true
                }
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, if (legacy) GraphicEqualizerFragment.newInstance() else PeqFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
