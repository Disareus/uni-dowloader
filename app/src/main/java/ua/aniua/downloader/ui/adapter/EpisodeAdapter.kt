package ua.aniua.downloader.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import ua.aniua.downloader.databinding.ItemEpisodeBinding
import ua.aniua.downloader.model.EpisodeItem

class EpisodeAdapter(
    private val listener: OnEpisodeActionListener
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_SELECTION = "selection"
    }

    interface OnEpisodeActionListener {
        fun onPlayPreview(episode: EpisodeItem)
        fun onSingleDownload(episode: EpisodeItem)
        fun onSelectionChanged(selectedCount: Int)
    }

    private var episodes: List<EpisodeItem> = emptyList()

    fun setEpisodes(items: List<EpisodeItem>?) {
        val newList = items?.toList() ?: emptyList()
        val oldList = episodes
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldList[oldItemPosition].id == newList[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = oldList[oldItemPosition]
                val n = newList[newItemPosition]
                return o.title == n.title &&
                    o.number == n.number &&
                    o.season == n.season &&
                    o.iframeUrl == n.iframeUrl &&
                    o.isSelected == n.isSelected
            }
        })
        episodes = newList
        diff.dispatchUpdatesTo(this)
        listener.onSelectionChanged(getSelectedEpisodes().size)
    }

    fun selectAll(select: Boolean) {
        for (item in episodes) {
            item.isSelected = select
        }
        if (episodes.isNotEmpty()) {
            notifyItemRangeChanged(0, episodes.size, PAYLOAD_SELECTION)
        }
        listener.onSelectionChanged(getSelectedEpisodes().size)
    }

    fun getSelectedEpisodes(): List<EpisodeItem> = episodes.filter { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val item = episodes[position]
            val b = holder.binding
            b.cbEpisodeSelect.setOnCheckedChangeListener(null)
            b.cbEpisodeSelect.isChecked = item.isSelected
            b.cbEpisodeSelect.setOnCheckedChangeListener { _, checked ->
                item.isSelected = checked
                listener.onSelectionChanged(getSelectedEpisodes().size)
            }
            return
        }
        onBindViewHolder(holder, position)
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
