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

/**
 * Settings screen — top-level menu. Reuses the shared activity toolbar to show a back
 * arrow + "Settings" title; each row below opens its own dedicated screen.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

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
            }
        }
    }

    /** "Auto" for the responsive default, otherwise the plain column number. */
    private fun columnLabel(count: Int): String =
        if (count == COLUMN_COUNT_AUTO) getString(R.string.column_count_auto) else count.toString()

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
