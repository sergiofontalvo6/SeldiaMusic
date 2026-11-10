package com.seldia.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import com.seldia.music.data.network.NetworkConnectivityManager
import com.seldia.music.data.repository.LibraryRepository
import com.seldia.music.data.repository.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val videoMode: Boolean = false
)

class MusicPlayer(
    private val context: Context,
    private val youtubeRepository: YouTubeRepository,
    private val libraryRepository: LibraryRepository,
    private val network: NetworkConnectivityManager? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionJob: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Flujo independiente de posición (cada 500 ms). Evita recalcular UI de estado. */
    private val _position = MutableStateFlow(0L)
    val positionFlow: StateFlow<Long> = _position.asStateFlow()
    private var lastPositionMs = 0L

    private var queue: MutableList<Track> = mutableListOf()
    private var currentIndex = -1
    private var shuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var videoMode = false

    private var streamCandidates: List<String> = emptyList()
    private var candidateIndex = 0

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "Estado de reproducción: ${playbackStateName(playbackState)}")
            if (playbackState == Player.STATE_ENDED) onTrackEnded()
            publish()
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            Log.d(TAG, "Buffering/carga: $isLoading")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "isPlaying=$isPlaying")
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "Transición de pista reason=$reason mediaId=${mediaItem?.mediaId}")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "Tamaño de vídeo: ${videoSize.width}x${videoSize.height}")
        }

        override fun onRenderedFirstFrame() {
            Log.d(TAG, "Primer frame de vídeo renderizado")
        }

        override fun onPlayerError(error: PlaybackException) {
            val track = queue.getOrNull(currentIndex)
            val failedUrl = streamCandidates.getOrNull(candidateIndex)
            Log.e(
                TAG,
                "PlaybackException code=${error.errorCode} (${error.errorCodeName}) " +
                    "track=\"${track?.title}\" " +
                    "candidata=${candidateIndex + 1}/${streamCandidates.size} " +
                    "url=${failedUrl?.take(160)}",
                error
            )
            error.cause?.let {
                Log.e(TAG, "Causa: ${it.javaClass.simpleName}: ${it.message}", it)
            }
            if (!tryNextCandidate()) {
                if (videoMode) {
                    Log.w(TAG, "Fallo total del modo vídeo; volviendo a audio")
                    videoMode = false
                    Toast.makeText(
                        context,
                        "Vídeo no disponible; reproduciendo solo audio",
                        Toast.LENGTH_LONG
                    ).show()
                    prepareAndPlayCurrent(
                        startPositionMs = player.currentPosition.coerceAtLeast(0L),
                        recordHistory = false
                    )
                } else {
                    val title = track?.title.orEmpty()
                    Toast.makeText(context, "Error al reproducir \"$title\"", Toast.LENGTH_LONG).show()
                }
            }
            publish()
        }
    }

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(STREAM_USER_AGENT)
        .setDefaultRequestProperties(
            mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com"
            )
        )
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, httpDataSourceFactory)
            )
        )
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        addListener(playerListener)
    }

    init {
        publish()
    }

    // ---- API pública ----

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue = tracks.toMutableList()
        currentIndex = startIndex.coerceIn(0, queue.size - 1)
        ensureServiceStarted()
        prepareAndPlayCurrent()
    }

    fun addToQueue(track: Track) {
        queue.add(track)
        publish()
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        if (index == currentIndex) {
            player.stop()
            player.clearMediaItems()
        }
        queue.removeAt(index)
        if (index < currentIndex) currentIndex--
        if (currentIndex >= queue.size) currentIndex = queue.size - 1
        publish()
    }

    /** Reordena la cola tras drag & drop (ajusta el índice actual sin interrumpir la reproducción). */
    fun reorderQueue(orderedTracks: List<Track>) {
        if (orderedTracks.size != queue.size) return
        val current = queue.getOrNull(currentIndex)
        val keys = orderedTracks.map { it.key }
        queue = orderedTracks.toMutableList()
        currentIndex = current?.let { keys.indexOf(it.key) } ?: -1
        publish()
    }

    fun playAt(index: Int) {
        if (index < 0 || index >= queue.size) return
        currentIndex = index
        ensureServiceStarted()
        prepareAndPlayCurrent()
    }

    fun toggle() {
        if (player.mediaItemCount == 0) {
            queue.getOrNull(currentIndex)?.let {
                ensureServiceStarted()
                prepareAndPlayCurrent()
            }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        if (queue.isEmpty()) return
        val nextIndex = if (shuffleEnabled) randomIndex() else (currentIndex + 1) % queue.size
        currentIndex = nextIndex
        ensureServiceStarted()
        prepareAndPlayCurrent()
    }

    /**
     * Pista anterior. Por defecto, si llevamos más de 3 s reproduciendo, vuelve al inicio
     * (comportamiento habitual de la UI). Con [forceSkip] = true salta siempre a la pista
     * anterior, que es lo que se espera de un mando físico del volante.
     */
    fun previous(forceSkip: Boolean = false) {
        if (queue.isEmpty()) return
        if (!forceSkip && player.currentPosition > 3000) {
            player.seekTo(0)
            return
        }
        val prevIndex = if (shuffleEnabled) randomIndex() else (currentIndex - 1 + queue.size) % queue.size
        currentIndex = prevIndex
        ensureServiceStarted()
        prepareAndPlayCurrent()
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0))
    }

    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        player.repeatMode =
            if (mode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        publish()
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        publish()
    }

    /** Activa/desactiva la reproducción aleatoria de forma explícita. */
    fun setShuffleEnabled(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        shuffleEnabled = enabled
        publish()
    }

    /**
     * Cambia entre audio y vídeo, ambos con ExoPlayer. El vídeo solo está disponible para
     * pistas de YouTube y continúa desde la posición actual.
     */
    fun setVideoMode(enabled: Boolean) {
        if (enabled == videoMode) return
        val track = queue.getOrNull(currentIndex) ?: return
        if (enabled && track.source != Source.YOUTUBE) return
        val resumeMs = player.currentPosition.coerceAtLeast(0L)
        videoMode = enabled
        prepareAndPlayCurrent(startPositionMs = resumeMs, recordHistory = false)
    }

    // ---- Internos ----

    private fun randomIndex(): Int {
        if (queue.size <= 1) return 0
        var idx = Random.nextInt(queue.size)
        if (idx == currentIndex) idx = (idx + 1) % queue.size
        return idx
    }

    private fun onTrackEnded() {
        when (repeatMode) {
            Player.REPEAT_MODE_ONE -> Unit
            Player.REPEAT_MODE_ALL -> {
                val next = if (shuffleEnabled) randomIndex() else (currentIndex + 1) % queue.size
                currentIndex = next
                prepareAndPlayCurrent()
            }
            else -> {
                if (currentIndex < queue.size - 1) {
                    currentIndex++
                    prepareAndPlayCurrent()
                } else {
                    player.pause()
                    player.seekTo(0)
                    publish()
                }
            }
        }
    }

    private fun prepareAndPlayCurrent(startPositionMs: Long = 0L, recordHistory: Boolean = true) {
        val track = queue.getOrNull(currentIndex) ?: return
        if (videoMode && track.source != Source.YOUTUBE) videoMode = false
        val requestedVideo = videoMode
        scope.launch {
            try {
                var candidates = resolveUrls(track, requestedVideo)
                Log.d(
                    TAG,
                    "Resueltas ${candidates.size} candidatas para \"${track.title}\" " +
                        "(modo=${if (requestedVideo) "vídeo" else "audio"})"
                )
                if (requestedVideo != videoMode) return@launch
                if (candidates.isEmpty() && requestedVideo) {
                    videoMode = false
                    publish()
                    Toast.makeText(context, "Sin vídeo disponible; se reproduce solo el audio", Toast.LENGTH_LONG).show()
                    candidates = resolveUrls(track, false)
                }
                if (candidates.isEmpty()) {
                    Log.e(TAG, "No se pudo resolver ninguna URL para \"${track.title}\"")
                    val offline = network?.isCurrentlyOnline() == false
                    val msg = if (offline) {
                        "Sin conexión · descarga \"${track.title}\" para escucharla offline"
                    } else {
                        "No se pudo obtener el audio de \"${track.title}\""
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    publish()
                    return@launch
                }

                streamCandidates = candidates
                candidateIndex = 0
                loadCandidate(track, startPositionMs)
                if (recordHistory) libraryRepository.addToHistory(track)
                startPositionUpdates()
                publish()
            } catch (e: Exception) {
                Log.e(TAG, "Error preparando \"${track.title}\"", e)
                publish()
            }
        }
    }

    private fun loadCandidate(track: Track, startPositionMs: Long) {
        val url = streamCandidates[candidateIndex]
        Log.d(
            TAG,
            "Cargando candidata ${candidateIndex + 1}/${streamCandidates.size} " +
                "modo=${if (videoMode) "vídeo" else "audio"} desde ${startPositionMs}ms"
        )
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(track.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artworkUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem, startPositionMs)
        player.prepare()
        player.play()
    }

    private suspend fun resolveUrls(track: Track, video: Boolean): List<String> = runCatching {
        when (track.source) {
            Source.LOCAL -> listOfNotNull(track.uri)
            Source.YOUTUBE -> {
                // 1) Si la pista ya está descargada (uri enlazada en Room), reproduce el archivo local.
                val local = track.uri?.takeIf { it.isNotBlank() }
                    ?: runCatching { libraryRepository.findDownloadedUri(track.key) }.getOrNull()
                if (!local.isNullOrBlank()) return@runCatching listOf(local)
                // 2) Sin conexión: no hay streams remotos; la pista no está disponible offline.
                if (network != null && !network.isCurrentlyOnline()) {
                    Log.i(TAG, "Sin conexión y \"${track.title}\" no está descargada")
                    return@runCatching emptyList()
                }
                // 3) En línea: resuelve los flujos remotos vía NewPipe Extractor.
                val videoId = track.videoId ?: track.id
                youtubeRepository.getStreamUrls(videoId, video).getOrDefault(emptyList())
            }
        }
    }.getOrDefault(emptyList())

    /** Si una URL de streaming falla (p. ej. 403), prueba la siguiente candidata. */
    private fun tryNextCandidate(): Boolean {
        if (candidateIndex + 1 >= streamCandidates.size) return false
        val track = queue.getOrNull(currentIndex) ?: return false
        val position = player.currentPosition.coerceAtLeast(0L)
        candidateIndex++
        Log.w(TAG, "Reintentando con la URL candidata ${candidateIndex + 1}/${streamCandidates.size}")
        loadCandidate(track, startPositionMs = position)
        return true
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                lastPositionMs = if (player.duration > 0) player.currentPosition else 0L
                _position.value = lastPositionMs
                delay(500)
            }
        }
    }

    private fun publish() {
        val current = queue.getOrNull(currentIndex)
        val duration = player.duration.takeIf { it > 0 } ?: current?.durationMs ?: 0L
        _state.value = PlayerState(
            currentTrack = current,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = lastPositionMs,
            durationMs = duration,
            queue = queue.toList(),
            currentIndex = currentIndex,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            videoMode = videoMode
        )
    }

    /** Cancelación explícita del scope (app-scoped: llamar desde el dueño al destruir). */
    fun close() {
        positionJob?.cancel()
        scope.cancel()
    }

    private fun ensureServiceStarted() {
        try {
            val intent = Intent(context, PlaybackService::class.java)
            // Usamos startService en lugar de startForegroundService para evitar
            // el bloqueo de tiempo mientras YouTube resuelve la URL por red.
            context.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private companion object {
        const val TAG = "MusicPlayer"
        const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun playbackStateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "DESCONOCIDO($state)"
        }
    }
}
