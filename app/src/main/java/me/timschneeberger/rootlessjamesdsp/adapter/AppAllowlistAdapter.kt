package me.timschneeberger.rootlessjamesdsp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.AppInfo

class AppAllowlistAdapter(private val onRemove:(AppInfo)->Unit) :
    ListAdapter<AppInfo,AppAllowlistAdapter.Holder>(object: DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem:AppInfo,newItem:AppInfo)=oldItem.packageName==newItem.packageName
        override fun areContentsTheSame(oldItem:AppInfo,newItem:AppInfo)=oldItem==newItem
    }) {
    inner class Holder(view:View): RecyclerView.ViewHolder(view) {
        private val title=view.findViewById<TextView>(android.R.id.title)
        private val summary=view.findViewById<TextView>(android.R.id.summary)
        private val icon=view.findViewById<ImageView>(android.R.id.icon)
        private var app:AppInfo?=null
        init {view.setOnClickListener {app?.let(onRemove)}}
        fun bind(next:AppInfo) {
            app=next;title.text=next.appName;summary.text=next.packageName
            if(next.icon!=null)icon.setImageDrawable(next.icon)
            else icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
    }
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_app_list,parent,false))
    override fun onBindViewHolder(holder:Holder,position:Int)=holder.bind(getItem(position))
}
