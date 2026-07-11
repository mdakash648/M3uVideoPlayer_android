package com.mdaksh.m3uvideoplayer.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.ItemGroupBinding
import com.mdaksh.m3uvideoplayer.databinding.ItemGroupListBinding

/**
 * UI-only representation of a single folder/group tile shown on the [GroupListFragment] grid.
 * Built by grouping the playlist's channels by [Channel.group] name (see [GroupListViewModel]).
 *
 * [isAllChannels] marks the synthetic "All channels" tile pinned at position 1, and [isFavorites]
 * marks the synthetic "Favorite" tile pinned at position 2 — both ignore the folder Sort Filter and,
 * when tapped, open the channel list filtered accordingly (unfiltered / favorites-only). Their
 * [name] is blank — the label comes from a string resource at bind time.
 */
data class GroupItem(
    val name: String,
    val channelCount: Int,
    val isAllChannels: Boolean = false,
    val isFavorites: Boolean = false
)

/**
 * Renders folders in either [FolderViewMode.GRID] (card tiles) or [FolderViewMode.LIST] (rows).
 * The active mode drives the item view type; [GroupListFragment] pairs it with the matching
 * LayoutManager and persists the choice via DataStore.
 */
class GroupAdapter(
    private val onClick: (GroupItem) -> Unit,
    initialViewMode: FolderViewMode = FolderViewMode.DEFAULT
) : ListAdapter<GroupItem, GroupAdapter.BaseGroupViewHolder>(DIFF_CALLBACK) {

    var viewMode: FolderViewMode = initialViewMode
        private set

    fun setViewMode(mode: FolderViewMode) {
        if (mode == viewMode) return
        viewMode = mode
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = viewMode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseGroupViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (FolderViewMode.values()[viewType]) {
            FolderViewMode.GRID ->
                GridViewHolder(ItemGroupBinding.inflate(inflater, parent, false))
            FolderViewMode.LIST ->
                ListViewHolder(ItemGroupListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: BaseGroupViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    abstract class BaseGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(group: GroupItem, onClick: (GroupItem) -> Unit)
    }

    /** [FolderViewMode.GRID] — the original card tile. */
    inner class GridViewHolder(private val binding: ItemGroupBinding) :
        BaseGroupViewHolder(binding.root) {
        override fun bind(group: GroupItem, onClick: (GroupItem) -> Unit) {
            binding.textGroupName.text = labelFor(group, binding.root.context)
            binding.textGroupCount.text = countLabel(group.channelCount)
            binding.root.setOnClickListener { onClick(group) }
        }
    }

    /** [FolderViewMode.LIST] — a compact horizontal row. */
    inner class ListViewHolder(private val binding: ItemGroupListBinding) :
        BaseGroupViewHolder(binding.root) {
        override fun bind(group: GroupItem, onClick: (GroupItem) -> Unit) {
            binding.textGroupName.text = labelFor(group, binding.root.context)
            binding.textGroupCount.text = countLabel(group.channelCount)
            binding.root.setOnClickListener { onClick(group) }
        }
    }

    companion object {
        private fun labelFor(group: GroupItem, context: android.content.Context): String = when {
            group.isAllChannels -> context.getString(R.string.all_channels)
            group.isFavorites -> context.getString(R.string.favorite)
            else -> group.name
        }

        private fun countLabel(count: Int): String =
            if (count == 1) "1 channel" else "$count channels"

        /** Stable identity key: the two synthetic tiles both have a blank [GroupItem.name]. */
        private fun key(group: GroupItem): String = when {
            group.isAllChannels -> "__all_channels__"
            group.isFavorites -> "__favorites__"
            else -> group.name
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<GroupItem>() {
            override fun areItemsTheSame(oldItem: GroupItem, newItem: GroupItem) =
                key(oldItem) == key(newItem)

            override fun areContentsTheSame(oldItem: GroupItem, newItem: GroupItem) =
                oldItem == newItem
        }
    }
}
