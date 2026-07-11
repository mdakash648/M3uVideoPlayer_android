package com.mdaksh.m3uvideoplayer.ui.channel

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository
import com.mdaksh.m3uvideoplayer.databinding.FragmentChannelListBinding
import com.mdaksh.m3uvideoplayer.domain.model.Channel
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChannelListFragment : Fragment() {

    private var _binding: FragmentChannelListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelListViewModel by viewModels()
    private lateinit var adapter: ChannelAdapter

    private val toolbar: MaterialToolbar
        get() = requireActivity().findViewById(R.id.toolbar)

    /** Title shown in the shared toolbar: "Favorite", the group name, or "All channels". */
    private val screenTitle: String
        get() = when {
            viewModel.favoritesOnly -> getString(R.string.favorite)
            viewModel.groupName != null -> viewModel.groupName!!
            else -> getString(R.string.all_channels)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChannelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Seed with whatever mode the persisted flow currently holds so the first inflate already
        // matches; the collector below keeps it in sync afterwards.
        val initialMode = viewModel.viewMode.value
        adapter = ChannelAdapter(
            onClick = { channel -> openPlayer(channel) },
            onToggleFavorite = { channel -> viewModel.toggleFavorite(channel) },
            initialViewMode = initialMode
        )
        binding.recyclerViewChannels.layoutManager = layoutManagerFor(initialMode)
        binding.recyclerViewChannels.adapter = adapter

        setupToolbar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.channels.collect { list ->
                        adapter.submitList(list)
                        binding.textEmptyChannels.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                // Step 4.4/4.5: react to the persisted view mode (changed here or, later, anywhere).
                launch {
                    viewModel.viewMode.collect { mode -> applyViewMode(mode) }
                }
                // Re-render the grid/poster live when the user changes the saved column counts.
                launch {
                    viewModel.gridColumnCount.collect { reapplyColumns() }
                }
                launch {
                    viewModel.posterColumnCount.collect { reapplyColumns() }
                }
            }
        }
    }

    /**
     * Step 9.3 — launches the LibVLC [PlayerActivity] for the tapped channel. Series entries can have
     * an empty URL until episode resolution (a later phase), so guard against that.
     */
    private fun openPlayer(channel: Channel) {
        if (channel.url.isBlank()) {
            Toast.makeText(requireContext(), R.string.player_error_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        // [FEATURE UPDATE] Pass the currently-displayed (filter-aware) list so the player's
        // Next/Previous buttons honor this screen's exact active sort order (CASE_1/CASE_2).
        val orderedChannels = viewModel.channels.value
        val startIndex = orderedChannels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        startActivity(PlayerActivity.newIntent(requireContext(), orderedChannels, startIndex))
    }

    // --- Toolbar ------------------------------------------------------------------------------

    private fun setupToolbar() {
        setupUniversalHeader(
            title = screenTitle,
            showBack = true,
            onQueryChange = { viewModel.setSearchQuery(it) },
            onViewMode = { showViewModeMenu() },
            onSort = { showSortMenu() },
            onSettings = {
                findNavController().navigate(R.id.action_channelListFragment_to_settingsFragment)
            },
        )
    }

    /**
     * Anchored menu of the three [ChannelSortOrder]s. Reorders only the channels inside this
     * folder — entirely independent of the Folder screen's own sort action.
     */
    private fun showSortMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_sort) ?: toolbar
        val themedContext = ContextThemeWrapper(requireContext(), R.style.Theme_M3uVideoPlayer_Popup)
        PopupMenu(themedContext, anchor).apply {
            menuInflater.inflate(R.menu.menu_channel_sort, menu)
            
            // Mark the active sort order as checked.
            val currentOrder = viewModel.sortOrder.value
            val currentItemId = when (currentOrder) {
                ChannelSortOrder.ASCENDING -> R.id.sort_ascending
                ChannelSortOrder.DESCENDING -> R.id.sort_descending
                ChannelSortOrder.PLAYLIST -> R.id.sort_playlist
            }
            menu.findItem(currentItemId)?.isChecked = true

            setOnMenuItemClickListener { item ->
                val order = when (item.itemId) {
                    R.id.sort_ascending -> ChannelSortOrder.ASCENDING
                    R.id.sort_descending -> ChannelSortOrder.DESCENDING
                    R.id.sort_playlist -> ChannelSortOrder.PLAYLIST
                    else -> return@setOnMenuItemClickListener false
                }
                viewModel.setSortOrder(order)
                true
            }
            show()
        }
    }

    /** Step 4.5 — anchored menu of the four [ChannelViewMode]s; selection is persisted, not local. */
    private fun showViewModeMenu() {
        val anchor = toolbar.findViewById<View>(R.id.action_view_mode) ?: toolbar
        val themedContext = ContextThemeWrapper(requireContext(), R.style.Theme_M3uVideoPlayer_Popup)
        PopupMenu(themedContext, anchor).apply {
            menuInflater.inflate(R.menu.menu_channel_view_mode, menu)

            // Mark the active view mode as checked.
            val currentMode = viewModel.viewMode.value
            val currentItemId = when (currentMode) {
                ChannelViewMode.LIST -> R.id.mode_list
                ChannelViewMode.GRID -> R.id.mode_grid
                ChannelViewMode.TITLE_ONLY -> R.id.mode_title_only
                ChannelViewMode.POSTER -> R.id.mode_poster
            }
            menu.findItem(currentItemId)?.isChecked = true

            setOnMenuItemClickListener { item ->
                val mode = when (item.itemId) {
                    R.id.mode_list -> ChannelViewMode.LIST
                    R.id.mode_grid -> ChannelViewMode.GRID
                    R.id.mode_title_only -> ChannelViewMode.TITLE_ONLY
                    R.id.mode_poster -> ChannelViewMode.POSTER
                    else -> return@setOnMenuItemClickListener false
                }
                viewModel.setViewMode(mode)
                true
            }
            show()
        }
    }

    // --- Rendering ----------------------------------------------------------------------------

    /**
     * Step 4.3/4.5 — switches both the adapter's item layout and the RecyclerView's LayoutManager
     * together, since grid modes need a [GridLayoutManager] and row modes need a
     * [LinearLayoutManager]. Driven by the persisted [ChannelListViewModel.viewMode] flow.
     */
    private fun applyViewMode(mode: ChannelViewMode) {
        if (mode == adapter.viewMode &&
            binding.recyclerViewChannels.layoutManager?.isGridFor(mode) == true
        ) return
        adapter.setViewMode(mode)
        binding.recyclerViewChannels.layoutManager = layoutManagerFor(mode)
    }

    private fun layoutManagerFor(mode: ChannelViewMode): RecyclerView.LayoutManager =
        when (mode) {
            ChannelViewMode.GRID ->
                GridLayoutManager(requireContext(), spanFor(viewModel.gridColumnCount.value))
            ChannelViewMode.POSTER ->
                GridLayoutManager(requireContext(), spanFor(viewModel.posterColumnCount.value))
            ChannelViewMode.LIST, ChannelViewMode.TITLE_ONLY -> LinearLayoutManager(requireContext())
        }

    /**
     * Columns for a grid/poster: the user's saved [custom] count when set, otherwise the responsive
     * default (`R.integer.grid_span_count` — 3 on phones, 4 on large screens).
     */
    private fun spanFor(custom: Int): Int =
        if (custom >= UserPreferencesRepository.COLUMN_COUNT_MIN) custom
        else resources.getInteger(R.integer.grid_span_count)

    /**
     * Rebuilds the layout manager in place when only a column count changed (the view mode itself is
     * unchanged, so [applyViewMode]'s early-return would otherwise skip it). No-op for row modes.
     */
    private fun reapplyColumns() {
        val mode = viewModel.viewMode.value
        if (mode == ChannelViewMode.GRID || mode == ChannelViewMode.POSTER) {
            binding.recyclerViewChannels.layoutManager = layoutManagerFor(mode)
        }
    }

    /** True when the current layout manager type already matches what [mode] needs. */
    private fun RecyclerView.LayoutManager.isGridFor(mode: ChannelViewMode): Boolean {
        val needsGrid = mode == ChannelViewMode.GRID || mode == ChannelViewMode.POSTER
        return (this is GridLayoutManager) == needsGrid
    }

    override fun onDestroyView() {
        // Don't tear the shared toolbar down here: onDestroyView runs *after* the incoming screen's
        // onViewCreated has already configured the toolbar, so clearing it would blank that screen's
        // header. Each screen fully re-sets the toolbar in its own setupToolbar().
        super.onDestroyView()
        _binding = null
    }
}
