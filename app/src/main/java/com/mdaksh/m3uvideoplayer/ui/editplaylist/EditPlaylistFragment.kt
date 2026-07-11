package com.mdaksh.m3uvideoplayer.ui.editplaylist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.FragmentAddPlaylistBinding
import com.mdaksh.m3uvideoplayer.domain.model.Playlist
import com.mdaksh.m3uvideoplayer.domain.model.UpdateFrequency
import com.mdaksh.m3uvideoplayer.domain.usecase.PlaylistType
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Settings ➔ Playlists ➔ (tap a playlist). Reuses the exact "Add Playlist" layout so the two
 * screens stay visually identical, but only exposes name / URL / update frequency (plus Xtream
 * credentials, since they're part of the URL) as editable, and adds a "Remove Playlist" action.
 */
@AndroidEntryPoint
class EditPlaylistFragment : Fragment() {

    private var _binding: FragmentAddPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: EditPlaylistViewModel by viewModels()
    private val args: EditPlaylistFragmentArgs by navArgs()

    private var selectedFrequency: UpdateFrequency = UpdateFrequency.DEFAULT
    private var hasPopulatedFields = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        hideSourceAttachmentUi()
        binding.buttonRemovePlaylist.visibility = View.VISIBLE

        binding.buttonCancel.setOnClickListener { findNavController().popBackStack() }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                name = binding.editName.text?.toString().orEmpty(),
                url = binding.editUrl.text?.toString().orEmpty(),
                username = binding.editUsername.text?.toString(),
                password = binding.editPassword.text?.toString(),
                frequency = selectedFrequency
            )
        }

        binding.buttonRemovePlaylist.setOnClickListener { confirmRemove() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isSaving.collect { saving ->
                        binding.progressBar.visibility = if (saving) View.VISIBLE else View.GONE
                        binding.buttonSave.isEnabled = !saving
                        binding.buttonRemovePlaylist.isEnabled = !saving
                    }
                }
                launch {
                    viewModel.playlist.collect { playlist ->
                        if (playlist != null && !hasPopulatedFields) {
                            populateFields(playlist)
                            hasPopulatedFields = true
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is EditPlaylistEvent.Saved -> {
                                Toast.makeText(requireContext(), R.string.playlist_updated, Toast.LENGTH_SHORT).show()
                                findNavController().popBackStack()
                            }
                            is EditPlaylistEvent.Removed -> {
                                Toast.makeText(requireContext(), R.string.playlist_removed, Toast.LENGTH_SHORT).show()
                                findNavController().popBackStack()
                            }
                            is EditPlaylistEvent.Error -> showError(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        setupUniversalHeader(title = getString(R.string.edit_playlist), showBack = true)
    }

    /** File re-attachment isn't part of editing; only the URL / credentials / name / frequency are. */
    private fun hideSourceAttachmentUi() {
        binding.textOr.visibility = View.GONE
        binding.buttonAttachFile.visibility = View.GONE
        binding.layoutSelectedFile.visibility = View.GONE
    }

    private fun populateFields(playlist: Playlist) {
        val source = viewModel.editableSource(playlist)

        val isXtream = playlist.type == PlaylistType.XTREAM
        binding.toggleType.visibility = View.GONE
        binding.layoutUsername.visibility = if (isXtream) View.VISIBLE else View.GONE
        binding.layoutPassword.visibility = if (isXtream) View.VISIBLE else View.GONE

        binding.editName.setText(playlist.name)
        binding.editUrl.setText(source.url)
        binding.editUsername.setText(source.username.orEmpty())
        binding.editPassword.setText(source.password.orEmpty())

        setupFrequencyDropdown(UpdateFrequency.fromName(playlist.updateFrequency))
    }

    private fun setupFrequencyDropdown(initial: UpdateFrequency) {
        selectedFrequency = initial
        val options = resources.getStringArray(R.array.update_frequency_options)
        binding.dropdownFrequency.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)
        )
        binding.dropdownFrequency.setText(options[selectedFrequency.ordinal], false)
        binding.dropdownFrequency.setOnItemClickListener { _, _, position, _ ->
            selectedFrequency = UpdateFrequency.entries[position]
        }
    }

    private fun confirmRemove() {
        val name = binding.editName.text?.toString().orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_playlist_confirm_title)
            .setMessage(getString(R.string.remove_playlist_confirm_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> viewModel.remove() }
            .show()
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
