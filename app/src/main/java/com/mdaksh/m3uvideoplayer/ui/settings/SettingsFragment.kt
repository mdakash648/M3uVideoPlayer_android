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
import com.mdaksh.m3uvideoplayer.data.preferences.UserPreferencesRepository.Companion.COLUMN_COUNT_AUTO
import com.mdaksh.m3uvideoplayer.databinding.FragmentSettingsBinding
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Settings screen — top-level menu. Reuses the shared activity toolbar to show a back
 * arrow + "Settings" title; each row below opens its own dedicated screen.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private val exportHistoryLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            uri?.let { viewModel.exportHistoryTo(requireContext().contentResolver, it) }
        }

    private val importHistoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importHistoryFrom(requireContext().contentResolver, it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUniversalHeader(
            title = getString(R.string.title_settings),
            showBack = true,
            showSearch = false,
            showViewMode = false,
            showSort = false,
            showSettings = false
        )

        binding.cardMenuPlaylists.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_settingsPlaylistsFragment)
        }
        binding.cardUpdatePlaylists.setOnClickListener {
            viewModel.updateAllPlaylists()
        }
        binding.cardMenuBackupRestore.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_backupRestoreFragment)
        }

        binding.cardExportHistory.setOnClickListener {
            exportHistoryLauncher.launch("direct_links_history.m3u")
        }
        binding.cardImportHistory.setOnClickListener {
            importHistoryLauncher.launch(arrayOf("*/*"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.event.collect { event ->
                        if (event != null) {
                            Toast.makeText(requireContext(), event, Toast.LENGTH_LONG).show()
                            viewModel.consumeEvent()
                        }
                    }
                }
                launch {
                    // While an update runs: disable the card (dimmed) and swap the chevron for a
                    // spinner. Both revert automatically the moment the flow flips back to false.
                    viewModel.isUpdatingPlaylists.collect { updating ->
                        binding.cardUpdatePlaylists.isEnabled = !updating
                        binding.cardUpdatePlaylists.alpha = if (updating) 0.5f else 1f
                        binding.progressUpdatePlaylists.visibility =
                            if (updating) View.VISIBLE else View.GONE
                        binding.iconUpdatePlaylistsChevron.visibility =
                            if (updating) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.resolvedTimeZone.collect { resolved ->
                        val text = if (resolved.mode == com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode.AUTO) {
                            getString(R.string.time_zone_auto_detected, resolved.id)
                        } else {
                            getString(R.string.time_zone_manual, resolved.id)
                        }
                        binding.textTimeZoneValue.text = text
                    }
                }
            }
        }

        binding.cardTimeZone.setOnClickListener {
            showTimeZoneDialog()
        }

        setupColumnSteppers()
    }

    private fun showTimeZoneDialog() {
        // Build timezone entries with UTC offset labels (e.g. "Asia/Dhaka (UTC+6:00)")
        val now = java.time.Instant.now()
        val allZones = java.time.ZoneId.getAvailableZoneIds()
            .map { id ->
                val zone = java.time.ZoneId.of(id)
                val offset = zone.rules.getOffset(now)
                val offsetStr = formatUtcOffset(offset)
                TimeZoneEntry(id, "$id (UTC$offsetStr)", offset.totalSeconds)
            }
            .sortedWith(compareBy<TimeZoneEntry> { it.offsetSeconds }.thenBy { it.id })

        val autoLabel = getString(R.string.time_zone_option_auto)

        val currentPref = viewModel.timeZonePreference.value
        val currentSelectedId = if (currentPref.mode == com.mdaksh.m3uvideoplayer.domain.model.TimeZoneMode.AUTO) {
            null // null means "Auto" is selected
        } else {
            currentPref.zoneId
        }

        // Inflate custom layout with search + list
        val dialogView = layoutInflater.inflate(R.layout.dialog_timezone_picker, null)
        val editSearch = dialogView.findViewById<android.widget.EditText>(R.id.editSearch)
        val listView = dialogView.findViewById<android.widget.ListView>(R.id.listTimeZones)

        // Build the full display list: Auto option at position 0, then all timezone entries
        val fullList = mutableListOf(autoLabel) + allZones.map { it.displayLabel }
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_single_choice,
            fullList.toMutableList()
        )
        listView.adapter = adapter

        // Pre-select the current timezone
        val initialIndex = if (currentSelectedId == null) {
            0
        } else {
            val zoneIdx = allZones.indexOfFirst { it.id == currentSelectedId }
            if (zoneIdx != -1) zoneIdx + 1 else 0
        }
        listView.setItemChecked(initialIndex, true)
        listView.post { listView.setSelection(initialIndex) }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.time_zone_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // Track currently filtered list to map click positions back
        var filteredZones = allZones.toList()

        // Search/filter logic
        editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                adapter.clear()
                if (query.isEmpty()) {
                    filteredZones = allZones.toList()
                    adapter.add(autoLabel)
                    allZones.forEach { adapter.add(it.displayLabel) }
                } else {
                    filteredZones = allZones.filter { entry ->
                        entry.id.lowercase().contains(query) ||
                        entry.displayLabel.lowercase().contains(query)
                    }
                    // Show Auto only if query matches
                    if (autoLabel.lowercase().contains(query)) {
                        adapter.add(autoLabel)
                    }
                    filteredZones.forEach { adapter.add(it.displayLabel) }
                }
                adapter.notifyDataSetChanged()
            }
        })

        // Handle selection
        listView.setOnItemClickListener { _, _, position, _ ->
            val query = editSearch.text?.toString()?.trim()?.lowercase() ?: ""
            val showsAuto = query.isEmpty() || autoLabel.lowercase().contains(query)

            if (showsAuto && position == 0) {
                viewModel.setTimeZoneAuto()
            } else {
                val zoneIndex = if (showsAuto) position - 1 else position
                if (zoneIndex in filteredZones.indices) {
                    viewModel.setTimeZoneManual(filteredZones[zoneIndex].id)
                }
            }
            dialog.dismiss()
        }

        dialog.show()

        // Set a fixed height for the dialog so the list is scrollable
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.75).toInt()
        )
    }

    /** Formats a ZoneOffset like +5:30, -8:00, +0:00 */
    private fun formatUtcOffset(offset: java.time.ZoneOffset): String {
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return if (minutes == 0) "$sign$hours" else "$sign$hours:${minutes.toString().padStart(2, '0')}"
    }

    /** Data holder for a timezone entry in the picker dialog. */
    private data class TimeZoneEntry(
        val id: String,
        val displayLabel: String,
        val offsetSeconds: Int
    )

    /**
     * Wires the two Display steppers (Grid & Poster column count) to [SettingsViewModel]. Each tap
     * persists through the DataStore, so the Folder / Folder Content grids re-render live; the value
     * label and the enabled state of the +/- buttons just reflect whatever the flow emits back.
     */
    private fun setupColumnSteppers() = with(binding) {
        btnGridColumnsMinus.setOnClickListener { viewModel.decrementGridColumns() }
        btnGridColumnsPlus.setOnClickListener { viewModel.incrementGridColumns() }
        btnPosterColumnsMinus.setOnClickListener { viewModel.decrementPosterColumns() }
        btnPosterColumnsPlus.setOnClickListener { viewModel.incrementPosterColumns() }
        btnControlsTimeoutMinus.setOnClickListener { viewModel.decrementControlsTimeout() }
        btnControlsTimeoutPlus.setOnClickListener { viewModel.incrementControlsTimeout() }
        btnSwipeSensitivityMinus.setOnClickListener { viewModel.decrementSwipeSensitivity() }
        btnSwipeSensitivityPlus.setOnClickListener { viewModel.incrementSwipeSensitivity() }
 
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.gridColumnCount.collect { count ->
                        textGridColumnsValue.text = columnLabel(count)
                        setStepperEnabled(btnGridColumnsMinus, !SettingsViewModel.isAtMin(count))
                        setStepperEnabled(btnGridColumnsPlus, !SettingsViewModel.isAtMax(count))
                    }
                }
                launch {
                    viewModel.posterColumnCount.collect { count ->
                        textPosterColumnsValue.text = columnLabel(count)
                        setStepperEnabled(btnPosterColumnsMinus, !SettingsViewModel.isAtMin(count))
                        setStepperEnabled(btnPosterColumnsPlus, !SettingsViewModel.isAtMax(count))
                    }
                }
                launch {
                    viewModel.controlsTimeoutMs.collect { timeoutMs ->
                        textControlsTimeoutValue.text = timeoutLabel(timeoutMs)
                        setStepperEnabled(btnControlsTimeoutMinus, !SettingsViewModel.isAtControlsTimeoutMin(timeoutMs))
                        setStepperEnabled(btnControlsTimeoutPlus, !SettingsViewModel.isAtControlsTimeoutMax(timeoutMs))
                    }
                }
                launch {
                    viewModel.swipeSensitivityPercent.collect { percent ->
                        textSwipeSensitivityValue.text = "$percent%"
                        setStepperEnabled(btnSwipeSensitivityMinus, !SettingsViewModel.isAtSwipeSensitivityMin(percent))
                        setStepperEnabled(btnSwipeSensitivityPlus, !SettingsViewModel.isAtSwipeSensitivityMax(percent))
                    }
                }
            }
        }
    }

    /** "Auto" for the responsive default, otherwise the plain column number. */
    private fun columnLabel(count: Int): String =
        if (count == COLUMN_COUNT_AUTO) getString(R.string.column_count_auto) else count.toString()

    /** Formats milliseconds to seconds representation (e.g. 3500 -> "3.5s"). */
    private fun timeoutLabel(timeoutMs: Int): String {
        val seconds = timeoutMs.toFloat() / 1000f
        return String.format(java.util.Locale.US, "%.1fs", seconds)
    }

    /** Dims a stepper button to read as disabled once its end of the ladder is reached. */
    private fun setStepperEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.3f
    }

    override fun onDestroyView() {
        // No toolbar teardown here — it would run after the next screen's setup and blank its header.
        // The incoming screen's setupToolbar() resets title/menu/navigation icon itself.
        super.onDestroyView()
        _binding = null
    }
}
