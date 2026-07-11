package com.mdaksh.m3uvideoplayer.ui.group

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.databinding.FragmentGroupListBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * PHASE 1 / Step 4.1 — Group (folder) browsing screen, now the hub for three toolbar actions:
 *  - Search: inline global search over this playlist (folders first, then files).
 *  - View mode: List vs Grid for the folder list (persisted in DataStore).
 *  - Settings: opens the settings shell.
 *
 * Tapping a folder tile opens the Channel List screen filtered to that group (Step 4.2).
 */
@AndroidEntryPoint
class GroupListFragment : Fragment() {

    private var _binding: FragmentGroupListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GroupListViewModel by viewModels()
    private lateinit var adapter: GroupAdapter
    private lateinit var searchAdapter: SearchResultAdapter

    private val toolbar: MaterialToolbar
        get() = requireActivity().findViewById(R.id.toolbar)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GroupAdapter(
            onClick = { group -> openGroup(group) },
            initialViewMode = viewModel.folderViewMode.value
        )
        binding.recyclerViewGroups.adapter = adapter

        searchAdapter = SearchResultAdapter(
            onFolderClick = { group -> openGroup(group) },
            onFileClick = { channel -> openPlayer(channel) }
        )
        binding.recyclerViewSearch.adapter = searchAdapter

