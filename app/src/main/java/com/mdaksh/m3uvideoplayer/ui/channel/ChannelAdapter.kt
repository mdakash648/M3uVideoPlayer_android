package com.mdaksh.m3uvideoplayer.ui.channel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mdaksh.m3uvideoplayer.databinding.ItemChannelGridBinding
import com.mdaksh.m3uvideoplayer.databinding.ItemChannelListBinding
import com.mdaksh.m3uvideoplayer.databinding.ItemChannelPosterBinding
import com.mdaksh.m3uvideoplayer.databinding.ItemChannelTitleBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel

/**
 * Step 4.3 — renders the channel list in one of four [ChannelViewMode]s, each backed by its
 * own XML layout + ViewHolder. Switching mode is exposed via [setViewMode] so the future
 * DataStore-backed preference (4.4) and toolbar switcher button (4.5) have something to call
 * into; neither of those exists yet, so [ChannelListFragment] currently just uses the default.
 */
class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
    initialViewMode: ChannelViewMode = ChannelViewMode.DEFAULT
) : ListAdapter<Channel, ChannelAdapter.BaseChannelViewHolder>(DIFF_CALLBACK) {

    var viewMode: ChannelViewMode = initialViewMode
        private set

    /** Swaps the active layout for every visible row. No-op if [mode] is already active. */
    fun setViewMode(mode: ChannelViewMode) {
        if (mode == viewMode) return
        viewMode = mode
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = viewMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseChannelViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ChannelViewMode.values()[viewType]) {
            ChannelViewMode.LIST ->
                ListViewHolder(ItemChannelListBinding.inflate(inflater, parent, false))
            ChannelViewMode.GRID ->
                GridViewHolder(ItemChannelGridBinding.inflate(inflater, parent, false))
            ChannelViewMode.TITLE_ONLY ->
                TitleViewHolder(ItemChannelTitleBinding.inflate(inflater, parent, false))
            ChannelViewMode.POSTER ->
                PosterViewHolder(ItemChannelPosterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: BaseChannelViewHolder, position: Int) {
        holder.bind(getItem(position), onClick, onToggleFavorite)
    }

    /** Common contract every per-mode ViewHolder implements so [onBindViewHolder] stays generic. */
    abstract class BaseChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(
            channel: Channel,
            onClick: (Channel) -> Unit,
            onToggleFavorite: (Channel) -> Unit
        )
    }

    /** [ChannelViewMode.LIST] — small logo, name + group, favorite star. */
    inner class ListViewHolder(private val binding: ItemChannelListBinding) :
        BaseChannelViewHolder(binding.root) {

        override fun bind(
            channel: Channel,
            onClick: (Channel) -> Unit,
            onToggleFavorite: (Channel) -> Unit
        ) {
            binding.textChannelName.text = channel.name
            binding.textChannelGroup.text = channel.group
            binding.imageLogo.load(channel.logo) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
            binding.buttonFavorite.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.root.setOnClickListener { onClick(channel) }
            binding.buttonFavorite.setOnClickListener { onToggleFavorite(channel) }
        }
    }

    /** [ChannelViewMode.GRID] — thumbnail on top, name below, favorite as a corner overlay. */
    inner class GridViewHolder(private val binding: ItemChannelGridBinding) :
        BaseChannelViewHolder(binding.root) {

        override fun bind(
            channel: Channel,
            onClick: (Channel) -> Unit,
            onToggleFavorite: (Channel) -> Unit
        ) {
            binding.textChannelName.text = channel.name
            binding.imageLogo.load(channel.logo) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
            binding.buttonFavorite.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.root.setOnClickListener { onClick(channel) }
            binding.buttonFavorite.setOnClickListener { onToggleFavorite(channel) }
        }
    }

    /** [ChannelViewMode.TITLE_ONLY] — dense, text-only rows for scrolling huge playlists fast. */
    inner class TitleViewHolder(private val binding: ItemChannelTitleBinding) :
        BaseChannelViewHolder(binding.root) {

        override fun bind(
            channel: Channel,
            onClick: (Channel) -> Unit,
            onToggleFavorite: (Channel) -> Unit
        ) {
            binding.textChannelName.text = channel.name
            binding.buttonFavorite.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.root.setOnClickListener { onClick(channel) }
            binding.buttonFavorite.setOnClickListener { onToggleFavorite(channel) }
        }
    }

    /**
     * [ChannelViewMode.POSTER] — 16:9 thumbnail with the name overlaid at the bottom.
     * This is the pre-4.3 default look, now just one of four options.
     */
    inner class PosterViewHolder(private val binding: ItemChannelPosterBinding) :
        BaseChannelViewHolder(binding.root) {

        override fun bind(
            channel: Channel,
            onClick: (Channel) -> Unit,
            onToggleFavorite: (Channel) -> Unit
        ) {
            binding.textChannelName.text = channel.name
            binding.imageLogo.load(channel.logo) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
            binding.buttonFavorite.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.root.setOnClickListener { onClick(channel) }
            binding.buttonFavorite.setOnClickListener { onToggleFavorite(channel) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
        }
    }
}
