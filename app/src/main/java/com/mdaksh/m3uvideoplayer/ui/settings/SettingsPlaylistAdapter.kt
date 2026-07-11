package com.mdaksh.m3uvideoplayer.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mdaksh.m3uvideoplayer.databinding.ItemSettingsPlaylistBinding
import com.mdaksh.m3uvideoplayer.domain.model.Playlist

/** Read-only-looking list row for Settings ➔ Playlists; tapping a row opens the Edit screen. */
class SettingsPlaylistAdapter(
    private val onOpen: (Playlist) -> Unit
) : ListAdapter<Playlist, SettingsPlaylistAdapter.SettingsPlaylistViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingsPlaylistViewHolder {
        val binding = ItemSettingsPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SettingsPlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SettingsPlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SettingsPlaylistViewHolder(private val binding: ItemSettingsPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: Playlist) {
            binding.textPlaylistName.text = playlist.name
            binding.textPlaylistUrl.text = playlist.url
            binding.root.setOnClickListener { onOpen(playlist) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) =
                oldItem == newItem
        }
    }
}
