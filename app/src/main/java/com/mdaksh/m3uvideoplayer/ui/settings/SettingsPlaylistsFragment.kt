package com.mdaksh.m3uvideoplayer.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.FragmentSettingsPlaylistsBinding
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Settings ➔ Playlists: lists all saved playlists; tapping one opens the Edit Playlist screen. */
@AndroidEntryPoint
class SettingsPlaylistsFragment : Fragment() {

    private var _binding: FragmentSettingsPlaylistsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsPlaylistsViewModel by viewModels()
    private lateinit var adapter: SettingsPlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SettingsPlaylistAdapter(
            onOpen = { playlist ->
                findNavController().navigate(
                    SettingsPlaylistsFragmentDirections
                        .actionSettingsPlaylistsFragmentToEditPlaylistFragment(playlist.id)
                )
            }
        )
        binding.recyclerViewPlaylists.adapter = adapter

        setupToolbar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playlists.collect { list ->
                    adapter.submitList(list)
                    binding.textEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupToolbar() {
        setupUniversalHeader(
            title = getString(R.string.title_manage_playlists),
            showBack = true,
            showSearch = false,
            showViewMode = false,
            showSort = false
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
