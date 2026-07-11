package com.mdaksh.m3uvideoplayer.ui.addplaylist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.data.local.ImportedFile
import com.mdaksh.m3uvideoplayer.databinding.FragmentAddPlaylistBinding
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.usecase.PlaylistType
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddPlaylistFragment : Fragment() {

    private var _binding: FragmentAddPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AddPlaylistViewModel by viewModels()

    private var selectedType: String = PlaylistType.M3U
    private var selectedFrequency: UpdateFrequency = UpdateFrequency.DEFAULT

    /** Opens the system file picker; the result is handed straight to the ViewModel to import. */
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onFilePicked(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupFrequencyDropdown()

        binding.buttonTypeM3u.isChecked = true
        updateTypeSpecificFields()

        binding.toggleType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedType = if (checkedId == binding.buttonTypeXtream.id) {
                PlaylistType.XTREAM
            } else {
                PlaylistType.M3U
            }
            updateTypeSpecificFields()
        }

        binding.buttonAttachFile.setOnClickListener {
            filePicker.launch("*/*")
        }

        binding.buttonRemoveFile.setOnClickListener {
            viewModel.clearAttachedFile()
        }

        binding.buttonCancel.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                type = selectedType,
                name = binding.editName.text?.toString().orEmpty(),
                url = binding.editUrl.text?.toString().orEmpty(),
                username = binding.editUsername.text?.toString(),
                password = binding.editPassword.text?.toString(),
                frequency = selectedFrequency
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isSaving.collect { saving ->
                        binding.progressBar.visibility = if (saving) View.VISIBLE else View.GONE
                        binding.buttonSave.isEnabled = !saving
                    }
                }
                launch {
                    viewModel.attachedFile.collect { file ->
                        updateAttachedFileUi(file)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is AddPlaylistEvent.Saved -> findNavController().popBackStack()
                            is AddPlaylistEvent.Error -> showError(event.message)
                        }
                    }
                }
            }
        }
    }

    /** This screen also owns the shared toolbar while visible: universal header + a back arrow.
     *  Search/View/Sort render (per the "header on every screen" rule) but are inert on this form. */
    private fun setupToolbar() {
        setupUniversalHeader(title = getString(R.string.add_playlist), showBack = true)
    }

    /** Populates the exposed dropdown from [R.array.update_frequency_options], whose order matches [UpdateFrequency]. */
    private fun setupFrequencyDropdown() {
        val options = resources.getStringArray(R.array.update_frequency_options)
        binding.dropdownFrequency.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)
        )
        binding.dropdownFrequency.setText(options[selectedFrequency.ordinal], false)
        binding.dropdownFrequency.setOnItemClickListener { _, _, position, _ ->
            selectedFrequency = UpdateFrequency.entries[position]
        }
    }

    private fun updateTypeSpecificFields() {
        val isXtream = selectedType == PlaylistType.XTREAM
        binding.layoutUsername.visibility = if (isXtream) View.VISIBLE else View.GONE
        binding.layoutPassword.visibility = if (isXtream) View.VISIBLE else View.GONE
        binding.layoutUrl.hint = getString(R.string.playlist_url)

        // File attachment only makes sense for a plain M3U source; Xtream always needs
        // server URL + credentials, so hide the "or attach a file" option in that mode.
        binding.textOr.visibility = if (isXtream) View.GONE else View.VISIBLE
        binding.buttonAttachFile.visibility = if (isXtream) View.GONE else View.VISIBLE
        if (isXtream) {
            binding.layoutSelectedFile.visibility = View.GONE
            viewModel.clearAttachedFile()
        }
    }

    /** Shows the selected-file chip and disables the URL field while a file is attached, and vice versa. */
    private fun updateAttachedFileUi(file: ImportedFile?) {
        val hasFile = file != null
        binding.layoutSelectedFile.visibility = if (hasFile) View.VISIBLE else View.GONE
        binding.textFileName.text = file?.displayName.orEmpty()
        binding.buttonAttachFile.visibility =
            if (hasFile || selectedType == PlaylistType.XTREAM) View.GONE else View.VISIBLE
        binding.layoutUrl.isEnabled = !hasFile
        binding.editUrl.isEnabled = !hasFile
        if (hasFile) {
            binding.editUrl.text?.clear()
        }
    }

    /** [key] is either a plain message or one of our known string-resource keys. */
    private fun showError(key: String) {
        val message = when (key) {
            "error_name_required" -> getString(R.string.error_name_required)
            "error_url_required" -> getString(R.string.error_url_required)
            "error_credentials_required" -> getString(R.string.error_credentials_required)
            else -> key
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
