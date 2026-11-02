package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.AppAllowlistAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentAllowlistBinding
import me.timschneeberger.rootlessjamesdsp.model.AppInfo
import me.timschneeberger.rootlessjamesdsp.model.ItemViewModel
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.AppProcessingLists
import me.timschneeberger.rootlessjamesdsp.utils.ProcessingAppPolicy
import me.timschneeberger.rootlessjamesdsp.session.root.RootProcessingPolicy
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getApplicationInfoCompat
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast

class AllowlistFragment : Fragment() {
    private var binding:FragmentAllowlistBinding?=null
    private lateinit var prefs:SharedPreferences
    private lateinit var adapter:AppAllowlistAdapter
    private val selection:ItemViewModel<AppInfo> by viewModels()
    private var loadJob:Job?=null
    private val cachedApps=hashMapOf<String,AppInfo>()
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key==ENABLED || key==PACKAGES) {
            renderPolicy()
            if(key==PACKAGES)loadSelectedApps()
        }
        if (key == getString(R.string.key_audioformat_processing) ||
            key == getString(R.string.key_audioformat_enhanced_processing) ||
            key == getString(R.string.key_audioformat_usb_rate_follow)) renderPolicy()
    }

    override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View {
        val next=FragmentAllowlistBinding.inflate(inflater,container,false)
        binding=next
        return next.root
    }

    override fun onViewCreated(view:View,state:Bundle?) {
        super.onViewCreated(view,state)
        prefs=requireContext().getSharedPreferences(Constants.PREF_APP,Context.MODE_PRIVATE)
        adapter=AppAllowlistAdapter {app->
            GlassAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_processing_selected_remove_title)
                .setMessage(getString(R.string.app_processing_selected_remove_message,app.appName))
                .setPositiveButton(R.string.app_processing_selected_remove) {_,_->
                    editList {AppProcessingLists.removeSpecified(it,app.packageName)}
                }.setNegativeButton(android.R.string.cancel,null).show()
        }
        binding?.apply {
            recyclerview.layoutManager=LinearLayoutManager(requireContext())
            recyclerview.adapter=adapter
            onlySelected.setOnCheckedChangeListener {_,checked->
                if(prefs.getBoolean(ENABLED,false)!=checked) {
                    prefs.edit().putBoolean(ENABLED,checked).apply()
                    if(checked && packages().isEmpty())showAppSelector()
                }
            }
        }
        selection.selectedItem.observe(viewLifecycleOwner) {app->
            app ?: return@observe
            selection.clearSelection()
            if(app.packageName !in packages()) {
                cachedApps[app.packageName]=app
                editList {AppProcessingLists.select(it,app,AppProcessingLists.Target.SPECIFIED)}
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        renderPolicy()
        editList {AppProcessingLists.normalize(it);loadSelectedApps()}
    }

    private fun packages()=prefs.getStringSet(PACKAGES,emptySet()).orEmpty().toSet()
    private fun editList(action:suspend(Context)->Unit) {
        val appContext=requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {action(appContext)}
            catch(error:CancellationException) {throw error}
            catch(error:Exception) {context?.toast(getString(R.string.app_processing_list_save_failed))}
        }
    }
    private fun renderPolicy() {
        val ui=binding ?: return
        val enabled=prefs.getBoolean(ENABLED,false)
        val count=packages().size
        if(ui.onlySelected.isChecked!=enabled)ui.onlySelected.isChecked=enabled
        val message=when {
            !RootProcessingPolicy.playerSessions(requireContext())->getString(R.string.app_processing_selected_global_saved,count)
            count==0 && enabled->getString(R.string.app_processing_selected_empty_enabled)
            count==0->getString(R.string.app_processing_selected_empty_disabled)
            enabled->getString(R.string.app_processing_selected_status_enabled,count)
            else->getString(R.string.app_processing_selected_status_disabled,count)
        }
        if(ui.policyStatus.text.toString()!=message)ui.policyStatus.text=message
    }
    private fun loadSelectedApps() {
        val ui=binding ?: return
        loadJob?.cancel()
        val selected=packages()
        val appContext=requireContext().applicationContext
        val cached=cachedApps.toMap()
        ui.loader.isVisible=selected.isNotEmpty() && adapter.itemCount==0
        loadJob=viewLifecycleOwner.lifecycleScope.launch {
            val items=withContext(Dispatchers.IO) {
                val pm=appContext.packageManager
                selected.map {pkg->
                    ensureActive()
                    cached[pkg] ?: runCatching {
                        val info=pm.getApplicationInfoCompat(pkg)
                        AppInfo(info.loadLabel(pm).toString(),pkg,info.loadIcon(pm),
                            (info.flags and ApplicationInfo.FLAG_SYSTEM)!=0,info.uid)
                    }.getOrElse {AppInfo(appContext.getString(R.string.app_processing_selected_missing,pkg),pkg,null,false,-1)}
                }.sortedBy {it.appName.lowercase()}
            }
            if(binding !== ui)return@launch
            items.forEach {cachedApps[it.packageName]=it}
            adapter.submitList(items)
            ui.loader.isVisible=false
        }
    }
    fun showAppSelector() {
        if(!isAdded || childFragmentManager.isStateSaved)return
        if(childFragmentManager.findFragmentByTag(SELECTOR)==null)
            AppsListFragment.newInstance(AppProcessingLists.Target.SPECIFIED).showNow(childFragmentManager,SELECTOR)
    }
    override fun onDestroyView() {
        loadJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        binding?.recyclerview?.adapter=null
        binding=null
        super.onDestroyView()
    }
    companion object {
        private const val ENABLED=ProcessingAppPolicy.ENABLED
        private const val PACKAGES=ProcessingAppPolicy.PACKAGES
        private const val SELECTOR="app_processing_allowlist_selector"
    }
}
