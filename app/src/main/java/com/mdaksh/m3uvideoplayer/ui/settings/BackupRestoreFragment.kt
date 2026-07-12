package com.mdaksh.m3uvideoplayer.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.FragmentBackupRestoreBinding
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Backup & Restore screen — "Save Application Data" exports every playlist, channel (incl.
 * favorites), group, playback history entry, and UI preference as one JSON file via the native
 * "Save As" picker; "Load Application Data" restores from a previously saved JSON file picked via
 * the native file picker (JSON-only filter).
 *
 * All the actual read/parse/write work happens in [BackupRestoreViewModel] /
 * `BackupRestoreRepository` — this Fragment only wires the two Storage Access Framework launchers
 * and reflects [BackupRestoreViewModel]'s busy/event state.
 */
@AndroidEntryPoint
class BackupRestoreFragment : Fragment() {

    private var _binding: FragmentBackupRestoreBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BackupRestoreViewModel by viewModels()

    /** "Save Application Data" — system "Save As" dialog, pre-filled with the dynamic filename. */
    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { viewModel.exportTo(requireContext().contentResolver, it) }
        }

    /** "Load Application Data" — system file picker, restricted to JSON files only. */
    private val openBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmImport(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupRestoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUniversalHeader(
            title = getString(R.string.title_backup_restore),
            showBack = true,
            showSearch = false,
            showViewMode = false,
            showSort = false
        )

        binding.cardSaveData.setOnClickListener {
            createBackupLauncher.launch(viewModel.suggestedBackupFileName())
        }
        binding.cardLoadData.setOnClickListener {
            openBackupLauncher.launch(arrayOf("application/json"))
        }
        binding.cardLoadDemoData.setOnClickListener {
            viewModel.loadDemoData(requireContext().assets)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isBusy.collect { busy ->
                        binding.progressOverlay.visibility = if (busy) View.VISIBLE else View.GONE
                        binding.cardSaveData.isEnabled = !busy
                        binding.cardLoadData.isEnabled = !busy
                        binding.cardLoadDemoData.isEnabled = !busy
                    }
                }
                launch {
                    viewModel.event.collect { event ->
                        if (event != null) {
                            handleEvent(event)
                            viewModel.consumeEvent()
                        }
                    }
                }
            }
        }
    }

    private fun confirmImport(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(R.string.backup_import_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore) { _, _ ->
                viewModel.importFrom(requireContext().contentResolver, uri)
            }
            .show()
    }

    private fun handleEvent(event: BackupRestoreEvent) {
        val message = when (event) {
            BackupRestoreEvent.ExportSucceeded -> getString(R.string.backup_export_success)
            BackupRestoreEvent.ImportSucceeded -> getString(R.string.backup_import_success)
            BackupRestoreEvent.DemoLoadSucceeded -> getString(R.string.demo_data_load_success)
            is BackupRestoreEvent.Failed -> event.message
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
