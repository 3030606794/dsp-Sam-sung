package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityBlocklistBinding
import me.timschneeberger.rootlessjamesdsp.fragment.AllowlistFragment

/** The application selection is independent of global versus player-session processing. */
class AllowlistActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding=ActivityBlocklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {onBackPressedDispatcher.onBackPressed()}
        binding.fab.contentDescription=getString(R.string.app_processing_selected_add)
        if(savedInstanceState==null)supportFragmentManager.beginTransaction()
            .replace(R.id.blocklist_host,AllowlistFragment()).commitNow()
        binding.fab.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.blocklist_host) as? AllowlistFragment)?.showAppSelector()
        }
    }
}
