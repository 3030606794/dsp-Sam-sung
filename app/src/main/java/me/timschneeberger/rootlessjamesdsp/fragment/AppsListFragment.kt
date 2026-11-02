package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.timschneeberger.rootlessjamesdsp.adapter.AppsListAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentApplistSheetBinding
import me.timschneeberger.rootlessjamesdsp.model.AppInfo
import me.timschneeberger.rootlessjamesdsp.model.ItemViewModel
import me.timschneeberger.rootlessjamesdsp.utils.AppProcessingLists
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getInstalledApplicationsCompat


class AppsListFragment : BottomSheetDialogFragment() {

    private lateinit var binding: FragmentApplistSheetBinding
    private lateinit var adapter: AppsListAdapter
    private lateinit var watcher: TextWatcher
    private var loadJob:Job?=null

    // Using the activityViewModels() Kotlin property delegate from the
    // fragment-ktx artifact to retrieve the ViewModel in the activity scope
    private val viewModel: ItemViewModel<AppInfo> by viewModels({requireParentFragment()})

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentApplistSheetBinding.inflate(layoutInflater, container, false)
        adapter = AppsListAdapter()

        watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Editable belongs to the input view; the background filter needs a snapshot.
                adapter.filter.filter(s?.toString().orEmpty())
            }
        }
        binding.filter.addTextChangedListener(watcher)
        binding.filter.text?.clear()

        adapter.setOnItemClickListener {
            viewModel.selectItem(it)
            this.dismiss()
        }

        binding.recyclerview.adapter = adapter
        binding.recyclerview.layoutManager = LinearLayoutManagerWrapper(requireContext())

        loadJob=lifecycleScope.launch {
            binding.loader.isVisible = true
            binding.recyclerview.isVisible = false
            binding.filter.isEnabled = false

            val packageManager = requireContext().packageManager
            val appContext=requireContext().applicationContext
            val target=arguments?.getString(TARGET)?.let {name->
                AppProcessingLists.Target.values().firstOrNull {it.name==name}
            }
            val appsData = withContext(Dispatchers.IO) {
                val opposite=target?.let {AppProcessingLists.oppositeSelection(appContext,it)}
                packageManager.getInstalledApplicationsCompat(0)
                    .filterNot { (it.flags and ApplicationInfo.FLAG_INSTALLED) == 0 }
                    .filterNot {opposite?.contains(it.packageName,it.uid)==true}
                    .mapIndexed { idx, it ->
                        if (idx % 5 == 0) yield()
                        AppInfo(
                            it.loadLabel(packageManager).toString(),
                            it.packageName,
                            it.loadIcon(packageManager),
                            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0,
                            it.uid
                        )
                    }
                .sortedWith(compareBy({ !it.isSystem }, { it.appName }))
            }

            adapter.dataList = appsData
            adapter.filter.filter(binding.filter.text?.toString().orEmpty())

            binding.loader.isVisible = false
            binding.recyclerview.isVisible = true
            binding.filter.isEnabled = true
        }
        return binding.root
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        binding.filter.removeTextChangedListener(watcher)
        binding.recyclerview.adapter = null
        adapter.dispose()
        super.onDestroyView()
    }

    private class LinearLayoutManagerWrapper(context: Context?) : LinearLayoutManager(context) {
        override fun supportsPredictiveItemAnimations() = false
    }

    companion object {
        private const val TARGET="app_processing_list_target"
        fun newInstance(target:AppProcessingLists.Target?=null): AppsListFragment {
            return AppsListFragment().apply {if(target!=null)arguments=Bundle().apply {putString(TARGET,target.name)}}
        }
    }
}
