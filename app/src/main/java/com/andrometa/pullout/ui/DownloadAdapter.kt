package com.andrometa.pullout.ui

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.andrometa.pullout.databinding.ItemDownloadBinding
import com.andrometa.pullout.download.DownloadRecord
import com.andrometa.pullout.download.DownloadStatus

class DownloadAdapter(
    private val onRetry: (DownloadRecord) -> Unit,
    private val onCancel: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.VH>(DIFF) {

    inner class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        with(holder.b) {
            tvFilename.text = r.filename.ifBlank { "downloading…" }

            // Reset all optional views
            btnOpen.visibility = View.GONE
            btnRetry.visibility = View.GONE
            btnCancel.visibility = View.GONE
            progressBar.visibility = View.GONE
            tvMuxPhase.visibility = View.GONE

            when (r.status) {
                DownloadStatus.QUEUED -> {
                    tvStatus.text = "queued"
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { onCancel(r) }
                }
                DownloadStatus.DOWNLOADING -> {
                    val pct = if (r.totalBytes > 0) (r.bytesDownloaded * 100 / r.totalBytes).toInt() else 0
                    val dlMb = r.bytesDownloaded / 1_048_576.0
                    tvStatus.text = if (r.totalBytes > 0)
                        "%.1f / %.1f MB".format(dlMb, r.totalBytes / 1_048_576.0)
                    else "%.1f MB".format(dlMb)
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = r.totalBytes <= 0
                    progressBar.progress = pct
                    if (r.isMuxed && r.muxPhase.isNotBlank()) {
                        tvMuxPhase.visibility = View.VISIBLE
                        tvMuxPhase.text = when (r.muxPhase) {
                            "DOWNLOADING_VIDEO" -> "downloading video…"
                            "DOWNLOADING_AUDIO" -> "downloading audio…"
                            "MERGING" -> "merging…"
                            else -> r.muxPhase.lowercase()
                        }
                    }
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { onCancel(r) }
                }
                DownloadStatus.COMPLETE -> {
                    val mb = if (r.totalBytes > 0) "%.1f MB".format(r.totalBytes / 1_048_576.0) else "saved"
                    tvStatus.text = mb
                    tvStatus.setTextColor(tvStatus.context.getColor(com.andrometa.pullout.R.color.neon_green))
                    btnOpen.visibility = View.VISIBLE
                    btnOpen.setOnClickListener { openFile(holder.b.root.context, r) }
                }
                DownloadStatus.FAILED, DownloadStatus.FAILED_NETWORK -> {
                    tvStatus.text = if (r.status == DownloadStatus.FAILED_NETWORK) "network error" else "failed"
                    tvStatus.setTextColor(tvStatus.context.getColor(com.andrometa.pullout.R.color.neon_red))
                    btnRetry.visibility = View.VISIBLE
                    btnRetry.setOnClickListener { onRetry(r) }
                }
            }
        }
    }

    private fun openFile(ctx: android.content.Context, r: DownloadRecord) {
        if (r.mediaStoreUriString.isBlank()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(r.mediaStoreUriString), r.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(a: DownloadRecord, b: DownloadRecord) = a.id == b.id
            override fun areContentsTheSame(a: DownloadRecord, b: DownloadRecord) = a == b
        }
    }
}
