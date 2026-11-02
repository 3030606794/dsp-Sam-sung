package me.timschneeberger.rootlessjamesdsp.fragment

import android.graphics.drawable.Drawable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.AppBlocklistAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentBlocklistBinding
import me.timschneeberger.rootlessjamesdsp.model.AppInfo
import me.timschneeberger.rootlessjamesdsp.model.ItemViewModel
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistViewModel
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistViewModelFactory
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.session.dump.DumpManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppIcon
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.asHtml
import me.timschneeberger.rootlessjamesdsp.utils.AppProcessingLists
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class BlocklistFragment : Fragment() {

    private lateinit var binding: FragmentBlocklistBinding
    private lateinit var appsListFragment: AppsListFragment
    private lateinit var adapter: AppBlocklistAdapter

    private val dumpManager: DumpManager by inject()
    private val preferences: Preferences.App by inject()

    private val viewModel: AppBlocklistViewModel by viewModels {
        AppBlocklistViewModelFactory((requireActivity().application as MainApplication).blockedAppRepository)
    }
    private val appListViewModel: ItemViewModel<AppInfo> by viewModels()

    private val iconCache: HashMap<Int, Drawable> = hashMapOf()

    private lateinit var sessionRecordingPolicyManager: SessionRecordingPolicyManager
    private var appLoadJob:Job?=null
    private var policyPollingJob:Job?=null
    private var systemExclusionJob:Job?=null
    private var restrictedApps = arrayOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBlocklistBinding.inflate(layoutInflater, container, false)

        sessionRecordingPolicyManager = SessionRecordingPolicyManager(requireContext())

        appsListFragment = AppsListFragment.newInstance(AppProcessingLists.Target.EXCLUDED)
        appListViewModel.selectedItem.observe(viewLifecycleOwner) { selected ->
            selected ?: return@observe
            appListViewModel.clearSelection()
            // TODO merge BlockedApp and AppInfo?
            editList {AppProcessingLists.select(it,selected,AppProcessingLists.Target.EXCLUDED)}
        }

        adapter = AppBlocklistAdapter()
        adapter.setOnItemClickListener {
            requireContext().showYesNoAlert(R.string.blocklist_delete_title, R.string.blocklist_delete) { confirm ->
                if(confirm)
                    editList {context->AppProcessingLists.removeExcluded(context,it)}
            }
        }

        binding.recyclerview.adapter = adapter
        binding.recyclerview.layoutManager = LinearLayoutManager(requireContext())

        binding.notice.isVisible = false
        binding.excludeSystemApps.setOnClickListener { excludeSystemApps() }
        binding.notice.setOnClickListener {
            requireContext().showAlert(
                getString(R.string.blocklist_unsupported_apps),
                getString(R.string.blocklist_unsupported_apps_message, restrictedApps.joinToString("<br/>")).asHtml()
            )
        }
        updateUnsupportedApps()

        editList {
            AppProcessingLists.normalize(it)
            observeBlockedApps()
        }

        return binding.root
    }

    private fun observeBlockedApps() {
        viewModel.blockedApps.observe(viewLifecycleOwner) { apps ->
            val isEmpty = apps == null || apps.isEmpty()
            appLoadJob?.cancel()
            val appContext=requireContext().applicationContext
            val cache=iconCache.toMap()
            val currentBinding=binding
            binding.loader.isVisible=!isEmpty && adapter.itemCount==0
            appLoadJob=viewLifecycleOwner.lifecycleScope.launch {
                // PackageManager label/icon calls can involve disk/Binder; never run them
                // in the LiveData callback while opening or scrolling this page.
                val items=withContext(Dispatchers.IO) {
                    apps.orEmpty().map {app->
                        ensureActive()
                        BlockedApp(app.uid,app.packageName,app.appName).apply {
                            appIcon=cache[uid] ?: packageName?.let {pkg->runCatching {appContext.getAppIcon(pkg)}.getOrNull()}
                        }
                    }.sortedBy {it.appName}
                }
                if(binding !== currentBinding)return@launch
                items.forEach {app->app.appIcon?.let {iconCache[app.uid]=it}}
                adapter.submitList(items)
                binding.loader.isVisible=false
            }
            binding.recyclerview.isVisible = !isEmpty
            binding.emptyview.isVisible = isEmpty
        }
    }

    private fun editList(action:suspend(android.content.Context)->Unit) {
        val appContext=requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {action(appContext)}
            catch(error:kotlinx.coroutines.CancellationException) {throw error}
            catch(error:Exception) {context?.toast(getString(R.string.app_processing_list_save_failed))}
        }
    }

    private fun excludeSystemApps() {
        if (systemExclusionJob?.isActive == true) return
        val appContext = requireContext().applicationContext
        val ui = binding
        ui.excludeSystemApps.isEnabled = false
        ui.excludeSystemApps.setText(R.string.app_processing_exclude_system_busy)
        systemExclusionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = AppProcessingLists.excludeSystemApps(appContext)
                if (binding === ui && view != null) {
                    ui.excludeSystemStatus.text = getString(R.string.app_processing_exclude_system_result,
                        result.addedCount, result.skippedSpecifiedCount)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (binding === ui && view != null)
                    context?.toast(getString(R.string.app_processing_list_save_failed))
            } finally {
                if (binding === ui && view != null) {
                    ui.excludeSystemApps.isEnabled = true
                    ui.excludeSystemApps.setText(R.string.app_processing_exclude_system)
                }
            }
        }
    }

    override fun onDestroyView() {
        appLoadJob?.cancel()
        systemExclusionJob?.cancel()
        policyPollingJob?.cancel()
        binding.recyclerview.adapter=null
        sessionRecordingPolicyManager.destroy()
        super.onDestroyView()
    }

    override fun onResume() {
        updateUnsupportedApps()
        super.onResume()
    }

    fun showAppSelector() {
        if(!appsListFragment.isAdded)
            appsListFragment.show(childFragmentManager, AppsListFragment::class.java.name)
    }

    private fun updateUnsupportedApps() {
        if(isRootless()) {
            policyPollingJob?.cancel()
            policyPollingJob=viewLifecycleOwner.lifecycleScope.launch {
                val isAllowed = preferences.get<Boolean>(R.string.key_session_exclude_restricted)

                restrictedApps = if (!isAllowed) {
                    arrayOf()
                } else {
                    dumpManager.dumpCaptureAllowlistLog()?.let {
                        sessionRecordingPolicyManager.update(it)
                    }

                    sessionRecordingPolicyManager
                        .getRestrictedUids()
                        .map { """${requireContext().getAppNameFromUidSafe(it)} (UID $it)""" }
                        .toTypedArray()
                }

                this@BlocklistFragment.binding.notice.isVisible = restrictedApps.isNotEmpty()
                context?.let {
                    this@BlocklistFragment.binding.noticeLabel.text = it.resources.getQuantityString(
                        R.plurals.unsupported_apps,
                        restrictedApps.size,
                        restrictedApps.size
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): BlocklistFragment {
            return BlocklistFragment()
        }
    }
}
