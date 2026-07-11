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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaylistFragment : Fragment() {

    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PlaylistViewModel by viewModels()
    private lateinit var adapter: PlaylistAdapter

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
            onRefresh = { playlist -> viewModel.refresh(playlist) },
            onDelete = { playlist -> viewModel.delete(playlist) },
            onResume = { target ->
                // Floating Resume Button engine — RULE B / LOGIC_2: live channels bypass positionMs
                // entirely and relaunch at the real-time edge (-1 = no seek).
                val resumePos = if (target.isLive) -1L else target.positionMs
                startActivity(
                    PlayerActivity.newIntent(requireContext(), target.channel, resumePos)
                )
            }
        )
        binding.recyclerViewPlaylists.adapter = adapter

        setupUniversalHeader(
            title = getString(R.string.title_playlists),
            onQueryChange = { viewModel.setSearchQuery(it) },
            onSettings = {
                findNavController().navigate(R.id.action_playlistFragment_to_settingsFragment)
            },
        )

        binding.fabAddPlaylist.setOnClickListener {
            findNavController().navigate(R.id.action_playlistFragment_to_addPlaylistFragment)
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
