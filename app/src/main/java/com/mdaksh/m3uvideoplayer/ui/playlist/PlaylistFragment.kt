package com.mdaksh.m3uvideoplayer.ui.playlist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.FragmentPlaylistBinding
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import com.mdaksh.m3uvideoplayer.data.time.TimeZoneManager
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistViewModel by viewModels()
    private lateinit var adapter: PlaylistAdapter
    
    @Inject
    lateinit var timeZoneManager: TimeZoneManager
    
    private var currentZone: java.time.ZoneId = java.time.ZoneId.systemDefault()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PlaylistAdapter(
            onOpen = { playlist ->
                findNavController().navigate(
                    PlaylistFragmentDirections.actionPlaylistFragmentToGroupListFragment(playlist.id)
                )
            },
            onResume = { target ->
                // [FIX] Build the full folder queue so Next/Previous navigate within the same group.
                val resumePos = if (target.isLive) -1L else target.positionMs
                viewLifecycleOwner.lifecycleScope.launch {
                    val (queue, startIndex) = viewModel.buildResumeQueue(target)
                    startActivity(
                        PlayerActivity.newIntent(requireContext(), queue, startIndex, resumePos)
                    )
                }
            },
            formatLastUpdated = { epochMs ->
                if (epochMs > 0) {
                    getString(R.string.last_updated_at, timeZoneManager.formatDateTime(epochMs, currentZone))
                } else {
                    getString(R.string.last_updated_never)
                }
            }
        )
        binding.recyclerViewPlaylists.adapter = adapter

        setupUniversalHeader(
            title = getString(R.string.title_playlists),
            showBack = true,
            showSearch = false,
            showViewMode = false,
            showSort = false,
            onSettings = {
                findNavController().navigate(R.id.action_playlistFragment_to_settingsFragment)
            },
        )

        binding.fabAddPlaylist.setOnClickListener {
            findNavController().navigate(R.id.action_playlistFragment_to_addPlaylistFragment)
        }

        binding.fabDirectLink.setOnClickListener {
            findNavController().navigate(R.id.action_playlistFragment_to_directLinkFragment)
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.playlists.value.forEach { viewModel.refresh(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.playlists.collect { list ->
                        adapter.submitList(list)
                        binding.textEmptyState.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isSyncing.collect { syncing ->
                        binding.swipeRefresh.isRefreshing = syncing
                    }
                }
                launch {
                    viewModel.syncErrors.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.resumeTargets.collect { targets -> adapter.setResumeTargets(targets) }
                }
                launch {
                    timeZoneManager.resolvedTimeZone.collect { resolved ->
                        if (currentZone != resolved.zone) {
                            currentZone = resolved.zone
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        // Don't tear the shared toolbar down here: onDestroyView runs *after* the incoming screen's
        // onViewCreated has already configured the toolbar, so clearing it would blank that screen's
        // header. Each screen fully re-sets the toolbar in its own setupToolbar(), so no cleanup is
        // needed on the way out.
        super.onDestroyView()
        _binding = null
    }
}
