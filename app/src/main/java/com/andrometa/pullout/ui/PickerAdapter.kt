package com.andrometa.pullout.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.andrometa.pullout.api.PickerItem
import com.andrometa.pullout.databinding.ItemPickerBinding

class PickerAdapter : RecyclerView.Adapter<PickerAdapter.VH>() {
    private var items: List<PickerItem> = emptyList()
    private val selected = mutableSetOf<Int>()

    inner class VH(val b: ItemPickerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
        VH(ItemPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        if (!item.thumb.isNullOrBlank()) h.b.ivThumb.load(item.thumb)
        else h.b.ivThumb.setImageDrawable(null)
        h.b.tvType.text = item.type
        val isSel = selected.contains(pos)
        h.b.viewSelected.visibility = if (isSel) View.VISIBLE else View.GONE
        h.b.ivCheck.visibility = if (isSel) View.VISIBLE else View.GONE
        h.b.root.setOnClickListener {
            if (selected.contains(pos)) selected.remove(pos) else selected.add(pos)
            notifyItemChanged(pos)
        }
    }

    fun setItems(newItems: List<PickerItem>) {
        items = newItems; selected.clear(); notifyDataSetChanged()
    }
    fun selectAll() { selected.addAll(items.indices); notifyDataSetChanged() }
    fun getSelectedItems(): List<PickerItem> = selected.map { items[it] }
    fun getAllItems(): List<PickerItem> = items
}
