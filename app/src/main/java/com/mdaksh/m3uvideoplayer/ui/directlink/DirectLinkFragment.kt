package com.mdaksh.m3uvideoplayer.ui.directlink

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.mdaksh.m3uvideoplayer.R
import com.mdaksh.m3uvideoplayer.databinding.FragmentDirectLinkBinding
import com.mdaksh.m3uvideoplayer.ui.common.setupUniversalHeader
import com.mdaksh.m3uvideoplayer.ui.player.PlayerActivity
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class DirectLinkFragment : Fragment() {

    private var _binding: FragmentDirectLinkBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DirectLinkViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDirectLinkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUniversalHeader(
            title = getString(R.string.direct_link_viewer),
            showBack = true,
            showSearch = false,
            showViewMode = false,
            showSort = false,
            onSettings = {
                findNavController().navigate(R.id.action_directLinkFragment_to_settingsFragment)
            }
        )

        binding.buttonPlay.setOnClickListener {
            val url = binding.editUrl.text?.toString().orEmpty()
            viewModel.play(url)
        }

        autoPasteUrl()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.buttonPlay.isEnabled = !loading
                        binding.layoutUrl.isEnabled = !loading
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is DirectLinkEvent.Play -> {
                                val intent = PlayerActivity.newIntent(
                                    context = requireContext(),
                                    channels = listOf(event.channel),
                                    startIndex = 0,
                                    resumePositionMs = -1L
                                )
                                startActivity(intent)
                                findNavController().popBackStack()
                            }
                            is DirectLinkEvent.Error -> {
                                val message = if (event.message == "error_url_required") {
                                    getString(R.string.error_url_required)
                                } else {
                                    event.message
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun autoPasteUrl() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val pasteData = item?.text?.toString()?.trim() ?: ""

        if (pasteData.startsWith("http://") || pasteData.startsWith("https://")) {
            binding.editUrl.setText(pasteData)
            // Optional: Move cursor to the end
            binding.editUrl.setSelection(pasteData.length)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
