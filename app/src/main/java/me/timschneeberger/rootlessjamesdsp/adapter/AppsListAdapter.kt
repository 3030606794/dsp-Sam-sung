package me.timschneeberger.rootlessjamesdsp.adapter

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.AppInfo

class AppsListAdapter: RecyclerView.Adapter<AppsListAdapter.ViewHolder>(), Filterable {
    private data class Source(val revision: Long, val items: List<AppInfo>)
    private data class FilteredResult(val sourceRevision: Long, val items: List<AppInfo>)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var source = Source(0L, emptyList())
    @Volatile private var disposed = false

    var dataList: List<AppInfo>
        get() = source.items
        set(value) {
            val snapshot = value.toList()
            onMain {
                if (!disposed) {
                    source = Source(source.revision + 1L, snapshot)
                    replaceVisibleItems(snapshot)
                }
            }
        }

    private var filteredDataList: List<AppInfo> = emptyList()

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun replaceVisibleItems(items: List<AppInfo>) {
        // RecyclerView must see the new count and its notification in one main-thread turn.
        filteredDataList = items
        notifyDataSetChanged()
    }

    fun dispose() {
        onMain {
            disposed = true
            source = Source(source.revision + 1L, emptyList())
            replaceVisibleItems(emptyList())
            onItemClickListener = null
        }
    }

    inner class ViewHolder(rootView: ViewGroup): RecyclerView.ViewHolder(rootView), View.OnClickListener {

        init { rootView.setOnClickListener(this) }

        private val titleView = rootView.findViewById<TextView>(android.R.id.title)
        private val summaryView = rootView.findViewById<TextView>(android.R.id.summary)
        private val iconView = rootView.findViewById<ImageView>(android.R.id.icon)

        var data: AppInfo? = null
            set(value) {
                field = value
                value ?: return
                titleView.text = value.appName
                summaryView.text = value.packageName
                iconView.setImageDrawable(value.icon)
            }

        override fun onClick(v: View) {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            filteredDataList.getOrNull(position)?.let {
                onItemClickListener?.onItemClick(it)
            }
        }
    }

    fun interface OnItemClickListener {
        fun onItemClick(appInfo: AppInfo)
    }

    private var onItemClickListener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.onItemClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_list, parent, false) as ViewGroup
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.data = filteredDataList[position]
    }

    override fun getItemCount(): Int = filteredDataList.size

    private val appFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val charString = constraint?.toString() ?: ""
                val snapshot = source
                // Android Filter runs here on its worker thread. Never mutate the list used
                // by getItemCount()/onBindViewHolder() before publishResults notifies the UI.
                val filtered =
                    if (charString.isEmpty())
                        snapshot.items
                    else snapshot.items.filter {
                        it.appName.contains(charString,true) || it.packageName.contains(charString,true)
                    }
                return FilterResults().apply {
                    values = FilteredResult(snapshot.revision, filtered)
                    count = filtered.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                val result = results?.values as? FilteredResult ?: return
                onMain {
                    if (!disposed && result.sourceRevision == source.revision)
                        replaceVisibleItems(result.items)
                }
            }
    }

    override fun getFilter(): Filter = appFilter
}
