package com.seldia.music.ui.queue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seldia.music.R
import com.seldia.music.SeldiaApp
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import com.seldia.music.databinding.FragmentQueueBinding
import com.seldia.music.playback.DownloadFormat
import com.seldia.music.ui.PlayerViewModel
import com.seldia.music.ui.LibraryViewModel
import com.seldia.music.ui.adapters.TrackTableAdapter
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!

    private val playerVm: PlayerViewModel by activityViewModels()
    private val libraryVm: LibraryViewModel by activityViewModels()

    private val container
        get() = (requireActivity().application as SeldiaApp).container

    private lateinit var adapter: TrackTableAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = TrackTableAdapter(
            onClick = { _, index -> playerVm.playAt(index) },
            onFavoriteToggle = { libraryVm.toggleFavorite(it) },
            onAddToQueue = {},
            onAddToPlaylist = {},
            onRemove = { _, index -> playerVm.removeFromQueue(index) },
            onDownload = ::downloadTrack
        )
        binding.recycler.adapter = adapter
        observeTrackStates()

        // Drag & drop en la cola: arrastra con el asidero y persiste en MusicPlayer.
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isItemViewSwipeEnabled(): Boolean = false

                override fun isLongPressDragEnabled(): Boolean = false

                override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                    super.clearView(rv, viewHolder)
                    playerVm.reorderQueue(adapter.snapshotList())
                }
            }
        )
        touchHelper.attachToRecyclerView(binding.recycler)
        adapter.onDragStartRequested = { touchHelper.startDrag(it) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerVm.state.collect { s ->
                    adapter.submit(s.queue)
                    adapter.setPlayingKey(s.currentTrack?.key)
                    val text = if (s.queue.isEmpty()) getString(R.string.queue_empty)
                    else getString(R.string.queue_count, s.queue.size)
                    binding.tvQueueInfo.text = text
                }
            }
        }
    }

    /**
     * La cola usa el mismo estado global que el resto de listas: favoritos y
     * descargas vienen de Room y la descarga en curso de MediaDownloadManager.
     */
    private fun observeTrackStates() {
        libraryVm.favorites.observe(viewLifecycleOwner) { favorites ->
            adapter.updateFavorites(favorites.map { it.key }.toSet())
        }
        libraryVm.downloadedKeys.observe(viewLifecycleOwner) { keys ->
            adapter.updateDownloadedKeys(keys)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.mediaDownloadManager.downloadStates.collect { states ->
                    adapter.updateDownloadStates(states)
                }
            }
        }
    }

    private fun downloadTrack(track: Track) {
        if (track.source == Source.LOCAL) {
            Toast.makeText(requireContext(), R.string.download_already_local, Toast.LENGTH_SHORT).show()
            return
        }
        if (!container.networkConnectivityManager.isCurrentlyOnline()) {
            Toast.makeText(requireContext(), R.string.download_offline_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val already = runCatching {
                container.libraryRepository.findExistingDownloadedUri(requireContext(), track.key)
            }.getOrNull()
            if (already != null) {
                Toast.makeText(requireContext(), R.string.download_already_local, Toast.LENGTH_SHORT).show()
            } else {
                container.mediaDownloadManager.download(track, DownloadFormat.AUDIO)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
