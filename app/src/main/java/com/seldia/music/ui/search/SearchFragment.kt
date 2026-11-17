package com.seldia.music.ui.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.seldia.music.R
import com.seldia.music.databinding.FragmentSearchBinding
import com.seldia.music.ui.SearchViewModel
import com.seldia.music.ui.TrackListFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Búsqueda unificada: consulta directa a YouTube (NewPipe Extractor) cuando hay
 * conexión y conmutación transparente a la biblioteca offline cuando no la hay.
 */
class SearchFragment : TrackListFragment() {

    companion object {
        private const val ARG_QUERY = "query"
        fun newInstance(query: String): SearchFragment = SearchFragment().apply {
            arguments = android.os.Bundle().apply { putString(ARG_QUERY, query) }
        }
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val searchVm: SearchViewModel by activityViewModels()

    private var searchJob: Job? = null

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                binding.etSearch.setText(spoken)
                binding.etSearch.setSelection(spoken.length)
                doSearch(spoken)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = makeAdapter()
        attachDragAndDrop(binding.recycler)

        binding.btnVoice.setOnClickListener { startVoiceSearch() }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding.etSearch.text.toString())
                true
            } else {
                false
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(400)
                    val q = s?.toString().orEmpty().trim()
                    if (q.isBlank()) searchVm.clear() else doSearch(q)
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        arguments?.getString("query")?.takeIf { it.isNotBlank() }?.let { q ->
            binding.etSearch.setText(q)
            binding.etSearch.setSelection(q.length)
        }

        searchVm.results.observe(viewLifecycleOwner) { results ->
            adapter.submit(results)
            binding.empty.visibility =
                if (results.isEmpty() && searchVm.loading.value != true) View.VISIBLE else View.GONE
        }
        searchVm.loading.observe(viewLifecycleOwner) { loading ->
            binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        }
        searchVm.message.observe(viewLifecycleOwner) { message ->
            binding.tvMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvMessage.text = message ?: ""
        }
        searchVm.offlineMode.observe(viewLifecycleOwner) { offline ->
            binding.tvOfflineBadge.visibility = if (offline) View.VISIBLE else View.GONE
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.voice_unavailable))
        }
    }

    private fun doSearch(query: String) {
        // La búsqueda offline lee MediaStore: requiere el permiso de audio.
        if (!container.networkConnectivityManager.isCurrentlyOnline()) {
            requestStoragePermission { searchVm.search(query) }
        } else {
            searchVm.search(query)
        }
    }

    override fun onDestroyView() {
        runCatching { finishSelectionMode() }
        super.onDestroyView()
        _binding = null
    }
}
