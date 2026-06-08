package com.andrometa.pullout.ui

import android.os.Bundle
import android.view.*
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.andrometa.pullout.R
import com.andrometa.pullout.databinding.SheetDownloadQueueBinding
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout

class DownloadQueueSheet : BottomSheetDialogFragment() {
    private var _b: SheetDownloadQueueBinding? = null
    private val b get() = _b!!
    private val vm: DownloadQueueViewModel by activityViewModels()
    private lateinit var adapter: DownloadAdapter
    var onRetry: ((DownloadRecord) -> Unit)? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        SheetDownloadQueueBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DownloadAdapter(
            onRetry = { r -> onRetry?.invoke(r) },
            onCancel = { r -> vm.cancelDownload(r) }
        )
        b.recyclerQueue.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerQueue.adapter = adapter

        b.tabLayout.addTab(b.tabLayout.newTab().setText(getString(R.string.queue_active)))
        b.tabLayout.addTab(b.tabLayout.newTab().setText(getString(R.string.queue_history)))

        b.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh(tab.position == 0)
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        vm.allDownloads.observe(viewLifecycleOwner) { refresh(b.tabLayout.selectedTabPosition == 0) }
        vm.activeDownloads.observe(viewLifecycleOwner) { refresh(b.tabLayout.selectedTabPosition == 0) }
    }

    private fun refresh(activeTab: Boolean) {
        val list = if (activeTab) vm.activeDownloads.value ?: emptyList()
        else vm.allDownloads.value?.filter {
            it.status == DownloadStatus.COMPLETE || it.status == DownloadStatus.FAILED
                    || it.status == DownloadStatus.FAILED_NETWORK
        } ?: emptyList()
        adapter.submitList(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.recyclerQueue.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    companion object { const val TAG = "DownloadQueueSheet"; fun newInstance() = DownloadQueueSheet() }
}
