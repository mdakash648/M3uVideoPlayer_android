package com.mdaksh.m3uvideoplayer.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mdaksh.m3uvideoplayer.databinding.ItemGroupListBinding
import com.mdaksh.m3uvideoplayer.databinding.ItemSearchFileBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel

/**
 * A single row in the inline global-search results list. Folders always rank above files
 * (see [GroupListViewModel.searchResults]), so the two are distinct item types here.
 */
sealed interface SearchResult {
    data class Folder(val group: GroupItem) : SearchResult
    data class File(val channel: Channel) : SearchResult
}

/** Two-view-type adapter: folder rows reuse [ItemGroupListBinding]; files get [ItemSearchFileBinding]. */
class SearchResultAdapter(
    private val onFolderClick: (GroupItem) -> Unit,
    private val onFileClick: (Channel) -> Unit
) : ListAdapter<SearchResult, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchResult.Folder -> TYPE_FOLDER
        is SearchResult.File -> TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(ItemGroupListBinding.inflate(inflater, parent, false))
            else -> FileViewHolder(ItemSearchFileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchResult.Folder -> (holder as FolderViewHolder).bind(item.group)
            is SearchResult.File -> (holder as FileViewHolder).bind(item.channel)
        }
    }

    inner class FolderViewHolder(private val binding: ItemGroupListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(group: GroupItem) {
            binding.textGroupName.text = group.name
            binding.textGroupCount.text =
                if (group.channelCount == 1) "1 channel" else "${group.channelCount} channels"
            binding.root.setOnClickListener { onFolderClick(group) }
        }
    }

    inner class FileViewHolder(private val binding: ItemSearchFileBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(channel: Channel) {
            binding.textChannelName.text = channel.name
            binding.textChannelGroup.text = channel.group
            binding.imageLogo.load(channel.logo) {
                crossfade(true)
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
            binding.root.setOnClickListener { onFileClick(channel) }
        }
    }

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_FILE = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SearchResult>() {
            override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
                when {
                    oldItem is SearchResult.Folder && newItem is SearchResult.Folder ->
                        oldItem.group.name == newItem.group.name
                    oldItem is SearchResult.File && newItem is SearchResult.File ->
                        oldItem.channel.id == newItem.channel.id
                    else -> false
                }

            override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
                oldItem == newItem
        }
    }
}
