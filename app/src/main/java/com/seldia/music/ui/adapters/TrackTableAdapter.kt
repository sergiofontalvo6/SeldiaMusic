package com.seldia.music.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.seldia.music.R
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import com.seldia.music.databinding.ItemTrackTableBinding
import com.seldia.music.playback.DownloadStatus
import com.seldia.music.util.accentPrimaryColor
import com.seldia.music.util.formatDuration

/**
 * Adaptador de tabla de pistas para el panel central (desktop):
 * columnas # · TÍTULO · duración + favorito/descarga/menú.
 *
 * Usa [ListAdapter] con [DiffUtil] para actualizaciones parciales: los cambios
 * de favoritos/descargas/pista sonando solo re-enlazan las filas afectadas.
 */
class TrackTableAdapter(
    private val onClick: (Track, Int) -> Unit,
    private val onFavoriteToggle: (Track) -> Unit,
    private val onAddToQueue: (Track) -> Unit,
    private val onAddToPlaylist: (Track) -> Unit,
    private val onRemove: ((Track, Int) -> Unit)? = null,
    private val onDownload: ((Track) -> Unit)? = null,
    private val onDeleteLocal: ((Track) -> Unit)? = null,
    private val onRequestSelectionMode: (Track) -> Unit = {},
    private val onSelectionChanged: (Set<String>) -> Unit = {},
    private val onOrderChanged: (List<Track>) -> Unit = {}
) : ListAdapter<Track, TrackTableAdapter.VH>(TrackDiffCallback()) {

    private var favoriteKeys: Set<String> = emptySet()
    private var downloadStates: Map<String, DownloadStatus> = emptyMap()
    private var downloadedKeys: Set<String> = emptySet()
    private var playingKey: String? = null

    /** Modo de selección múltiple (checkboxes visibles). */
    var selectionMode = false
        private set
    var onDragStartRequested: (RecyclerView.ViewHolder) -> Unit = {}
    val selectedKeys: Set<String> get() = selectedSet.toSet()
    private val selectedSet = mutableSetOf<String>()

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedSet.clear()
        notifyItemRangeChanged(0, itemCount, "selection")
        onSelectionChanged(selectedSet.toSet())
    }

    fun toggleSelection(track: Track) {
        val pos = currentList.indexOfFirst { it.key == track.key }
        if (pos < 0) return
        if (!selectedSet.add(track.key)) selectedSet.remove(track.key)
        notifyItemChanged(pos)
        onSelectionChanged(selectedSet.toSet())
    }

    fun clearSelection() {
        if (selectedSet.isEmpty()) return
        selectedSet.clear()
        notifyItemRangeChanged(0, itemCount, "selection")
        onSelectionChanged(selectedSet.toSet())
    }

    fun select(track: Track) {
        if (selectedSet.add(track.key)) {
            val pos = currentList.indexOfFirst { it.key == track.key }
            if (pos >= 0) notifyItemChanged(pos)
        }
        onSelectionChanged(selectedSet.toSet())
    }

    fun submit(list: List<Track>) {
        submitList(list)
    }

    fun updateFavorites(keys: Set<String>) {
        val changed = favoriteKeys.symmetricDiff(keys)
        favoriteKeys = keys
        notifyRelatedPositions(changed)
    }

    fun updateDownloadStates(states: Map<String, DownloadStatus>) {
        val changed = (downloadStates.keys + states.keys)
            .filter { key -> downloadStates[key] != states[key] }
            .toSet()
        downloadStates = states
        notifyRelatedPositions(changed)
    }

    /** Claves de pistas ya descargadas y persistidas en Room (disponibles offline). */
    fun updateDownloadedKeys(keys: Set<String>) {
        val changed = downloadedKeys.symmetricDiff(keys)
        downloadedKeys = keys
        notifyRelatedPositions(changed)
    }

    /** Resalta la fila que está sonando. */
    fun setPlayingKey(key: String?) {
        if (playingKey == key) return
        val old = playingKey
        playingKey = key
        listOfNotNull(old, key).forEach { notifyRelatedPositions(setOf(it)) }
    }

    fun snapshotList(): List<Track> = getCurrentList().toList()

    /** Mueve un ítem durante drag & drop (posiciones locales). */
    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val list = currentList.toMutableList()
        if (fromPosition !in list.indices || toPosition !in list.indices) return
        list.add(toPosition, list.removeAt(fromPosition))
        submitList(list)
    }

    /** Persiste en el llamador tras soltar el arrastre. */
    fun publishOrderChanged() {
        onOrderChanged(snapshotList())
    }

    private fun notifyRelatedPositions(keys: Set<String>) {
        if (keys.isEmpty()) return
        // Una pista puede aparecer con otra clave (LOCAL frente a YOUTUBE) si
        // ambas representan el mismo archivo. Repinta todas las filas relacionadas,
        // no solo la primera coincidencia exacta.
        currentList.forEachIndexed { index, item ->
            if (item.key in keys || item.linkedKeys.any { it in keys }) {
                notifyItemChanged(index)
            }
        }
    }

    private fun Track.isFavoriteState(): Boolean =
        key in favoriteKeys || linkedKeys.any { it in favoriteKeys }

    private fun Track.downloadStateOrIdle(): DownloadStatus {
        downloadStates[key]?.let { return it }
        return linkedKeys.firstNotNullOfOrNull { downloadStates[it] } ?: DownloadStatus.Idle
    }

    /** True si existe una copia offline descargada y accesible registrada en Room. */
    private fun Track.hasDownloadedFile(): Boolean =
        key in downloadedKeys || linkedKeys.any { it in downloadedKeys }

    private fun Set<String>.symmetricDiff(other: Set<String>): Set<String> =
        (this - other) + (other - this)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackTableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position), position)

    override fun onBindViewHolder(
        holder: VH,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty()) {
            holder.bind(getItem(position), position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class VH(private val binding: ItemTrackTableBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, position: Int) {
            val isPlaying = track.key == playingKey
            binding.tvIndex.text = if (isPlaying) "▶" else (position + 1).toString()
            binding.tvTitle.text = track.title
            binding.tvTitle.setTextColor(
                if (isPlaying) binding.root.context.accentPrimaryColor() else ContextCompat.getColor(
                    binding.root.context, R.color.text_primary
                )
            )
            binding.tvArtist.text = track.artist
            binding.tvDuration.text = formatDuration(track.durationMs)

            // Modo selección: checkbox visible y estado de la fila.
            val selected = track.key in selectedSet
            binding.cbSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.cbSelected.isChecked = selected

            val isFav = track.isFavoriteState()
            binding.btnFavorite.setImageResource(
                if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
            )
            binding.btnFavorite.setColorFilter(
                if (isFav) binding.root.context.accentPrimaryColor() else ContextCompat.getColor(
                    binding.root.context, R.color.text_secondary
                )
            )
            binding.btnFavorite.contentDescription =
                if (isFav) "Quitar de favoritos" else "Añadir a favoritos"

            // Miniatura pequeña (128) en lugar de imagen completa 480x360.
            binding.ivArtwork.load(track.artworkUrl) {
                size(128, 128)
                placeholder(R.drawable.ic_music_note)
                error(R.drawable.ic_music_note)
                crossfade(false)
            }

            val canDownload = onDownload != null
            if (!canDownload) {
                binding.btnDownload.visibility = View.GONE
                binding.progressDownload.visibility = View.GONE
            } else {
                val status = track.downloadStateOrIdle()
                val isDownloaded = track.hasDownloadedFile()
                val isBusy = status is DownloadStatus.Preparing || status is DownloadStatus.Downloading

                binding.btnDownload.visibility = if (isBusy) View.GONE else View.VISIBLE
                binding.progressDownload.visibility = if (isBusy) View.VISIBLE else View.GONE

                // Flecha/estado en color de acento cuando la pista ya está descargada.
                binding.btnDownload.setColorFilter(
                    if (isDownloaded) binding.root.context.accentPrimaryColor() else ContextCompat.getColor(
                        binding.root.context, R.color.text_secondary
                    )
                )
            }

            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(track) else onClick(track, position)
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) {
                    onRequestSelectionMode(track)
                    true
                } else {
                    toggleSelection(track)
                    true
                }
            }
            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onDragStartRequested(this)
                }
                false
            }
            binding.btnFavorite.setOnClickListener { onFavoriteToggle(track) }
            binding.btnDownload.setOnClickListener { onDownload?.invoke(track) }
            binding.btnMore.setOnClickListener { showMenu(it, track) }
        }

        private fun showMenu(anchor: View, track: Track) {
            val popup = PopupMenu(binding.root.context, anchor)
            popup.menu.add(0, MENU_QUEUE, 0, R.string.add_to_queue)
            popup.menu.add(0, MENU_PLAYLIST, 1, R.string.add_to_playlist)
            val isFav = track.isFavoriteState()
            popup.menu.add(
                0, MENU_FAVORITE, 2,
                if (isFav) R.string.remove_favorite else R.string.add_favorite
            )
            if (onDownload != null && track.source == Source.YOUTUBE) {
                popup.menu.add(0, MENU_DOWNLOAD, 3, R.string.download_track)
            }
            if (onRemove != null) {
                popup.menu.add(0, MENU_REMOVE, 4, R.string.remove_from_playlist)
            }
            if (onDeleteLocal != null) {
                popup.menu.add(0, MENU_DELETE, 5, R.string.delete_offline)
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_QUEUE -> onAddToQueue(track)
                    MENU_PLAYLIST -> onAddToPlaylist(track)
                    MENU_FAVORITE -> onFavoriteToggle(track)
                    MENU_DOWNLOAD -> onDownload?.invoke(track)
                    MENU_REMOVE -> onRemove?.invoke(track, bindingAdapterPosition)
                    MENU_DELETE -> onDeleteLocal?.invoke(track)
                }
                true
            }
            popup.show()
        }
    }

    private companion object {
        const val MENU_QUEUE = 1
        const val MENU_PLAYLIST = 2
        const val MENU_FAVORITE = 3
        const val MENU_REMOVE = 4
        const val MENU_DOWNLOAD = 5
        const val MENU_DELETE = 6
    }

    private class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean =
            oldItem == newItem
    }
}
