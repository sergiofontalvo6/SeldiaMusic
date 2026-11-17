package com.seldia.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seldia.music.SeldiaApp
import com.seldia.music.data.model.Track
import com.seldia.music.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reproducción pedida por voz desde FiestaLauncher **sin abrir la interfaz**.
 *
 * Prioriza coincidencia local (biblioteca/descargas) para respuesta rápida; si no hay
 * match local y hay red, consulta la API con timeout corto y reproduce el primer resultado.
 */
class VoicePlaybackReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_VOICE_PLAY) return
        val query = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) return

        val app = context.applicationContext as? SeldiaApp ?: return
        val container = app.container

        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "VOICE_PLAY recibido query=\"$query\"")

        // Asegura el servicio en primer plano (si el launcher no lo arrancó antes).
        try {
            val svc = Intent(context, PlaybackService::class.java)
            svc.putExtra(PlaybackService.EXTRA_BG_PLAY, true)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo iniciar PlaybackService", t)
        }

        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val tracks = withContext(Dispatchers.IO) { buscarRapido(container, query) }
                if (tracks.isEmpty()) {
                    Log.w(TAG, "Sin resultados para \"$query\" (t=${android.os.SystemClock.elapsedRealtime() - t0}ms)")
                    return@launch
                }
                container.musicPlayer.playQueue(tracks, 0)
                Log.i(TAG, "PLAY_START request query=\"$query\" first=\"${tracks.first().title}\" t=${android.os.SystemClock.elapsedRealtime() - t0}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "Fallo la reproducción en segundo plano", t)
            } finally {
                try { pending.finish() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Prioriza coincidencia local (biblioteca/descargas/caché MediaStore) para respuesta rápida;
     * si no hay match local y hay red, consulta API con timeout corto.
     */
    private suspend fun buscarRapido(container: AppContainer, query: String): List<Track> {
        // 1) Local primero
        val local = try {
            val all = container.offlineMusicRepository.loadTracks()
            filtrar(all, query)
        } catch (_: Throwable) { emptyList() }
        if (local.isNotEmpty()) {
            Log.i(TAG, "Match local para \"$query\": ${local.size} pistas")
            return local
        }

        // 2) API con timeout corto si hay red
        if (!container.networkConnectivityManager.isCurrentlyOnline()) return emptyList()
        return try {
            withTimeoutOrNull(4000L) {
                container.youtubeRepository.search(query).getOrElse { emptyList() }
            } ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    private fun filtrar(all: List<Track>, query: String): List<Track> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val strong = all.filter { track ->
            track.title.contains(q, ignoreCase = true) ||
                track.artist.contains(q, ignoreCase = true) ||
                track.album.contains(q, ignoreCase = true)
        }
        if (strong.isNotEmpty()) return strong
        val words = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return all.filter { track ->
            val hay = (track.title + " " + track.artist + " " + track.album).lowercase()
            words.all { hay.contains(it) }
        }
    }

    companion object {
        const val ACTION_VOICE_PLAY = "com.seldia.music.action.VOICE_PLAY"
        const val EXTRA_QUERY = "query"
        private const val TAG = "SeldiaVoicePlay"
    }
}