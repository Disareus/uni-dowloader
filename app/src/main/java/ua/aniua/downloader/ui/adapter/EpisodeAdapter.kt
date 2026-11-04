package ua.aniua.downloader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ua.aniua.downloader.R
import ua.aniua.downloader.model.EpisodeItem

class EpisodeAdapter(
    private val listener: OnEpisodeActionListener
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    interface OnEpisodeActionListener {
        fun onPlayPreview(episode: EpisodeItem)
        fun onSingleDownload(episode: EpisodeItem)
        fun onSelectionChanged(selectedCount: Int)
    }

    private var episodes: List<EpisodeItem> = emptyList()

    fun setEpisodes(items: List<EpisodeItem>?) {
        this.episodes = items ?: emptyList()
        notifyDataSetChanged()
        listener.onSelectionChanged(getSelectedEpisodes().size)
    }

    fun selectAll(select: Boolean) {
        for (item in episodes) {
            item.isSelected = select
        }
        notifyDataSetChanged()
        listener.onSelectionChanged(getSelectedEpisodes().size)
    }

    fun getSelectedEpisodes(): List<EpisodeItem> = episodes.filter { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = episodes[position]
        holder.tvTitle.text = item.title

        val numStr = item.number.ifEmpty { "${position + 1}" }
        val badgeStr = if (numStr.length == 1) "0$numStr" else numStr
        holder.tvNumBadge.text = badgeStr
        holder.tvSub.text = "Серія $numStr"

        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = item.isSelected

        holder.cbSelect.setOnCheckedChangeListener { _, checked ->
            item.isSelected = checked
            listener.onSelectionChanged(getSelectedEpisodes().size)
        }

        holder.btnPlay.setOnClickListener {
            listener.onPlayPreview(item)
        }

        holder.btnSingleDownload.setOnClickListener {
            listener.onSingleDownload(item)
        }

        holder.itemView.setOnClickListener {
            holder.cbSelect.toggle()
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNumBadge: TextView = itemView.findViewById(R.id.tv_episode_num_badge)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_episode_title)
        val tvSub: TextView = itemView.findViewById(R.id.tv_episode_sub)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cb_episode_select)
        val btnPlay: ImageButton = itemView.findViewById(R.id.btn_play_preview)
        val btnSingleDownload: ImageButton = itemView.findViewById(R.id.btn_single_download)
    }
}
