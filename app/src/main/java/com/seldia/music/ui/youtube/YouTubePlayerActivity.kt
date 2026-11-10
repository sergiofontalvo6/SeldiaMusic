package com.seldia.music.ui.youtube

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.seldia.music.databinding.ActivityYoutubePlayerBinding

/**
 * Reproductor de YouTube mediante la IFrame Player API oficial (enablejsapi=1).
 * No usa Google Play Services, por lo que funciona en el WJ/Wonjan AC8227L.
 * Nota: la reproducción de YouTube es en primer plano (los Términos de YouTube no
 * permiten reproducción en segundo plano ni descarga).
 */
class YouTubePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubePlayerBinding
    private var videoId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        intent?.let {
            val theme = it.getStringExtra("com.fordlauncher.theme") ?: it.getStringExtra("theme")
            if (theme != null) {
                com.seldia.music.util.ThemePrefs.applyExternalTheme(this, theme.equals("light", ignoreCase = true))
            }
        }
        setTheme(com.seldia.music.util.ThemePrefs.themeResForContext(this))
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()

        binding.tvTitle.text = title
        binding.tvArtist.text = artist

        binding.btnBack.setOnClickListener { finish() }
        binding.btnExternal.setOnClickListener { openExternally() }

        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = USER_AGENT
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(TAG, "console[${it.messageLevel()}] ${it.message()}")
                }
                return true
            }
        }
        web.addJavascriptInterface(YouTubeBridge(), "Android")

        web.loadDataWithBaseURL(
            "https://www.youtube.com",
            buildHtml(videoId),
            "text/html",
            "utf-8",
            null
        )
    }

    private fun buildHtml(videoId: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                html,body{margin:0;padding:0;height:100%;background:#000;}
                #player{position:absolute;top:0;left:0;width:100%;height:100%;}
              </style>
            </head>
            <body>
              <div id="player"></div>
              <script>
                var tag = document.createElement('script');
                tag.src = "https://www.youtube.com/iframe_api";
                var firstScriptTag = document.getElementsByTagName('script')[0];
                firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                var player;
                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    videoId: '$videoId',
                    playerVars: { autoplay: 1, playsinline: 1, rel: 0, origin: 'https://www.youtube.com' },
                    events: {
                      'onReady': function(e) {
                        if (window.Android) Android.onState('ready');
                        e.target.playVideo();
                      },
                      'onStateChange': function(e) {
                        if (window.Android) Android.onState('state_' + e.data);
                      },
                      'onError': function(e) {
                        if (window.Android) Android.onError(e.data);
                      }
                    }
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun openExternally() {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        )
    }

    /** Puente JavaScript: recibe el estado/error del reproductor de YouTube. */
    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onState(state: String) {
            Log.d(TAG, "playerState: $state")
        }

        @JavascriptInterface
        fun onError(code: Int) {
            Log.d(TAG, "playerError: $code")
            runOnUiThread {
                val message = when (code) {
                    100, 101, 150, 152, 153 ->
                        "Este vídeo no permite reproducción embebida. Ábrelo en YouTube."
                    else ->
                        "Error al reproducir el vídeo (código $code)."
                }
                android.widget.Toast.makeText(
                    this@YouTubePlayerActivity, message, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        private const val TAG = "YTPlayer"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
