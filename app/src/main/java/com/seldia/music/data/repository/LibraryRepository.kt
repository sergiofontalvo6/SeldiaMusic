package com.seldia.music.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.room.withTransaction
import com.seldia.music.data.db.AppDatabase
import com.seldia.music.data.db.PlaylistEntity
import com.seldia.music.data.db.PlaylistTrackCrossRef
import com.seldia.music.data.db.toEntity
import com.seldia.music.data.db.toHistoryEntity
import com.seldia.music.data.db.toTrack
import com.seldia.music.data.model.Playlist
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import kotlinx.coroutines.launch

/** Repositorio de la biblioteca offline (favoritos, playlists e historial). */
class LibraryRepository(private val db: AppDatabase) {

    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val historyDao = db.historyDao()

    private val repairScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    init {
        // Reparación única de filas enlazadas: copia metadatos YouTube a filas
        // "LOCAL|id" que comparten URI con una fila YOUTUBE (duración 0:00 y
        // carátula ausente → miniatura real). Se ejecuta en segundo plano.
        repairScope.launch { runCatching { healAllLinkedRows() } }
    }

    private suspend fun healAllLinkedRows() {
        val rows = trackDao.getWithUri()
        rows.groupBy { it.uri!! }.values.forEach { group ->
            val yt = group.firstOrNull { it.source == Source.YOUTUBE.name } ?: return@forEach
            group.filter { it.source != Source.YOUTUBE.name }.forEach { local ->
                val healed = local.copy(
                    title = com.seldia.music.data.db.sanitizeTitle(yt.title),
                    artist = com.seldia.music.data.db.sanitizeArtist(yt.artist),
                    album = com.seldia.music.data.db.sanitizeAlbum(yt.album),
                    durationMs = if (yt.durationMs > 0L) yt.durationMs else local.durationMs,
                    artworkUrl = com.seldia.music.data.db.sanitizeArtworkUrl(local.artworkUrl)
                        ?: com.seldia.music.data.db.sanitizeArtworkUrl(yt.artworkUrl),
                    isFavorite = local.isFavorite || yt.isFavorite
                )
                if (healed != local) {
                    trackDao.upsert(healed)
                }
            }
        }
    }

    /**
     * Marca una pista como descargada: guarda la URI content:// del archivo
     * de audio en Room. Al compartir la misma clave en favoritos/playlists/historial,
     * las pistas descargadas quedan disponibles offline en todas las listas.
     */
    suspend fun markDownloaded(trackKey: String, uri: String) = trackDao.updateUri(trackKey, uri)

    /**
     * Single Source of Truth para estado de descarga.
     * Si [isDownloaded] es true, exige una URI (cache o MediaStore) y la guarda;
     * si es false, limpia la URI. Notifica reactivamente a todos los observadores
     * de [observeDownloadedTrackKeys] y de descargas completadas.
     */
    suspend fun updateDownloadStatus(trackId: String, isDownloaded: Boolean, uri: String? = null) {
        if (isDownloaded) {
            val finalUri = uri?.takeIf { it.isNotBlank() } ?: return
            trackDao.updateUri(trackId, finalUri)
        } else {
            trackDao.updateUri(trackId, "")
        }
    }

    /**
     * Devuelve la URI local (content://) asociada a la pista si fue descargada,
     * consultando Room. Permite que pistas encoladas desde búsqueda (que no
     * transportan la URI) sigan sonando sin conexión.
     */
    suspend fun findDownloadedUri(trackKey: String): String? =
        trackDao.getById(trackKey)?.uri?.takeIf { it.isNotBlank() }

    /**
     * Como [findDownloadedUri], pero valida que el archivo siga existiendo. Si la
     * URI almacenada ya no es accesible (p. ej. se borraron los datos/archivos de la
     * app), limpia el estado de descarga en Room para que la pista pueda volver a
     * reproducirse en streaming y descargarse.
     */
    suspend fun findExistingDownloadedUri(context: android.content.Context, trackKey: String): String? {
        val uri = findDownloadedUri(trackKey) ?: return null
        if (com.seldia.music.util.LocalMedia.exists(context, uri)) return uri
        updateDownloadStatus(trackKey, false)
        return null
    }

