package ua.aniua.downloader.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ua.aniua.downloader.databinding.ItemEpisodeBinding
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
        episodes = items ?: emptyList()
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
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = episodes[position]
        val b = holder.binding

        b.tvEpisodeTitle.text = item.title
        val numStr = item.number.ifEmpty { "${position + 1}" }
        b.tvEpisodeNumBadge.text = if (numStr.length == 1) "0$numStr" else numStr
        b.tvEpisodeSub.text = "Сезон ${item.season.ifEmpty { "1" }} • Серія $numStr"

        b.cbEpisodeSelect.setOnCheckedChangeListener(null)
        b.cbEpisodeSelect.isChecked = item.isSelected

        b.cbEpisodeSelect.setOnCheckedChangeListener { _, checked ->
            item.isSelected = checked
            listener.onSelectionChanged(getSelectedEpisodes().size)
        }

        b.btnPlayPreview.setOnClickListener {
            listener.onPlayPreview(item)
        }

        b.btnSingleDownload.setOnClickListener {
            listener.onSingleDownload(item)
        }

        b.root.setOnClickListener {
            b.cbEpisodeSelect.toggle()
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
