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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.databinding.FragmentGroupListBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.domain.model.PlaylistResumeTarget
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity
import androidx.media3.common.util.UnstableApi
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
@UnstableApi
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
            onLongClick = { group -> handleFolderLongClick(group) },
            initialViewMode = viewModel.folderViewMode.value
        )
        binding.recyclerViewGroups.adapter = adapter

        searchAdapter = SearchResultAdapter(
            onFolderClick = { group -> openGroup(group) },
            onFileClick = { channel -> openPlayer(channel) },
            onFolderLongClick = { group -> handleFolderLongClick(group) },
            onFileLongClick = { channel -> handleSearchFileLongClick(channel) }
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
            val resumePos = if (target.isLive) 0L else target.positionMs
            // [FIX] Build the full folder queue so Next/Previous navigate within the same group.
            // buildResumeQueue() is a one-shot suspend call; launch on the lifecycle scope so it
            // is automatically cancelled if the fragment is destroyed before it completes.
            viewLifecycleOwner.lifecycleScope.launch {
                val (queue, startIndex) = viewModel.buildResumeQueue(target)
                startActivity(
                    PlayerActivity.newIntent(requireContext(), queue, startIndex, resumePos)
                )
            }
        }
    }

    /** Folder Resume FAB — hidden with nothing to continue; a finished VOD hides too (live never does). */
    private fun renderResumeFab(target: PlaylistResumeTarget?) {
        val visible = target != null && (target.isLive || !target.completed)
        binding.fabResume.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun openGroup(group: GroupItem) {
        when {
            group.isAllMovies -> {
                // "All Movies" tile → flat channel list filtered to MOVIE contentType only
                findNavController().navigate(
                    GroupListFragmentDirections.actionGroupListFragmentToChannelListFragment(
                        playlistId = viewModel.playlistId,
                        groupName = null,
                        favoritesOnly = false,
                        contentFilter = "MOVIE"
                    )
                )
            }
            group.isAllSeries -> {
                // "All Series" tile → GroupList sub-screen (seriesOnly=true) showing per-series folders
                findNavController().navigate(
                    GroupListFragmentDirections.actionGroupListFragmentSelf(
                        playlistId = viewModel.playlistId,
                        seriesOnly = true
                    )
                )
            }
            group.isAllChannels || group.isFavorites -> {
                // Pinned "All channels" or "Favorite" tile → unfiltered / favorites-only channel list
                findNavController().navigate(
                    GroupListFragmentDirections.actionGroupListFragmentToChannelListFragment(
                        playlistId = viewModel.playlistId,
                        groupName = null,
                        favoritesOnly = group.isFavorites,
                        contentFilter = null
                    )
                )
            }
            viewModel.seriesOnly -> {
                // Inside the "All Series" sub-screen: tapping a folder shows SERIES episodes for that group
                findNavController().navigate(
                    GroupListFragmentDirections.actionGroupListFragmentToChannelListFragment(
                        playlistId = viewModel.playlistId,
                        groupName = group.name,
                        favoritesOnly = false,
                        contentFilter = "SERIES"
                    )
                )
            }
            else -> {
                // Regular folder tile → channel list for that group, no contentType filter
                findNavController().navigate(
                    GroupListFragmentDirections.actionGroupListFragmentToChannelListFragment(
                        playlistId = viewModel.playlistId,
                        groupName = group.name,
                        favoritesOnly = false,
                        contentFilter = null
                    )
                )
            }
        }
    }

    /**
     * Folder Delete — long-pressing a folder tile. Only armed inside "Direct Links History"
     * ([GroupListViewModel.isDeletable]) and never on a pinned/synthetic tile (All channels,
     * Favorite, All Movies, All Series), since those aren't real folders.
     * @return true to consume the long-press (dialog shown), false to let it pass through untouched.
     */
    private fun handleFolderLongClick(group: GroupItem): Boolean {
        if (!viewModel.isDeletable.value) return false
        if (group.isAllChannels || group.isFavorites || group.isAllMovies || group.isAllSeries) return false
        confirmDeleteFolder(group)
        return true
    }

    private fun handleSearchFileLongClick(channel: Channel): Boolean {
        if (!viewModel.isDeletable.value) return false
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_video_confirm_title)
            .setMessage(getString(R.string.delete_video_confirm_message, channel.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                // GroupListViewModel needs a deleteVideo function as well if we support search hits
                // but for now let's just use the existing logic if possible.
                // Actually, I'll add deleteVideo to GroupListViewModel too.
                viewModel.deleteVideo(channel)
            }
            .show()
        return true
    }

    private fun confirmDeleteFolder(group: GroupItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_folder_confirm_title)
            .setMessage(getString(R.string.delete_folder_confirm_message, group.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteFolder(group.name) }
            .show()
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
            title = if (viewModel.seriesOnly) getString(R.string.all_series) else getString(R.string.title_folders),
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
