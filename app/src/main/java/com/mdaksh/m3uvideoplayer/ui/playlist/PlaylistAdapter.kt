package com.mdaksh.m3uvideoplayer.ui.playlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mdaksh.m3uvideoplayer.databinding.ItemPlaylistBinding
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget

class PlaylistAdapter(
    private val onOpen: (Playlist) -> Unit,
    /** Floating Resume Button engine — tap relaunches [PlaylistResumeTarget.channel]. */
    private val onResume: (PlaylistResumeTarget) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(DIFF_CALLBACK) {

    /** playlistId -> active resume target; updated live from PlaylistViewModel. */
    private var resumeTargets: Map<Long, PlaylistResumeTarget> = emptyMap()

    /** Refresh just the resume-icon state for every visible row without a full list diff. */
    fun setResumeTargets(targets: Map<Long, PlaylistResumeTarget>) {
        resumeTargets = targets
        notifyItemRangeChanged(0, itemCount, RESUME_PAYLOAD)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: PlaylistViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(RESUME_PAYLOAD)) {
            holder.bindResume(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PlaylistViewHolder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: Playlist) {
            binding.textPlaylistName.text = playlist.name
            // Show the source URL under the name so users can instantly recognise where it came from.
            binding.textPlaylistType.text = playlist.url

            binding.root.setOnClickListener { onOpen(playlist) }
            bindResume(playlist)
        }

        fun bindResume(playlist: Playlist) {
            val target = resumeTargets[playlist.id]
            // RULE B / LOGIC_2 — a completed VOD hides the icon; a live channel never completes,
            // so it always stays visible.
            val visible = target != null && (target.isLive || !target.completed)
            binding.buttonResume.visibility = if (visible) View.VISIBLE else View.GONE
            binding.buttonResume.setOnClickListener {
                target?.let(onResume)
            }
        }
    }

    companion object {
        private val RESUME_PAYLOAD = Any()

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist) =
                oldItem == newItem
        }
    }
}
