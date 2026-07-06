package com.yumito.yumyhook.ui.config

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yumito.yumyhook.databinding.ItemHookFeatureBinding
import com.yumito.yumyhook.model.HookFeatureItem

/** 配置页功能开关列表。 */
class HookFeatureAdapter(
    private val onToggle: (String, Boolean) -> Boolean,
    private val allowToggle: (HookFeatureItem) -> Boolean = { it.implemented },
) : RecyclerView.Adapter<HookFeatureAdapter.VH>() {

    private var items: List<Pair<HookFeatureItem, Boolean>> = emptyList()

    fun submit(list: List<Pair<HookFeatureItem, Boolean>>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHookFeatureBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemHookFeatureBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Pair<HookFeatureItem, Boolean>) {
            val (meta, enabled) = item
            binding.textTitle.text = meta.title
            binding.textDesc.text = if (meta.implemented) {
                "${meta.section} · ${meta.description}"
            } else {
                "未实现 · ${meta.description}"
            }
            binding.switchFeature.setOnCheckedChangeListener(null)
            binding.switchFeature.isEnabled = allowToggle(meta)
            binding.switchFeature.isChecked = if (meta.implemented) enabled else false
            binding.switchFeature.setOnCheckedChangeListener { _, checked ->
                if (!allowToggle(meta)) {
                    binding.switchFeature.isChecked = false
                    return@setOnCheckedChangeListener
                }
                val accepted = onToggle(meta.key, checked)
                if (!accepted) {
                    binding.switchFeature.isChecked = !checked
                }
            }
        }
    }
}
