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
            }
        }

        setupColumnSteppers()
    }

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
