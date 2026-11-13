package ua.aniua.downloader.ui.adapter

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import ua.aniua.downloader.R
import ua.aniua.downloader.data.ShortcutRepository
import ua.aniua.downloader.databinding.ItemShortcutBinding
import ua.aniua.downloader.model.SiteShortcut
import java.util.Locale

class ShortcutAdapter(
    private val listener: OnShortcutClickListener
) : RecyclerView.Adapter<ShortcutAdapter.ViewHolder>() {

    interface OnShortcutClickListener {
        fun onAddShortcutClick()
        fun onShortcutClick(shortcut: SiteShortcut)
        fun onShortcutLongClick(shortcut: SiteShortcut)
    }

    private var shortcuts: List<SiteShortcut> = emptyList()

    fun setShortcuts(items: List<SiteShortcut>) {
        shortcuts = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = shortcuts.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val b = holder.binding
        val ctx = b.root.context

        if (position == 0) {
            // Пункт "Ярлик" з плюсиком (+)
            b.containerShortcutIcon.setBackgroundResource(R.drawable.bg_shortcut_add)
            b.ivShortcutAdd.visibility = View.VISIBLE
            b.ivShortcutIcon.visibility = View.GONE
            b.tvShortcutMonogram.visibility = View.GONE
            Glide.with(ctx).clear(b.ivShortcutIcon)

            b.tvShortcutName.text = ctx.getString(R.string.shortcut_add_label)
            b.tvShortcutName.setTextColor(ContextCompat.getColor(ctx, R.color.primary))

            b.root.setOnClickListener { listener.onAddShortcutClick() }
            b.root.setOnLongClickListener(null)
            return
        }

        val shortcut = shortcuts[position - 1]
        b.containerShortcutIcon.setBackgroundResource(R.drawable.bg_shortcut_icon)
        b.ivShortcutAdd.visibility = View.GONE
        b.tvShortcutMonogram.visibility = View.VISIBLE
        b.tvShortcutMonogram.text = shortcut.name.firstOrNull()?.uppercase(Locale.ROOT)?.toString() ?: "W"
        b.tvShortcutName.text = shortcut.name
        b.tvShortcutName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))

        val faviconUrl = ShortcutRepository.buildFaviconUrl(shortcut.url)
        if (!faviconUrl.isNullOrEmpty()) {
            b.ivShortcutIcon.visibility = View.VISIBLE
            Glide.with(ctx)
                .load(faviconUrl)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        b.ivShortcutIcon.visibility = View.GONE
                        b.tvShortcutMonogram.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        b.tvShortcutMonogram.visibility = View.GONE
                        return false
                    }
                })
                .into(b.ivShortcutIcon)
        } else {
            Glide.with(ctx).clear(b.ivShortcutIcon)
            b.ivShortcutIcon.visibility = View.GONE
        }

        b.root.setOnClickListener { listener.onShortcutClick(shortcut) }
        b.root.setOnLongClickListener {
            listener.onShortcutLongClick(shortcut)
            true
        }
    }

    class ViewHolder(val binding: ItemShortcutBinding) : RecyclerView.ViewHolder(binding.root)
}
