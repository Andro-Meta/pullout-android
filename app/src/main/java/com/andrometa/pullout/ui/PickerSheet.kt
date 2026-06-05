package com.andrometa.pullout.ui

import android.os.Bundle
import android.view.*
import androidx.recyclerview.widget.GridLayoutManager
import com.andrometa.pullout.api.PickerItem
import com.andrometa.pullout.databinding.SheetPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject

class PickerSheet : BottomSheetDialogFragment() {
    private var _b: SheetPickerBinding? = null
    private val b get() = _b!!
    private val adapter = PickerAdapter()
    var onItemsSelected: ((List<PickerItem>) -> Unit)? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        SheetPickerBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recyclerPicker.layoutManager = GridLayoutManager(requireContext(), 2)
        b.recyclerPicker.adapter = adapter

        val json = arguments?.getString(ARG_ITEMS) ?: "[]"
        val arr = JSONArray(json)
        val items = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PickerItem(o.getString("type"), o.getString("url"), o.optString("thumb").ifBlank { null })
        }
        adapter.setItems(items)

        b.btnPullAll.setOnClickListener {
            adapter.selectAll()
            onItemsSelected?.invoke(adapter.getAllItems())
            dismiss()
        }
        b.btnPullSelected.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) { onItemsSelected?.invoke(selected); dismiss() }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        const val TAG = "PickerSheet"
        private const val ARG_ITEMS = "items"
        fun newInstance(items: List<PickerItem>): PickerSheet {
            val json = JSONArray(items.map {
                JSONObject().apply { put("type", it.type); put("url", it.url); put("thumb", it.thumb ?: "") }
            }.map { it.toString() }).toString()
            return PickerSheet().apply { arguments = Bundle().apply { putString(ARG_ITEMS, json) } }
        }
    }
}
