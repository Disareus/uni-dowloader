package ua.aniua.downloader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import ua.aniua.downloader.R
import ua.aniua.downloader.model.DownloadState
import ua.aniua.downloader.model.DownloadTask
import java.util.Locale

class DownloadsAdapter(
    private val cancelListener: (DownloadTask) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    private val tasks = mutableListOf<DownloadTask>()

    fun setTasks(items: List<DownloadTask>?) {
        tasks.clear()
        if (items != null) tasks.addAll(items)
        notifyDataSetChanged()
    }

    fun updateTask(task: DownloadTask) {
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx != -1) {
            tasks[idx] = task
            notifyItemChanged(idx)
        } else {
            tasks.add(0, task)
            notifyItemInserted(0)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val task = tasks[position]
        holder.tvTitle.text = "${task.mediaTitle} - С${task.episodeNumber} [${task.quality}]"
        holder.tvPercent.text = String.format(Locale.US, "%.1f%%", task.progress)
        holder.progress.progress = task.progress.toInt()

        when (task.state) {
            DownloadState.DOWNLOADING -> {
                holder.tvSpeedEta.text = "${task.speed} • ETA: ${task.eta}"
            }
            DownloadState.COMPLETED -> {
                holder.tvSpeedEta.text = "Завантажено ✓"
            }
            DownloadState.CANCELLED -> {
                holder.tvSpeedEta.text = "Скасовано ✕"
            }
            DownloadState.ERROR -> {
                holder.tvSpeedEta.text = "Помилка: ${task.errorMessage}"
            }
            else -> {
                holder.tvSpeedEta.text = "У черзі..."
            }
        }

        holder.btnCancel.setOnClickListener {
            cancelListener(task)
        }
    }

    override fun getItemCount(): Int = tasks.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_dl_title)
        val tvPercent: TextView = itemView.findViewById(R.id.tv_dl_percent)
        val progress: LinearProgressIndicator = itemView.findViewById(R.id.progress_dl)
        val tvSpeedEta: TextView = itemView.findViewById(R.id.tv_dl_speed_eta)
        val btnCancel: ImageButton = itemView.findViewById(R.id.btn_dl_cancel)
    }
}
