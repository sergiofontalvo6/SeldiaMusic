package com.seldia.music.playback

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.seldia.music.R
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import com.seldia.music.data.network.NetworkConnectivityManager
import com.seldia.music.data.repository.DownloadMediaInfo
import com.seldia.music.data.repository.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Formato con el que el usuario quiere guardar la pista. */
enum class DownloadFormat { AUDIO, VIDEO }

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    object Preparing : DownloadStatus()
    data class Downloading(val progress: Int) : DownloadStatus()
    data class Completed(val uri: Uri) : DownloadStatus()
    data class Failed(val reason: String? = null) : DownloadStatus()
}

/**
 * Descargas multimedia guardadas mediante MediaStore.
 *
 * - API 29+: se usa RELATIVE_PATH + IS_PENDING, sin WRITE_EXTERNAL_STORAGE.
 * - API 27-28: MediaStore exige la columna DATA y el permiso WRITE_EXTERNAL_STORAGE
 *   (se solicita en tiempo de ejecución antes de llamar a [download]).
 *
 * La transferencia se hace en Dispatchers.IO escribiendo directamente en el
 * OutputStream devuelto por el ContentResolver, sin rutas absolutas manuales
 * en versiones modernas de Android.
 */
class MediaDownloadManager(
    private val context: Context,
    private val youtubeRepository: YouTubeRepository,
    private val network: NetworkConnectivityManager? = null,
    private val onAudioDownloaded: (suspend (Track, Uri) -> Unit)? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _downloadStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadStatus>> = _downloadStates.asStateFlow()

    private data class DownloadRequest(
        val track: Track,
        val format: DownloadFormat,
        val fromAutoDownload: Boolean
    )

    private val downloadQueue = ArrayDeque<DownloadRequest>()
    private val queuedKeys = mutableSetOf<String>()
    private var drainingQueue = false

    /**
     * Descarga automática: SIEMPRE solo el flujo de audio (M4A/WEBM), nunca vídeo.
     * Se usa cuando la opción "Auto-descarga" está activada o en modo sin conexión.
     */
    fun autoDownloadAudio(track: Track) {
        download(track, DownloadFormat.AUDIO, fromAutoDownload = true)
    }

    /** Encola varias pistas para descargarlas secuencialmente, una por una. */
    fun enqueueAll(tracks: List<Track>, format: DownloadFormat) {
        tracks.forEach { download(it, format, fromAutoDownload = false) }
    }

    fun download(track: Track, format: DownloadFormat, fromAutoDownload: Boolean = false) {
        if (track.source == Source.LOCAL) {
            if (!fromAutoDownload) toast(R.string.download_already_local)
            return
        }

        // Optimización de almacenamiento: sin conexión (o descarga automática)
        // se procesa únicamente el flujo de audio, omitiendo el vídeo.
        var effectiveFormat = format
        val offline = network?.isCurrentlyOnline() == false
        if (effectiveFormat == DownloadFormat.VIDEO && offline) {
            effectiveFormat = DownloadFormat.AUDIO
            toast(R.string.download_offline_audio_only)
        }
        if (fromAutoDownload) effectiveFormat = DownloadFormat.AUDIO

        val key = track.key
        val current = _downloadStates.value[key]
        if (current is DownloadStatus.Preparing || current is DownloadStatus.Downloading) {
            return
        }

        if (!hasLegacyWritePermission()) {
            _downloadStates.update {
                it + (key to DownloadStatus.Failed("Permiso de almacenamiento denegado"))
            }
            toast(R.string.download_permission_denied)
            return
        }

        synchronized(this) {
            if (!queuedKeys.add(key)) return
            downloadQueue.addLast(DownloadRequest(track, effectiveFormat, fromAutoDownload))
        }
        _downloadStates.update { it + (key to DownloadStatus.Preparing) }
        drainQueue()
    }

    private fun drainQueue() {
        val shouldStart = synchronized(this) {
            if (drainingQueue) false else {
                drainingQueue = true
                true
            }
        }
        if (!shouldStart) return

        scope.launch {
            try {
                while (true) {
                    val request = synchronized(this@MediaDownloadManager) {
                        downloadQueue.removeFirstOrNull()
                    } ?: break
                    try {
                        runDownload(request)
                    } finally {
                        synchronized(this@MediaDownloadManager) {
                            queuedKeys.remove(request.track.key)
                        }
                    }
                }
            } finally {
                val hasMore = synchronized(this@MediaDownloadManager) {
                    drainingQueue = false
                    downloadQueue.isNotEmpty()
                }
                if (hasMore) drainQueue()
            }
        }
    }

    private suspend fun runDownload(request: DownloadRequest) {
        val track = request.track
        val effectiveFormat = request.format
        val key = track.key

        if (!request.fromAutoDownload) {
            toast(context.getString(R.string.download_starting, track.title))
        } else {
            // Auto-descarga: indicar inicio sin ser intrusivo (el manager ya controla cola).
            toast(context.getString(R.string.download_starting, track.title))
        }

        val result = runCatching {
            val media = withContext(Dispatchers.IO) {
                val videoId = track.videoId ?: track.id
                youtubeRepository
                    .getDownloadMedia(videoId, video = effectiveFormat == DownloadFormat.VIDEO)
                    .getOrThrow()
            }

            withContext(Dispatchers.IO) {
                saveToMediaStore(track, media, effectiveFormat) { progress ->
                    _downloadStates.update {
                        it + (key to DownloadStatus.Downloading(progress))
                    }
                }
            }
        }

        result.onSuccess { uri ->
            Log.i(TAG, "Descarga completada: $uri (${effectiveFormat.name})")
            _downloadStates.update { it + (key to DownloadStatus.Completed(uri)) }
            if (effectiveFormat == DownloadFormat.AUDIO) {
                // Vincula la pista con el archivo local en Room (disponibilidad offline).
                runCatching { onAudioDownloaded?.invoke(track, uri) }
                    .onFailure { Log.w(TAG, "No se pudo enlazar la descarga en Room", it) }
            }
            toast(context.getString(R.string.download_saved, track.title))
        }.onFailure { error ->
            Log.e(TAG, "Error descargando \"${track.title}\" (${effectiveFormat.name})", error)
            _downloadStates.update {
                it + (key to DownloadStatus.Failed(error.message))
            }
            toast(R.string.download_failed)
        }
    }

    /**
     * Inserta la fila en MediaStore, descarga el flujo y lo escribe en el
     * OutputStream del ContentResolver. Si algo falla se elimina la fila
     * pendiente para no dejar archivos corruptos visibles.
     */
    private fun saveToMediaStore(
        track: Track,
        media: DownloadMediaInfo,
        format: DownloadFormat,
        onProgress: (Int) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val isVideo = format == DownloadFormat.VIDEO
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val fileName = buildSafeFileName(track.artist, track.title, media.extension)
        val relativeDir = buildString {
            append(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC)
            append("/SeldiaMusic")
        }
        var legacyPath: String? = null

        val targetDirName = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        // En API < 29 la columna DATA debe apuntar a una ruta libre; si ya existe
        // un archivo con el mismo nombre (de una descarga interrumpida o previa),
        // el insert se rechaza. Se busca un nombre libre sin borrar nada.
        val legacyTarget: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            null
        } else {
            @Suppress("DEPRECATION")
            val publicDir = Environment.getExternalStoragePublicDirectory(targetDirName)
            uniqueLegacyTarget(File(publicDir, "SeldiaMusic"), fileName, isVideo)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, legacyTarget?.name ?: fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.TITLE, track.title)

            if (isVideo) {
                put(MediaStore.Video.Media.DESCRIPTION, track.artist)
            } else {
                put(MediaStore.Audio.Media.ARTIST, track.artist)
                put(MediaStore.Audio.Media.ALBUM, track.album)
                // Marca explícita para que la biblioteca offline lo liste como música.
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                if (track.durationMs > 0L) {
                    put(MediaStore.Audio.Media.DURATION, track.durationMs)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val target = legacyTarget
                    ?: throw IOException("No se pudo preparar la ruta de destino")
                target.parentFile?.mkdirs()
                legacyPath = target.absolutePath
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore no pudo crear la fila para \"$fileName\"")

        try {
            downloadWithRetries(media.url, uri, onProgress)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            return uri
        } catch (t: Throwable) {
            // En API 27-28 resolver.delete() no siempre elimina el archivo físico,
            // así que se borra también la ruta DATA para no dejar restos corruptos.
            runCatching { resolver.delete(uri, null, null) }
            legacyPath?.let { path -> runCatching { File(path).delete() } }
            throw t
        }
    }

    /**
     * Descarga el flujo por bloques explícitos usando la cabecera Range.
     *
     * Los CDN de YouTube entregan un ráfaga inicial y luego estrangulan la
     * conexión. Si se pide un rango cerrado (p. ej. 1 MB) el servidor termina
     * la respuesta al llegar al límite y se abre una conexión nueva para el
     * bloque siguiente, evitando esperar el timeout de lectura. Si un bloque
     * falla, se reintenta desde el byte ya escrito.
     */
    private fun downloadWithRetries(
        url: String,
        uri: Uri,
        onProgress: (Int) -> Unit
    ) {
        val resolver = context.contentResolver
        var downloaded = 0L
        var totalBytes = -1L
        var lastError: Throwable? = null
        var stalledAttempts = 0
        var requests = 0

        while (requests < MAX_TOTAL_REQUESTS) {
            requests++
            val chunkStart = downloaded
            val chunkEnd = if (totalBytes > 0) {
                (chunkStart + CHUNK_BYTES - 1).coerceAtMost(totalBytes - 1)
            } else {
                chunkStart + CHUNK_BYTES - 1
            }

            var connection: HttpURLConnection? = null
            var completed = false
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Referer", "https://www.youtube.com/")
                    setRequestProperty("Origin", "https://www.youtube.com")
                    setRequestProperty("Range", "bytes=$chunkStart-$chunkEnd")
                    connect()
                }

                val code = connection.responseCode
                val partial = code == HttpURLConnection.HTTP_PARTIAL

                if (code == HttpURLConnection.HTTP_OK && chunkStart > 0) {
                    // El servidor ignoró el Range: hay que reescribir desde cero.
                    Log.w(TAG, "El servidor ignoró Range; reiniciando descarga")
                    val fullLength = connection.contentLengthLong
                    if (fullLength > 0) totalBytes = fullLength
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        connection.inputStream.use { input ->
                            downloaded = copyStream(input, output, 0L, totalBytes, onProgress)
                        }
                    } ?: throw IOException("MediaStore no devolvió un OutputStream")
                    completed = true
                } else if (code == HttpURLConnection.HTTP_OK || partial) {
                    if (partial) {
                        parseTotalBytes(connection.getHeaderField("Content-Range"))?.let {
                            totalBytes = it
                        }
                    } else {
                        val fullLength = connection.contentLengthLong
                        if (fullLength > 0) totalBytes = fullLength
                    }

                    val chunkLength = connection.contentLengthLong
                    if (partial && chunkLength > 0 && totalBytes <= 0) {
                        totalBytes = chunkStart + chunkLength
                    }

                    val mode = if (chunkStart > 0) "wa" else "wt"
                    resolver.openOutputStream(uri, mode)?.use { output ->
                        connection.inputStream.use { input ->
                            downloaded = copyStream(
                                input = input,
                                output = output,
                                downloaded = chunkStart,
                                totalBytes = totalBytes,
                                onProgress = onProgress
                            )
                        }
                    } ?: throw IOException("MediaStore no devolvió un OutputStream")

                    completed = when {
                        totalBytes > 0 -> downloaded >= totalBytes
                        // Rango cerrado: si el servidor devolvió menos de lo pedido, era el final.
                        chunkLength in 0 until CHUNK_BYTES -> true
                        else -> false
                    }
                } else {
                    throw com.seldia.music.data.network.NetworkError.toApiException(
                        java.io.IOException("HTTP $code al descargar ${url.take(120)}")
                    )
                }
            } catch (t: Throwable) {
                lastError = t
                Log.w(
                    TAG,
                    "Petición $requests falló tras $downloaded bytes: ${t.message}"
                )
            } finally {
                connection?.disconnect()
            }

            if (completed) return

            if (downloaded > chunkStart) {
                stalledAttempts = 0
            } else {
                stalledAttempts++
                if (stalledAttempts >= MAX_STALLED_ATTEMPTS) break
                runCatching { Thread.sleep(1_000L * stalledAttempts) }
            }
        }

        throw IOException(
            "No se pudo completar la descarga (${downloaded.coerceAtLeast(0)} bytes)",
            lastError
        )
    }

    /** Extrae el tamaño total de una cabecera `Content-Range: bytes 0-9/1234`. */
    private fun parseTotalBytes(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        return runCatching {
            CONTENT_RANGE_TOTAL.find(contentRange)?.groupValues?.get(1)?.toLong()
        }.getOrNull()
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        downloaded: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var written = downloaded
        var lastProgress = Int.MIN_VALUE

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read

            val progress = if (totalBytes > 0) {
                ((written * 100) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                -1
            }
            if (progress != lastProgress) {
                lastProgress = progress
                onProgress(progress)
            }
        }
        output.flush()
        return written
    }

    /** En API <= 28 MediaStore público todavía exige este permiso. */
    private fun hasLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun buildSafeFileName(artist: String, title: String, ext: String): String {
        val raw = if (artist.isNotBlank() && !title.contains(artist, ignoreCase = true)) {
            "$artist - $title"
        } else {
            title
        }
        val clean = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "seldia_track_${System.currentTimeMillis()}" }
        return "$clean.$ext"
    }

    /**
     * Devuelve un [File] cuyo nombre no choca con un archivo en disco ni con una
     * fila de MediaStore. Si el destino ya existe se añade un sufijo ` (1)`, `(2)`, …
     * para no sobrescribir música ya descargada ni provocar un insert rechazado.
     */
    private fun uniqueLegacyTarget(directory: File, fileName: String, isVideo: Boolean): File {
        val index = fileName.lastIndexOf('.')
        val stem = if (index > 0) fileName.substring(0, index) else fileName
        val extension = if (index > 0) fileName.substring(index) else ""

        var candidate = File(directory, fileName)
        var suffix = 1
        while (candidate.exists() || mediaStoreRowExists(candidate, isVideo)) {
            candidate = File(directory, "$stem ($suffix)$extension")
            suffix++
            if (suffix > 100) {
                candidate = File(directory, "${stem}_${System.currentTimeMillis()}$extension")
                break
            }
        }
        return candidate
    }

    /** Consulta si ya existe una fila en MediaStore apuntando a la ruta `path`. */
    private fun mediaStoreRowExists(file: File, isVideo: Boolean): Boolean = runCatching {
        val path = file.absolutePath
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null
        )?.use { it.count > 0 } == true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "MediaDownloadManager"
        const val CHUNK_BYTES = 1L * 1024 * 1024
        const val MAX_STALLED_ATTEMPTS = 5
        const val MAX_TOTAL_REQUESTS = 300
        val CONTENT_RANGE_TOTAL = Regex("""/(\d+)\s*$""")
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