    /** Indica si una pista ya tiene un archivo de audio vinculado en Room (descargada). */
    suspend fun isTrackDownloaded(trackKey: String): Boolean =
        trackDao.getById(trackKey)?.uri?.isNotBlank() == true

    /** Observa las claves de todas las pistas que tienen URI local (descargadas). */
    fun observeDownloadedTrackKeys(): LiveData<Set<String>> =
        trackDao.observeDownloadedTrackKeys().map { it.toSet() }

    /**
     * Limpia en Room las referencias de descarga cuyos archivos ya no existen
     * (p. ej. tras borrar datos/archivos de la app). Permite volver a reproducir
     * por streaming y habilita la descarga de nuevo.
     */
    suspend fun purgeMissingDownloads(context: android.content.Context) {
        val rows = trackDao.getWithUri()
        rows.forEach { entity ->
            val uri = entity.uri?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!com.seldia.music.util.LocalMedia.exists(context, uri)) {
                trackDao.updateUri(entity.id, "")
                runCatching { historyDao.clearUri(uri) }
            }
        }
    }

    // ---- Favoritos ----

    fun observeFavorites(): LiveData<List<Track>> =
        trackDao.observeFavorites().map { list -> dedupeFavorites(list.map { it.toTrack() }) }

    /**
     * La misma canción descargada puede estar en Room bajo dos claves
     * (LOCAL|id de MediaStore y YOUTUBE|videoId). Si ambas están marcadas,
     * "Canciones que te gustan" mostraría duplicados: se fusionan por URI,
     * conservando la representación de YouTube (carátula y metadatos ricos).
     */
    private fun dedupeFavorites(items: List<Track>): List<Track> {
        val out = mutableListOf<Track>()
        items.filter { it.uri.isNullOrBlank() }.forEach { out.add(it) }
        items.filter { !it.uri.isNullOrBlank() }
            .groupBy { it.uri!! }
            .values
            .forEach { group ->
                out.add(group.firstOrNull { it.source == Source.YOUTUBE } ?: group.first())
            }
        return out.sortedWith(compareByDescending { it.addedAt })
    }

    suspend fun isFavorite(track: Track): Boolean {
        val uri = track.uri?.takeIf { it.isNotBlank() }
        if (uri != null) {
            val linked = trackDao.getByUri(uri)
            if (linked.isNotEmpty()) return linked.any { it.isFavorite }
        }
        return trackDao.isFavorite(track.key) ?: false
    }

    suspend fun toggleFavorite(track: Track) {
        // La misma pista puede estar guardada como LOCAL|id y YOUTUBE|videoId
        // cuando el archivo descargado se fusiona por URI. Al cambiar el
        // favorito hay que actualizar todas las representaciones para que
        // Sin conexión, la cola y el resto de listas se repinten igual.
        val uri = track.uri?.takeIf { it.isNotBlank() }
        if (uri != null) {
            val linked = trackDao.getByUri(uri)
            if (linked.isNotEmpty()) {
                val newFavorite = !linked.any { it.isFavorite }
                linked.forEach { trackDao.setFavorite(it.id, newFavorite) }
                if (linked.none { it.id == track.key }) {
                    trackDao.upsert(track.toEntity(isFavorite = newFavorite))
                }
                return
            }
        }
        toggleFavorite(track.key, track)
    }

    /** Alterna favorito por clave (Single Source of Truth): cualquier vista que observe se repinta. */
    suspend fun toggleFavorite(trackId: String, track: Track? = null) {
        val existing = trackDao.getById(trackId)
        if (existing == null) {
            val entity = (track?.toEntity(isFavorite = true)) ?: return
            trackDao.upsert(entity)
        } else {
            trackDao.setFavorite(trackId, !existing.isFavorite)
        }
    }

    // ---- Playlists ----

    /**
     * Playlists en orden manual (drag & drop): Room devuelve `sortOrder ASC`.
     * Se pasa el orden sin reordenar por reproducciones.
     */
    fun observePlaylists(): LiveData<List<Playlist>> =
        playlistDao.observePlaylists().map { list ->
            list.map { Playlist(it.id, it.name, it.createdAt, it.trackCount, it.sortOrder) }
        }

    suspend fun createPlaylist(name: String): Long {
        val nextOrder = playlistDao.maxSortOrder() + 1
        return playlistDao.insertPlaylist(PlaylistEntity(name = name, sortOrder = nextOrder))
    }

    /** Persiste el orden tras soltar el arrastre (transacción en orden de lista). */
    suspend fun reorderPlaylists(orderedIds: List<Long>) {
        db.withTransaction {
            orderedIds.forEachIndexed { index, id -> playlistDao.updateSortOrder(id, index) }
        }
    }

    suspend fun getPlaylists(): List<Playlist> =
        playlistDao.getAllPlaylists().map { Playlist(it.id, it.name, it.createdAt, it.trackCount, it.sortOrder) }

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.renamePlaylist(id, name)

    suspend fun deletePlaylist(id: Long) = playlistDao.deletePlaylist(id)

    fun observePlaylistTracks(playlistId: Long): LiveData<List<Track>> =
        playlistDao.observePlaylistTracks(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun addTrackToPlaylist(playlistId: Long, track: Track) {
        // Conserva el estado de favorito/addedAt previo: upsert REPLACE reescribiría la fila completa.
        val existing = trackDao.getById(track.key)
        trackDao.upsert(track.toEntity(isFavorite = existing?.isFavorite ?: track.isFavorite))
        val position = playlistDao.maxPosition(playlistId) + 1
        playlistDao.addTrack(PlaylistTrackCrossRef(playlistId, track.key, position))
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, track: Track) =
        playlistDao.removeTrack(playlistId, track.key)

    /** Persiste el orden manual de pistas dentro de una playlist tras el arrastre. */
    suspend fun reorderPlaylistTracks(playlistId: Long, orderedTracks: List<Track>) {
        db.withTransaction {
            orderedTracks.forEachIndexed { index, track ->
                playlistDao.updateTrackPosition(playlistId, track.key, index)
            }
        }
    }

    /**
     * Importación atómica de playlist (p. ej. desde Spotify):
     * crea la lista, guarda las pistas y las asocia en orden.
     * Sanitiza metadatos (corrección <unknown>) y preserva favoritos previos.
     */
    suspend fun importPlaylist(name: String, tracks: List<Track>): Long {
        val safeName = name.trim().takeIf {
            it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) &&
                !it.equals("unknown", ignoreCase = true)
        } ?: "Lista importada"
        val deduped = tracks
            .map { it.copy(title = com.seldia.music.data.db.sanitizeTitle(it.title), artist = com.seldia.music.data.db.sanitizeArtist(it.artist), album = com.seldia.music.data.db.sanitizeAlbum(it.album), artworkUrl = com.seldia.music.data.db.sanitizeArtworkUrl(it.artworkUrl)) }
            .distinctBy { it.key }
        var playlistId = 0L
        db.withTransaction {
            val nextOrder = playlistDao.maxSortOrder() + 1
            playlistId = playlistDao.insertPlaylist(PlaylistEntity(name = safeName, sortOrder = nextOrder))
            var position = 0
            for (track in deduped) {
                val existing = trackDao.getById(track.key)
                trackDao.upsert(
                    track.toEntity(isFavorite = existing?.isFavorite ?: track.isFavorite)
                        .copy(addedAt = if (existing != null) existing.addedAt else System.currentTimeMillis())
                )
                playlistDao.addTrack(PlaylistTrackCrossRef(playlistId, track.key, position))
                position += 1
            }
        }
        return playlistId
    }

    // ---- Historial ----

    fun observeHistory(): LiveData<List<Track>> =
        historyDao.observeRecent().map { list -> list.map { it.toTrack() } }

    suspend fun addToHistory(track: Track) {
        historyDao.insert(track.toHistoryEntity())
        val existing = trackDao.getById(track.key)
        if (existing == null) {
            trackDao.upsert(track.toEntity(isFavorite = track.isFavorite))
        }
    }

    suspend fun clearHistory() = historyDao.clear()
}
