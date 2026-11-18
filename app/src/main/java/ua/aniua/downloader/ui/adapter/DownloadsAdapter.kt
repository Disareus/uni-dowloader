package ua.aniua.downloader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ua.aniua.downloader.R
import ua.aniua.downloader.databinding.ItemDownloadBinding
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import java.util.Locale

class DownloadsAdapter(
    private val listener: OnDownloadActionListener
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_PROGRESS = "progress_only"
    }

    interface OnDownloadActionListener {
        fun onCancel(task: DownloadTask)
        fun onRetry(task: DownloadTask)
        fun onPlay(task: DownloadTask)
        fun onRemove(task: DownloadTask)
    }

    private val tasks = mutableListOf<DownloadTask>()

    fun setTasks(items: List<DownloadTask>?) {
        val newList = items?.toList() ?: emptyList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = tasks.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                tasks[oldItemPosition].id == newList[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = tasks[oldItemPosition]
                val n = newList[newItemPosition]
                return o.state == n.state &&
                    o.progress == n.progress &&
                    o.speed == n.speed &&
                    o.eta == n.eta &&
                    o.downloadedBytes == n.downloadedBytes &&
                    o.totalBytes == n.totalBytes &&
                    o.errorMessage == n.errorMessage &&
                    o.savedFilePath == n.savedFilePath
            }
        })
        tasks.clear()
        tasks.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    fun updateTask(task: DownloadTask) {
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx != -1) {
            val prev = tasks[idx]
            tasks[idx] = task
            if (prev.state == task.state && task.state == DownloadState.DOWNLOADING) {
                notifyItemChanged(idx, PAYLOAD_PROGRESS)
            } else {
                notifyItemChanged(idx)
            }
        } else {
            tasks.add(0, task)
            notifyItemInserted(0)
        }
    }

    fun removeTaskById(taskId: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1) {
            tasks.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            val task = tasks[position]
            val b = holder.binding
            bindProgressFields(b, task)
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindProgressFields(b: ItemDownloadBinding, task: DownloadTask) {
        b.tvDlSubtitle.text = buildString {
            if (task.studioName.isNotEmpty() && task.studioName != "Оригінал") {
                append(task.studioName)
                append(" • ")
            }
            append(task.format.uppercase(Locale.US))
            if (task.downloadedBytes > 0) {
                append(" • ")
                append(formatBytes(task.downloadedBytes))
                if (task.totalBytes > 0) {
                    append(" / ")
                    append(formatBytes(task.totalBytes))
                }
            }
        }
        b.tvDlPercent.text = String.format(Locale.US, "%.1f%%", task.progress)
        b.progressDl.progress = task.progress.toInt()
        if (task.state == DownloadState.DOWNLOADING) {
            val speedEta = listOf(task.speed, task.eta.takeIf { it.isNotEmpty() }?.let { "ETA: $it" })
                .filterNotNull()
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
            b.tvDlSpeedEta.text = speedEta.ifEmpty { "Завантаження…" }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = tasks[position]
        val b = holder.binding
        val ctx = b.root.context

        b.tvDlTitle.text = if (task.isSingle) {
            "${task.mediaTitle} [${task.quality}]"
        } else {
            "${task.mediaTitle} - С${task.episodeNumber} [${task.quality}]"
        }
        b.tvDlSubtitle.text = buildString {
            if (task.studioName.isNotEmpty() && task.studioName != "Оригінал") {
                append(task.studioName)
                append(" • ")
            }
            append(task.format.uppercase(Locale.US))
            if (task.downloadedBytes > 0) {
                append(" • ")
                append(formatBytes(task.downloadedBytes))
                if (task.totalBytes > 0) {
                    append(" / ")
                    append(formatBytes(task.totalBytes))
                }
            }
        }

        b.tvDlPercent.text = String.format(Locale.US, "%.1f%%", task.progress)
        b.progressDl.progress = task.progress.toInt()

        val cyan = ContextCompat.getColor(ctx, R.color.accent_cyan)
        val green = ContextCompat.getColor(ctx, R.color.accent_green)
        val danger = ContextCompat.getColor(ctx, R.color.danger)
        val muted = ContextCompat.getColor(ctx, R.color.text_muted)

        when (task.state) {
            DownloadState.DOWNLOADING -> {
                b.progressDl.setIndicatorColor(cyan)
                b.tvDlPercent.setTextColor(cyan)
                val speedEta = listOf(task.speed, task.eta.takeIf { it.isNotEmpty() }?.let { "ETA: $it" })
                    .filterNotNull()
                    .filter { it.isNotEmpty() }
                    .joinToString(" • ")
                b.tvDlSpeedEta.text = speedEta.ifEmpty { "Завантаження…" }
                b.btnDlPlay.visibility = View.GONE
                b.btnDlRetry.visibility = View.GONE
                b.btnDlCancel.visibility = View.VISIBLE
                b.btnDlCancel.setOnClickListener { listener.onCancel(task) }
            }
            DownloadState.COMPLETED -> {
                b.progressDl.setIndicatorColor(green)
                b.tvDlPercent.setTextColor(green)
                b.tvDlSpeedEta.text = "Завантажено ✓"
                b.btnDlPlay.visibility = if (!task.savedFilePath.isNullOrEmpty()) View.VISIBLE else View.GONE
                b.btnDlRetry.visibility = View.GONE
                b.btnDlCancel.visibility = View.VISIBLE
                b.btnDlPlay.setOnClickListener { listener.onPlay(task) }
                b.btnDlCancel.setOnClickListener { listener.onRemove(task) }
            }
            DownloadState.CANCELLED -> {
                b.progressDl.setIndicatorColor(muted)
                b.tvDlPercent.setTextColor(muted)
                b.tvDlSpeedEta.text = "Скасовано"
                b.btnDlPlay.visibility = View.GONE
                b.btnDlRetry.visibility = View.VISIBLE
                b.btnDlCancel.visibility = View.VISIBLE
                b.btnDlRetry.setOnClickListener { listener.onRetry(task) }
                b.btnDlCancel.setOnClickListener { listener.onRemove(task) }
            }
            DownloadState.ERROR -> {
                b.progressDl.setIndicatorColor(danger)
                b.tvDlPercent.setTextColor(danger)
                b.tvDlSpeedEta.text = "Помилка: ${task.errorMessage ?: "невідома"}"
                b.btnDlPlay.visibility = View.GONE
                b.btnDlRetry.visibility = View.VISIBLE
                b.btnDlCancel.visibility = View.VISIBLE
                b.btnDlRetry.setOnClickListener { listener.onRetry(task) }
                b.btnDlCancel.setOnClickListener { listener.onRemove(task) }
            }
            else -> {
                b.progressDl.setIndicatorColor(cyan)
                b.tvDlPercent.setTextColor(cyan)
                b.tvDlSpeedEta.text = "У черзі…"
                b.btnDlPlay.visibility = View.GONE
                b.btnDlRetry.visibility = View.GONE
                b.btnDlCancel.visibility = View.VISIBLE
                b.btnDlCancel.setOnClickListener { listener.onCancel(task) }
            }
        }
    }

    override fun getItemCount(): Int = tasks.size

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    class ViewHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
}
