package com.yumito.yumyhook.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yumito.yumyhook.R
import com.yumito.yumyhook.model.ScopedAppEntry

class ScopeAppAdapter : RecyclerView.Adapter<ScopeAppAdapter.VH>() {

    private val items = mutableListOf<ScopedAppEntry>()

    fun submit(entries: List<ScopedAppEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scope_app, parent, false)
        return VH(view as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(entry: ScopedAppEntry) {
            textView.text = "• ${entry.label} · ${entry.packageName}"
        }
    }
}