        setupToolbar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.folderViewMode.collect { mode -> applyFolderViewMode(mode) }
                }
                // Re-render the grid live when the user changes the saved column count in Settings.
                launch {
                    viewModel.gridColumnCount.collect { applyFolderViewMode(viewModel.folderViewMode.value) }
                }
                launch {
                    viewModel.groups.collect { list ->
                        adapter.submitList(list)
                        renderVisibility()
                    }
                }
                launch {
                    viewModel.isSearching.collect { renderVisibility() }
                }
                launch {
                    viewModel.searchResults.collect { results ->
                        searchAdapter.submitList(results)
                        renderVisibility()
                    }
                }
                launch {
                    viewModel.resumeTarget.collect { target -> renderResumeFab(target) }
                }
            }
        }

        binding.fabResume.setOnClickListener {
            val target = viewModel.resumeTarget.value ?: return@setOnClickListener
            // RULE B / LOGIC_2: live channels bypass positionMs entirely, relaunch at the live edge.
            val resumePos = if (target.isLive) -1L else target.positionMs
            startActivity(PlayerActivity.newIntent(requireContext(), target.channel, resumePos))
        }
    }

    /** Folder Resume FAB — hidden with nothing to continue; a finished VOD hides too (live never does). */
    private fun renderResumeFab(target: PlaylistResumeTarget?) {
        val visible = target != null && (target.isLive || !target.completed)
        binding.fabResume.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun openGroup(group: GroupItem) {
        // The pinned "All channels" tile opens the list unfiltered; a null group means "no filter".
        // The pinned "Favorite" tile also passes null for the group but sets favoritesOnly instead.
        val groupName = if (group.isAllChannels || group.isFavorites) null else group.name
        findNavController().navigate(
            GroupListFragmentDirections.actionGroupListFragmentToChannelListFragment(
                viewModel.playlistId, groupName, group.isFavorites
            )
        )
    }

    /**
     * Fix — search results (files) were never actually launching playback; they showed a
     * placeholder Toast instead. Mirrors [ChannelListFragment]'s openPlayer behavior.
     */
    private fun openPlayer(channel: Channel) {
        if (channel.url.isBlank()) {
            Toast.makeText(requireContext(), R.string.player_error_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        // A global search result isn't tied to any single folder's sort order, so it launches with
        // no Next/Previous queue (buttons stay hidden — see PlayerActivity.updateQueueButtonsVisibility).
        startActivity(PlayerActivity.newIntent(requireContext(), channel))
    }

    // --- Toolbar ------------------------------------------------------------------------------

    private fun setupToolbar() {
        setupUniversalHeader(
            title = getString(R.string.title_folders),
            showBack = true,
            onQueryChange = { viewModel.setSearchQuery(it) },
            onViewMode = { showFolderViewModeMenu() },
            onSort = { showSortMenu() },
            onSettings = {
                findNavController().navigate(R.id.action_groupListFragment_to_settingsFragment)
            },
        )
    }

    private fun showFolderViewModeMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_view_mode) ?: toolbar
        val themedContext = ContextThemeWrapper(requireContext(), R.style.Theme_M3uVideoPlayer_Popup)
        PopupMenu(themedContext, anchor).apply {
            menuInflater.inflate(R.menu.menu_folder_view_mode, menu)

            // Mark the active view mode as checked.
            val currentMode = viewModel.folderViewMode.value
            val currentItemId = when (currentMode) {
                FolderViewMode.LIST -> R.id.folder_mode_list
                FolderViewMode.GRID -> R.id.folder_mode_grid
            }
            menu.findItem(currentItemId)?.isChecked = true

            setOnMenuItemClickListener { item ->
                val mode = when (item.itemId) {
                    R.id.folder_mode_list -> FolderViewMode.LIST
                    R.id.folder_mode_grid -> FolderViewMode.GRID
                    else -> return@setOnMenuItemClickListener false
                }
                viewModel.setFolderViewMode(mode)
                true
            }
            show()
        }
    }

    private fun showSortMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_sort) ?: toolbar
        val themedContext = ContextThemeWrapper(requireContext(), R.style.Theme_M3uVideoPlayer_Popup)
        PopupMenu(themedContext, anchor).apply {
            menuInflater.inflate(R.menu.menu_folder_sort, menu)

            // Mark the active sort order as checked.
            val currentOrder = viewModel.sortOrder.value
            val currentItemId = when (currentOrder) {
                FolderSortOrder.ASCENDING -> R.id.sort_ascending
                FolderSortOrder.DESCENDING -> R.id.sort_descending
                FolderSortOrder.PLAYLIST -> R.id.sort_playlist
            }
            menu.findItem(currentItemId)?.isChecked = true

            setOnMenuItemClickListener { item ->
                val order = when (item.itemId) {
                    R.id.sort_ascending -> FolderSortOrder.ASCENDING
                    R.id.sort_descending -> FolderSortOrder.DESCENDING
                    R.id.sort_playlist -> FolderSortOrder.PLAYLIST
                    else -> return@setOnMenuItemClickListener false
                }
                viewModel.setSortOrder(order)
                true
            }
            show()
        }
    }

    // --- Rendering ----------------------------------------------------------------------------

    private fun applyFolderViewMode(mode: FolderViewMode) {
        adapter.setViewMode(mode)
        binding.recyclerViewGroups.layoutManager = when (mode) {
            FolderViewMode.GRID -> GridLayoutManager(requireContext(), resolveGridSpan())
            FolderViewMode.LIST -> LinearLayoutManager(requireContext())
        }
    }

    /**
     * Columns for the folder grid: the user's saved count when set, otherwise the responsive
     * default (`R.integer.grid_span_count` — 3 on phones, 4 on large screens).
     */
    private fun resolveGridSpan(): Int {
        val custom = viewModel.gridColumnCount.value
        return if (custom >= UserPreferencesRepository.COLUMN_COUNT_MIN) custom
        else resources.getInteger(R.integer.grid_span_count)
    }

    /** Single source of truth for which of the three content views (grid / results / empty) shows. */
    private fun renderVisibility() {
        val searching = viewModel.isSearching.value
        binding.recyclerViewSearch.visibility = if (searching) View.VISIBLE else View.GONE
        binding.recyclerViewGroups.visibility = if (searching) View.GONE else View.VISIBLE
        binding.textEmptyGroups.visibility =
            if (!searching && viewModel.groups.value.isEmpty()) View.VISIBLE else View.GONE
        binding.textEmptySearch.visibility =
            if (searching && viewModel.searchResults.value.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        // Don't tear the shared toolbar down here: onDestroyView runs *after* the incoming screen's
        // onViewCreated has already configured the toolbar, so clearing it would blank that screen's
        // header. Each screen fully re-sets the toolbar in its own setupToolbar().
        super.onDestroyView()
        _binding = null
    }
}
