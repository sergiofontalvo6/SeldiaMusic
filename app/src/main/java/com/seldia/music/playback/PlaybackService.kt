package com.seldia.music.playback

import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.seldia.music.SeldiaApp

/**
 * Expone el reproductor al sistema: notificación multimedia, Bluetooth, Android Auto y,
 * sobre todo, los botones multimedia de los mandos del volante de la radio.
 *
 * La cola real de Seldia vive en [MusicPlayer] y el ExoPlayer solo lleva cargado un
 * [androidx.media3.common.MediaItem] cada vez. Media3 traduce por defecto
 * KEYCODE_MEDIA_NEXT/PREVIOUS a seekToNextMediaItem()/seekToPreviousMediaItem() sobre esa
 * timeline de un único item: "siguiente" no hace nada y "anterior" reinicia la pista.
 * Interceptamos esos eventos para usar MusicPlayer.next()/previous(), que sí recorren la cola.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val musicPlayer = (application as SeldiaApp).container.musicPlayer
        mediaSession = MediaSession.Builder(this, musicPlayer.player)
            .setCallback(SteeringWheelCallback(musicPlayer))
            .build()
        addSession(mediaSession!!)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /**
     * Maneja los botones multimedia físicos (mandos del volante, Bluetooth, auriculares).
     * Devolver `true` impide que Media3 aplique su manejo por defecto sobre el ExoPlayer.
     */
    @UnstableApi
    private class SteeringWheelCallback(
        private val musicPlayer: MusicPlayer
    ) : MediaSession.Callback {

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            val keyEvent = intent.keyEvent() ?: return false

            return when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (keyEvent.isSinglePressDown) musicPlayer.next()
                    // Consumimos también el ACTION_UP para que Media3 no lo reprocese.
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    // En un mando físico se espera que una pulsación cambie de pista siempre,
                    // sin el "reiniciar si llevamos más de 3 s" de los botones de la UI.
                    if (keyEvent.isSinglePressDown) musicPlayer.previous(forceSkip = true)
                    true
                }
                // PLAY/PAUSE, HEADSETHOOK, FAST_FORWARD, REWIND, etc. los sigue gestionando
                // Media3 sobre el ExoPlayer (funcionan correctamente).
                else -> false
            }
        }

        private val KeyEvent.isSinglePressDown: Boolean
            get() = action == KeyEvent.ACTION_DOWN && repeatCount == 0

        private fun Intent.keyEvent(): KeyEvent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            }
    }
}
