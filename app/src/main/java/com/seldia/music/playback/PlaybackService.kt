package com.seldia.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
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

    /**
     * Si el servicio se inicia desde FiestaLauncher por voz, hay que entrar en primer
     * plano de inmediato (Android 8 da 5 s tras startForegroundService). Media3
     * sustituirá esta notificación por la del reproductor al empezar a sonar.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BG_PLAY, false) == true) notificacionDeEspera()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun notificacionDeEspera() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CANAL_BG, "Reproducción en segundo plano", NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val n = NotificationCompat.Builder(this, CANAL_BG)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Seldia Music")
                .setContentText("Preparando reproducción…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(ID_BG, n)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo entrar en primer plano", t)
        }
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

    companion object {
        /** Lo envía FiestaLauncher al iniciar el servicio para reproducción por voz. */
        const val EXTRA_BG_PLAY = "com.fordlauncher.bg_play"
        private const val CANAL_BG = "seldia_bg_play"
        private const val ID_BG = 4211
        private const val TAG = "SeldiaPlayback"
    }
}
