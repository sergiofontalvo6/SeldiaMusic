package com.seldia.music.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.app.SearchManager
import android.provider.MediaStore
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.seldia.music.R
import com.seldia.music.SeldiaApp
import com.seldia.music.data.model.Source
import com.seldia.music.data.model.Track
import com.seldia.music.databinding.ActivityMainBinding
import com.seldia.music.databinding.ActivityMainDesktopBinding
import com.seldia.music.databinding.ViewBottomBarBinding
import com.seldia.music.playback.DownloadStatus
import com.seldia.music.playback.PlayerState
import com.seldia.music.ui.home.HomeFragment
import com.seldia.music.ui.library.GroupTracksFragment
import com.seldia.music.ui.library.LikedSongsFragment
import com.seldia.music.ui.library.LibraryFragment
import com.seldia.music.ui.library.MediaGroupsFragment
import com.seldia.music.ui.library.OfflineFragment
import com.seldia.music.ui.player.LyricsBottomSheetFragment
import com.seldia.music.ui.player.PlayerFragment
import com.seldia.music.ui.playlist.PlaylistDetailFragment
import com.seldia.music.ui.queue.QueueFragment
import com.seldia.music.ui.SearchViewModel
import com.seldia.music.ui.search.SearchFragment
import com.seldia.music.ui.adapters.SidebarPlaylistAdapter
import com.seldia.music.util.OfflinePrefs
import com.seldia.music.util.accentPrimaryColor
import com.seldia.music.util.formatDuration
import com.seldia.music.util.showCreatePlaylistDialog
import kotlinx.coroutines.launch

/**
 * Shell principal adaptativo:
 * - Pantallas anchas (>= 840dp): layout desktop de tres paneles
 *   (biblioteca · contenido · "Sonando ahora" con reproductor integrado)
 * - Pantallas compactas: contenido + barra flotante + navegación inferior.
 */
class MainActivity : AppCompatActivity() {

    private var phoneBinding: ActivityMainBinding? = null
    private var desktopBinding: ActivityMainDesktopBinding? = null

    private val bottomBarBinding: ViewBottomBarBinding?
        get() = phoneBinding?.bottomBar ?: desktopBinding?.bottomBar

    private var fullscreen = false
    private var lastArtworkUrl: String? = null
    private var isCurrentFavorite = false
    private var downloadedKeys: Set<String> = emptySet()
    private var favoriteTrackKey: String? = null
    private var userScrubbing = false
    private var sidebarPlaylistAdapter: SidebarPlaylistAdapter? = null
    private var activePlaylistId: Long? = null
    private var likedActive = false
    private var activeFilter = SidebarFilter.LISTAS

    private enum class SidebarFilter { LISTAS, ARTISTAS, BUSCAR }

    // ---- Referencias al reproductor integrado en el panel derecho (desktop) ----
    private var npVideoView: PlayerView? = null
    private var npArtworkCard: View? = null
    private var npVideoContainer: View? = null
    private var npModeToggle: MaterialButtonToggleGroup? = null
    private var npBtnFullscreenEnter: ImageButton? = null
    private var npBtnFullscreenExit: ImageButton? = null
    private var npBtnLyrics: ImageButton? = null
    private var npFsBar: View? = null
    private var npFsIvArtwork: ImageView? = null
    private var npFsTvTitle: TextView? = null
    private var npFsTvArtist: TextView? = null
    private var npFsBtnFavorite: ImageButton? = null
    private var npFsBtnDownload: ImageButton? = null
    private var npFsBtnPlayPause: ImageButton? = null
    private var npFsBtnPrev: ImageButton? = null
    private var npFsBtnNext: ImageButton? = null
    private var npFsVolumeBar: SeekBar? = null
    private var npFsTvPosition: TextView? = null
    private var npFsTvDuration: TextView? = null
    private var npFsSeekBar: SeekBar? = null
    private var npFsBtnExit: ImageButton? = null
    private var npBtnFavorite: ImageButton? = null
    private var npBtnDownload: ImageButton? = null
    private var npBtnQueue: ImageButton? = null
    private var npTvTitle: TextView? = null
    private var npTvArtist: TextView? = null
    private var npSeekBar: SeekBar? = null
    private var npTvPosition: TextView? = null
    private var npTvDuration: TextView? = null
    private var npProgressDownload: ProgressBar? = null
    private var npBtnModeAudio: MaterialButton? = null
    private var npBtnModeVideo: MaterialButton? = null
    private var npIvArtwork: ImageView? = null

    // Estado guardado del video_container para pantalla completa exclusiva.
    private var savedVideoParent: ViewGroup? = null
    private var savedVideoIndex: Int = -1

    // Autohide de controles en pantalla completa (5 s de inactividad).
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlayHideRunnable: Runnable? = null
    private var overlaySeeking = false

    private val playerVm: PlayerViewModel by viewModels()
    private val libraryVm: LibraryViewModel by viewModels()
    private val searchVm: SearchViewModel by viewModels()
    private var pendingVoiceQuery: String? = null
    private var voiceSearchObserved = false

    private val isDesktop: Boolean
        get() = resources.configuration.screenWidthDp >= DESKTOP_MIN_WIDTH_DP ||
            (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                resources.configuration.screenWidthDp >= 720)

    private val contentContainer: FrameLayout
        get() = phoneBinding?.contentContainer ?: desktopBinding!!.contentContainer

    private val bottomNav: BottomNavigationView?
        get() = phoneBinding?.bottomNav

    private val app: SeldiaApp
        get() = application as SeldiaApp

    private val themeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.seldia.music.LauncherThemeReceiver.ACTION_THEME_APPLIED) {
                recreate()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        applyExternalTheme(intent)
        setTheme(com.seldia.music.util.ThemePrefs.themeResForContext(this))
        super.onCreate(savedInstanceState)

        if (isDesktop) {
            desktopBinding = ActivityMainDesktopBinding.inflate(layoutInflater)
            setContentView(desktopBinding!!.root)
        } else {
            phoneBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(phoneBinding!!.root)
            setupBottomNav()
        }

        // Diferir inicialización pesada (ExoPlayer, DB, red, extractores) para mostrar UI rápido.
        val rootView = if (isDesktop) desktopBinding!!.root else phoneBinding!!.root
        val loadingOverlay = findViewById<View>(R.id.loading_overlay)
        val firstLaunchPrefs = getSharedPreferences("seldia_ui_prefs", Context.MODE_PRIVATE)
        val showFirstLaunchLoading = savedInstanceState == null && !firstLaunchPrefs.getBoolean("first_open_loading_done", false)
        if (showFirstLaunchLoading) {
            loadingOverlay?.visibility = View.VISIBLE
        }
        rootView.post {
            if (isFinishing || isDestroyed) return@post
            if (isDesktop) {
                setupDesktopSidebar()
                setupDesktopPlayerPanel()
            }
            setupBottomBar()
            observePlayerState()
            observeDownloadStates()
            observeConnectivity()
            observeLikedCount()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                app.container.libraryRepository.purgeMissingDownloads(applicationContext)
            }

            if (savedInstanceState == null) {
                phoneBinding?.bottomNav?.selectedItemId = R.id.nav_home
                if (isDesktop) {
                    // Filtro Listas activo por defecto: primera lista (Favoritas) en el centro.
                    likedActive = true
                    activeFilter = SidebarFilter.LISTAS
                    renderSidebarFilterStates()
                    selectSection(LikedSongsFragment())
                }
            }
            if (showFirstLaunchLoading) {
                loadingOverlay?.visibility = View.GONE
                firstLaunchPrefs.edit().putBoolean("first_open_loading_done", true).apply()
            }
            handleVoicePlayIntent(intent)
        }
    }

    // ======================= Desktop: sidebar =======================

    private fun setupDesktopSidebar() {
        val b = desktopBinding ?: return

        sidebarPlaylistAdapter = SidebarPlaylistAdapter(
            onClick = { playlist -> openPlaylistDetail(playlist.id) },
            db = app.container.database
        )
        b.recyclerPlaylists.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.recyclerPlaylists.adapter = sidebarPlaylistAdapter

        // Drag & drop manual: arrastrar fila (pulsación larga) para reordenar.
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    sidebarPlaylistAdapter?.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isItemViewSwipeEnabled(): Boolean = false

                override fun onSelectedChanged(
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (viewHolder != null) {
                        viewHolder.itemView.elevation = if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                            12 * resources.displayMetrics.density
                        } else {
                            0f
                        }
                    }
                }

                override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    // Persiste el nuevo orden en Room (sortOrder).
                    sidebarPlaylistAdapter?.orderedIds()?.let { ids ->
                        libraryVm.reorderPlaylists(ids)
                    }
                }
            }
        )
        itemTouchHelper.attachToRecyclerView(b.recyclerPlaylists)

        // Filtros en fila horizontal: Listas | Artistas | Buscar.
        b.btnFilterLists.setOnClickListener {
            activeFilter = SidebarFilter.LISTAS
            renderSidebarFilterStates()
            if (activePlaylistId != null) {
                navigate(PlaylistDetailFragment.newInstance(activePlaylistId!!))
            } else {
                likedActive = true
                sidebarPlaylistAdapter?.setActive(null)
                renderSidebarFilterStates()
                navigate(LikedSongsFragment())
            }
        }
        b.btnFilterArtists.setOnClickListener {
            likedActive = false
            activePlaylistId = null
            sidebarPlaylistAdapter?.setActive(null)
            activeFilter = SidebarFilter.ARTISTAS
            renderSidebarFilterStates()
            navigate(MediaGroupsFragment.newInstance(GroupTracksFragment.MODE_ARTIST))
        }
        b.btnFilterSearch.setOnClickListener {
            likedActive = false
            activePlaylistId = null
            sidebarPlaylistAdapter?.setActive(null)
            activeFilter = SidebarFilter.BUSCAR
            renderSidebarFilterStates()
            navigate(SearchFragment())
        }

        b.btnNavOffline.setOnClickListener { openOfflineMusic() }
        b.btnNavLiked.setOnClickListener { openLikedSongs() }

        b.btnNewPlaylist.setOnClickListener {
            showCreatePlaylistDialog(this) { name -> libraryVm.createPlaylist(name) }
        }

        b.btnNavSettings.setOnClickListener { openSettings() }

        libraryVm.playlists.observe(this) { list ->
            sidebarPlaylistAdapter?.submit(list)
            sidebarPlaylistAdapter?.setActive(activePlaylistId)
        }
        renderSidebarFilterStates()
    }

    private fun renderSidebarFilterStates() {
        val b = desktopBinding ?: return
        val accent = accentPrimaryColor()
        val primary = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
        val listsActive = activeFilter == SidebarFilter.LISTAS
        val artistsActive = activeFilter == SidebarFilter.ARTISTAS
        val searchActive = activeFilter == SidebarFilter.BUSCAR

        b.btnFilterLists.setBackgroundResource(
            if (listsActive) R.drawable.bg_nav_selected else R.drawable.bg_glass_pill
        )
        b.btnFilterLists.setTextColor(if (listsActive) accent else primary)

        b.btnFilterArtists.setBackgroundResource(
            if (artistsActive) R.drawable.bg_nav_selected else R.drawable.bg_glass_pill
        )
        b.btnFilterArtists.setTextColor(if (artistsActive) accent else primary)

        b.btnFilterSearch.setBackgroundResource(
            if (searchActive) R.drawable.bg_nav_selected else R.drawable.bg_glass_pill
        )
        b.btnFilterSearch.setTextColor(if (searchActive) accent else primary)

        b.btnNavLiked.setBackgroundResource(
            if (likedActive) R.drawable.bg_nav_selected else R.drawable.bg_glass_card
        )
    }

    // ======================= Desktop: reproductor integrado en panel derecho =======================

    private fun setupDesktopPlayerPanel() {
        val np = desktopBinding?.nowPlaying ?: return

        // Capturar referencias a las vistas del panel derecho
        npVideoView = np.videoView
        npArtworkCard = np.artworkCard
        npVideoContainer = np.videoContainer
        npModeToggle = np.modeToggle
        npBtnFullscreenEnter = np.btnFullscreenEnter
        npBtnFullscreenExit = np.btnFullscreenExit
        npBtnLyrics = np.btnNpLyrics
        npBtnFavorite = np.btnNpFavorite
        npBtnDownload = np.btnNpDownload
        npBtnQueue = np.btnNpQueue
        npFsBar = np.fsBar
        npFsIvArtwork = np.fsIvArtwork
        npFsTvTitle = np.fsTvTitle
        npFsTvArtist = np.fsTvArtist
        npFsBtnFavorite = np.fsBtnFavorite
        npFsBtnDownload = np.fsBtnDownload
        npFsBtnPlayPause = np.fsBtnPlayPause
        npFsBtnPrev = np.fsBtnPrev
        npFsBtnNext = np.fsBtnNext
        npFsVolumeBar = np.fsVolumeBar
        npFsTvPosition = np.fsTvPosition
        npFsTvDuration = np.fsTvDuration
        npFsSeekBar = np.fsSeekBar
        npFsBtnExit = np.fsBtnExit
        npTvTitle = np.tvNpTitle
        npTvArtist = np.tvNpArtist
        npSeekBar = np.seekBar
        npTvPosition = np.tvPosition
        npTvDuration = np.tvDuration
        npProgressDownload = np.progressDownload
        npBtnModeAudio = np.btnModeAudio
        npBtnModeVideo = np.btnModeVideo
        npIvArtwork = np.ivBigArtwork

        // Vincular PlayerView a ExoPlayer (corrige pantalla negra en video).
        // Se asigna SIEMPRE (no solo en video mode) para que la surface esté lista.
        npVideoView?.player = playerVm.exoPlayer
        npVideoView?.useController = false
        npVideoView?.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

        // Acciones secundarias (los controles principales viven en la barra inferior)
        npBtnLyrics?.setOnClickListener { openLyrics() }
        npBtnFavorite?.setOnClickListener { toggleFavoriteCurrentTrack() }
        npBtnDownload?.setOnClickListener { downloadCurrentTrack() }
        npBtnQueue?.setOnClickListener { openQueue() }

        npBtnFullscreenEnter?.setOnClickListener { setFullscreen(true) }
        npBtnFullscreenExit?.setOnClickListener { setFullscreen(false) }

        // Toggle Audio / Video
        npModeToggle?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wantsVideo = checkedId == R.id.btn_mode_video
            val currentTrack = playerVm.state.value.currentTrack
            if (wantsVideo && currentTrack?.source != Source.YOUTUBE) {
                toast(getString(R.string.video_only_youtube))
                npModeToggle?.check(R.id.btn_mode_audio)
                return@addOnButtonCheckedListener
            }
            playerVm.setVideoMode(wantsVideo)
        }

        // Seek bar del panel derecho
        npSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) npTvPosition?.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                playerVm.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })

        // Barra flotante de pantalla completa (idéntica a la principal).
        npFsBtnPlayPause?.setOnClickListener { playerVm.toggle() }
        npFsBtnPrev?.setOnClickListener { playerVm.previous() }
        npFsBtnNext?.setOnClickListener { playerVm.next() }
        npFsBtnFavorite?.setOnClickListener { toggleFavoriteCurrentTrack() }
        npFsBtnDownload?.setOnClickListener { downloadCurrentTrack() }
        npFsBtnExit?.setOnClickListener { setFullscreen(false) }
        npFsVolumeBar?.progress = (playerVm.exoPlayer.volume * 100).toInt()
        npFsVolumeBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) playerVm.exoPlayer.volume = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        npFsSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) npFsTvPosition?.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { overlaySeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                playerVm.seekTo(seekBar?.progress?.toLong() ?: 0L)
                overlaySeeking = false
                showOverlayAndReschedule()
            }
        })
    }

    // ======================= Desktop: conectividad y likes =======================

    private fun observeConnectivity() {
        if (!isDesktop) return
        val b = desktopBinding ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.container.networkConnectivityManager.isOnline.collect { online ->
                    b.connectivityDot.setBackgroundResource(
                        if (online) R.drawable.bg_dot_online else R.drawable.bg_dot_offline
                    )
                    b.tvConnectivity.setText(
                        if (online) R.string.online_status else R.string.offline_status
                    )
                }
            }
        }
    }

    private fun observeLikedCount() {
        if (!isDesktop) return
        libraryVm.favorites.observe(this) { favs ->
            desktopBinding?.tvLikedCount?.text =
                resources.getQuantityString(R.plurals.favorite_count, favs.size, favs.size)
            // Sincronización reactiva: si la pista en reproducción cambia de favorito
            // en cualquier lista, el corazón del reproductor se repinta solo.
            val currentKey = playerVm.state.value.currentTrack?.key
            if (currentKey != null) {
                val nowFav = favs.any { it.key == currentKey }
                if (nowFav != isCurrentFavorite) {
                    isCurrentFavorite = nowFav
                    renderFavoriteButtons()
                }
            }
        }
        // Sincronización reactiva de descargas en barra/panel (Room + sesión).
        app.container.libraryRepository.observeDownloadedTrackKeys().observe(this) { keys ->
            downloadedKeys = keys
            val track = playerVm.state.value.currentTrack ?: return@observe
            val tint = if (isTrackDownloadedOffline(track)) accentPrimaryColor()
            else androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
            npBtnDownload?.setColorFilter(tint)
            bottomBarBinding?.btnDownloadBottom?.setColorFilter(tint)
        }
    }

    // ======================= Bottom bar =======================

    private fun setupBottomBar() {
        val bar = bottomBarBinding ?: return

        bar.btnPlayPause.setOnClickListener { playerVm.toggle() }
        bar.btnNext.setOnClickListener { playerVm.next() }
        bar.btnPrev.setOnClickListener { playerVm.previous() }
        bar.btnShuffle.setOnClickListener { playerVm.toggleShuffle() }
        bar.btnRepeat.setOnClickListener { cycleRepeatMode() }
        bar.btnLyrics.setOnClickListener { openLyrics() }
        bar.btnQueue.setOnClickListener { openQueue() }
        bar.btnFavorite.setOnClickListener { toggleFavoriteCurrentTrack() }
        bar.btnDownloadBottom.setOnClickListener { downloadCurrentTrack() }

        if (resources.configuration.screenWidthDp < 560) {
            bar.volumeBar.visibility = View.GONE
            bar.ivVolumeIcon.visibility = View.GONE
        }

        bar.volumeBar.progress = (playerVm.exoPlayer.volume * 100).toInt()
        bar.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) playerVm.exoPlayer.volume = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        bar.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = playerVm.state.value.durationMs
                if (duration <= 0L) return
                playerVm.seekTo(duration * progress / 1000L)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userScrubbing = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userScrubbing = false
            }
        })

        desktopBinding?.nowPlaying?.let { np ->
            np.btnNpLyrics.setOnClickListener { openLyrics() }
            np.btnNpFavorite.setOnClickListener { toggleFavoriteCurrentTrack() }
            np.btnNpDownload.setOnClickListener { downloadCurrentTrack() }
        }

        bottomBarBinding?.bottomBarRoot?.setOnClickListener {
            if (!isDesktop) openPlayer()
        }
    }

    private fun cycleRepeatMode() {
        val next = when (playerVm.state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        playerVm.setRepeatMode(next)
    }

    private fun toggleFavoriteCurrentTrack() {
        val track = playerVm.state.value.currentTrack ?: return
        isCurrentFavorite = !isCurrentFavorite
        renderFavoriteButtons()
        libraryVm.toggleFavorite(track)
        lifecycleScope.launch {
            isCurrentFavorite = app.container.libraryRepository.isFavorite(track)
            renderFavoriteButtons()
        }
    }

    private fun downloadCurrentTrack() {
        val track = playerVm.state.value.currentTrack ?: return
        when {
            track.source == Source.LOCAL ->
                toast(getString(R.string.download_already_local))
            !app.container.networkConnectivityManager.isCurrentlyOnline() ->
                toast(getString(R.string.download_offline_blocked))
            else -> {
                lifecycleScope.launch {
                    val already = runCatching {
                        app.container.libraryRepository.findExistingDownloadedUri(this@MainActivity, track.key)
                    }.getOrNull()
                    if (already != null) {
                        toast(getString(R.string.download_already_local))
                    } else {
                        app.container.mediaDownloadManager.autoDownloadAudio(track)
                    }
                }
            }
        }
    }

    private fun openLyrics() {
        LyricsBottomSheetFragment().show(supportFragmentManager, LyricsBottomSheetFragment.TAG)
    }

    private fun renderFavoriteButtons() {
        val bar = bottomBarBinding
        val icon = if (isCurrentFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        val accent = accentPrimaryColor()
        val color = if (isCurrentFavorite) accent else androidx.core.content.ContextCompat.getColor(
            this, R.color.text_secondary
        )
        bar?.btnFavorite?.setImageResource(icon)
        bar?.btnFavorite?.setColorFilter(color)

        npBtnFavorite?.setImageResource(icon)
        npBtnFavorite?.setColorFilter(
            if (isCurrentFavorite) accent else androidx.core.content.ContextCompat.getColor(
                this, R.color.text_primary
            )
        )
    }

    // ======================= Observador de estado de reproducción =======================

    private fun observePlayerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerVm.state.collect { updatePlaybackUi(it) }
            }
        }
        // Flujo independiente de posición: solo seekbars y tiempos cada 500 ms.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerVm.positionFlow.collect { updatePlaybackPosition(it) }
            }
        }
    }

    private fun updatePlaybackPosition(positionMs: Long) {
        val bar = bottomBarBinding
        val duration = playerVm.state.value.durationMs.coerceAtLeast(0L)
        bar?.tvPosition?.text = formatDuration(positionMs)
        if (!userScrubbing) {
            bar?.seekBar?.progress =
                if (duration > 0L) ((positionMs * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
        }
        npTvPosition?.text = formatDuration(positionMs)
        if (!userScrubbing) {
            npSeekBar?.progress =
                if (duration > 0L) ((positionMs * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
        }
        npFsTvPosition?.text = formatDuration(positionMs)
        if (!overlaySeeking) {
            npFsSeekBar?.progress =
                if (duration > 0L) ((positionMs * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
        }
    }

    private fun observeDownloadStates() {
        if (!isDesktop) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.container.mediaDownloadManager.downloadStates.collect { states ->
                    val track = playerVm.state.value.currentTrack
                    if (track != null && states.containsKey(track.key)) {
                        lifecycleScope.launch { updateNowPlayingDownloadState(track) }
                    }
                }
            }
        }
    }

    private fun updatePlaybackUi(s: PlayerState) {
        val bar = bottomBarBinding
        val track = s.currentTrack

        val barVisibility = if (track != null && !fullscreen) View.VISIBLE else View.GONE
        bar?.bottomBarRoot?.visibility = barVisibility
        if (track == null) {
            npTvTitle?.text = getString(R.string.nothing_playing)
            npTvArtist?.text = ""
            showLogoFallback(npIvArtwork)
            showLogoFallback(bar?.ivArtwork)
            npProgressDownload?.visibility = View.GONE
            return
        }

        bar?.tvTitle?.text = track.title
        bar?.tvArtist?.text = track.artist
        bar?.btnPlayPause?.setImageResource(
            if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (track.key != favoriteTrackKey) {
            favoriteTrackKey = track.key
            lifecycleScope.launch {
                isCurrentFavorite = app.container.libraryRepository.isFavorite(track)
                renderFavoriteButtons()
            }
        }

        bar?.btnShuffle?.setColorFilter(
            if (s.shuffleEnabled) accentPrimaryColor() else androidx.core.content.ContextCompat.getColor(
                this, R.color.text_secondary
            )
        )
        when (s.repeatMode) {
            Player.REPEAT_MODE_ONE -> {
                bar?.btnRepeat?.setImageResource(R.drawable.ic_repeat_one)
                bar?.btnRepeat?.setColorFilter(accentPrimaryColor())
            }
            Player.REPEAT_MODE_ALL -> {
                bar?.btnRepeat?.setImageResource(R.drawable.ic_repeat)
                bar?.btnRepeat?.setColorFilter(accentPrimaryColor())
            }
            else -> {
                bar?.btnRepeat?.setImageResource(R.drawable.ic_repeat)
                bar?.btnRepeat?.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
                )
            }
        }

        val duration = s.durationMs.coerceAtLeast(0L)
        bar?.tvDuration?.text = formatDuration(duration)

        // ===== Panel derecho =====
        npTvTitle?.text = track.title
        npTvArtist?.text = track.artist

        // Carátula: cargar en panel derecho y barra inferior (ambas).
        val artworkUrl = track.artworkUrl
        if (lastArtworkUrl != artworkUrl) {
            lastArtworkUrl = artworkUrl
            listOfNotNull(bar?.ivArtwork, npIvArtwork).forEach { image ->
                if (artworkUrl.isNullOrBlank()) {
                    showLogoFallback(image)
                } else {
                    image.clearAnimation()
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.load(hqArtwork(artworkUrl)) {
                        placeholder(R.drawable.ic_seldia_logo)
                        error(R.drawable.ic_seldia_logo)
                        crossfade(false)
                    }
                }
            }
            // Letras fijas del panel: cargar automáticamente al cambiar de pista.
            // (panel de letras eliminado: los accesos quedan en la barra inferior)
        }

        val isYouTube = track.source == Source.YOUTUBE
        npBtnModeAudio?.isEnabled = true
        npBtnModeVideo?.isEnabled = isYouTube
        npBtnModeVideo?.alpha = if (isYouTube) 1f else 0.4f

        npArtworkCard?.visibility = if (s.videoMode) View.GONE else View.VISIBLE
        npVideoContainer?.visibility = if (s.videoMode) View.VISIBLE else View.GONE

        // Asegurar que el PlayerView tenga el player asignado y forzar
        // la creación de la surface cuando se activa el modo video.
        if (s.videoMode) {
            npVideoView?.player = playerVm.exoPlayer
            npVideoView?.requestLayout()
            npVideoView?.invalidate()
        }

        npBtnFullscreenEnter?.isEnabled = s.videoMode
        npBtnFullscreenEnter?.alpha = if (s.videoMode) 1f else 0.4f
        npBtnFullscreenEnter?.visibility = if (s.videoMode && !fullscreen) View.VISIBLE else View.GONE
        npBtnFullscreenExit?.visibility = if (s.videoMode && fullscreen) View.VISIBLE else View.GONE

        if (!s.videoMode && fullscreen) setFullscreen(false)

        npTvDuration?.text = formatDuration(duration)

        // Refrescar barra flotante de pantalla completa (idéntica a la principal)
        npFsTvTitle?.text = track.title
        npFsTvArtist?.text = track.artist
        npFsBtnPlayPause?.setImageResource(
            if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        npFsBtnFavorite?.setImageResource(
            if (isCurrentFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        )
        npFsTvDuration?.text = formatDuration(duration)
        if (fullscreen) {
            val fsArtwork = npFsIvArtwork
            if (fsArtwork != null) {
                if (artworkUrl.isNullOrBlank()) {
                    showLogoFallback(fsArtwork)
                } else {
                    fsArtwork.clearAnimation()
                    fsArtwork.scaleType = ImageView.ScaleType.CENTER_CROP
                    fsArtwork.load(hqArtwork(artworkUrl)) {
                        placeholder(R.drawable.ic_seldia_logo)
                        error(R.drawable.ic_seldia_logo)
                        crossfade(false)
                    }
                }
            }
        }

        val alreadyDownloaded = isTrackDownloadedOffline(track)
        val tint = if (alreadyDownloaded) accentPrimaryColor() else androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
        npBtnDownload?.setColorFilter(tint)
        bar?.btnDownloadBottom?.setColorFilter(tint)
        npProgressDownload?.visibility = View.GONE
        // Auto-limpieza reactiva: si hay referencia obsoleta en el objeto en memoria, sanearla.
        if (!alreadyDownloaded && track.uri?.isNotBlank() == true && track.source == Source.YOUTUBE) {
            lifecycleScope.launch {
                val exists = runCatching {
                    app.container.libraryRepository.findExistingDownloadedUri(this@MainActivity, track.key)
                }.getOrNull()
                if (exists == null) {
                    runCatching { app.container.libraryRepository.updateDownloadStatus(track.key, false) }
                }
            }
        }
    }

    private fun isTrackDownloadedOffline(track: Track): Boolean =
        track.key in downloadedKeys || track.linkedKeys.any { it in downloadedKeys }

    private suspend fun updateNowPlayingDownloadState(track: Track) {
        val already = runCatching {
            app.container.libraryRepository.findExistingDownloadedUri(this, track.key)
        }.getOrNull() != null
        val tint = if (already) accentPrimaryColor() else androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
        npBtnDownload?.setColorFilter(tint)
        bottomBarBinding?.btnDownloadBottom?.setColorFilter(tint)
    }

    private fun scheduleOverlayHide() {
        overlayHideRunnable?.let { hideControlsHandler.removeCallbacks(it) }
        overlayHideRunnable = Runnable {
            npFsBar?.visibility = View.GONE
        }
        hideControlsHandler.postDelayed(overlayHideRunnable!!, OVERLAY_HIDE_DELAY_MS)
    }

    private fun showOverlayAndReschedule() {
        npFsBar?.visibility = View.VISIBLE
        scheduleOverlayHide()
    }

    private fun showLogoFallback(image: ImageView?) {
        if (image == null) return
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageResource(R.drawable.ic_seldia_logo)
        runCatching {
            val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_logo)
            image.startAnimation(anim)
        }
    }

    private fun updateArtwork(artworkUrl: String?) {
        // La carátula se carga en updatePlaybackUi para ambas vistas (panel y barra inferior).
    }

    private fun hqArtwork(url: String): String {
        val match = Regex("https?://i\\.ytimg\\.com/vi/([^/]+)/([^/]+)").find(url) ?: return url
        val id = match.groupValues[1]
        val file = match.groupValues[2].substringBefore('.')
        return if (file.startsWith("maxresdefault")) url else "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    }

    fun setFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) insets.hide(WindowInsetsCompat.Type.systemBars())
        else insets.show(WindowInsetsCompat.Type.systemBars())

        val chrome = if (enabled) View.GONE else View.VISIBLE
        phoneBinding?.bottomNav?.visibility = chrome
        bottomBarBinding?.bottomBarRoot?.visibility = chrome

        if (!isDesktop) return

        if (enabled) {
            // Solo el video: ocultar sidebar, contenido y divisores.
            desktopBinding?.let { b ->
                b.sidebarRoot.visibility = View.GONE
                b.dividerSidebar.visibility = View.GONE
                b.dividerNowPlaying.visibility = View.GONE
                b.contentContainer.visibility = View.GONE
            }
            // Mover el video_container al decor para que llene toda la pantalla.
            val video = npVideoContainer ?: return
            val parent = video.parent as? ViewGroup
            if (parent != null) {
                savedVideoParent = parent
                savedVideoIndex = parent.indexOfChild(video)
                parent.removeView(video)
            }
            val decor = window.decorView as ViewGroup
            decor.addView(
                video,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            video.setPadding(0, 0, 0, 0)
            video.bringToFront()
            npVideoView?.visibility = View.VISIBLE
            npVideoView?.requestLayout()
            npVideoView?.invalidate()
            // La barra flotante fullscreen lleva el botón de salida integrado.
            npFsBtnExit?.visibility = View.VISIBLE
            npBtnFullscreenExit?.visibility = View.GONE
            // Autohide: controles visibles al entrar y se ocultan a los 5 s.
            video.setOnTouchListener { _, motion ->
                if (motion.action == android.view.MotionEvent.ACTION_DOWN) showOverlayAndReschedule()
                false
            }
            showOverlayAndReschedule()
        } else {
            desktopBinding?.let { b ->
                b.sidebarRoot.visibility = View.VISIBLE
                b.dividerSidebar.visibility = View.VISIBLE
                b.dividerNowPlaying.visibility = View.VISIBLE
                b.contentContainer.visibility = View.VISIBLE
            }
            // Restaurar el video en el panel derecho.
            val video = npVideoContainer
            val decor = window.decorView as ViewGroup
            if (video != null && video.parent == decor) {
                decor.removeView(video)
                val parent = savedVideoParent
                val index = savedVideoIndex
                if (parent != null) {
                    val insertAt = if (index in 0 until parent.childCount) index else parent.childCount
                    parent.addView(
                        video,
                        insertAt,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (220 * resources.displayMetrics.density).toInt()
                        )
                    )
                }
                savedVideoParent = null
                savedVideoIndex = -1
            }
            video?.setOnTouchListener(null)
            overlayHideRunnable?.let { hideControlsHandler.removeCallbacks(it) }
            overlayHideRunnable = null
            npFsBar?.visibility = View.GONE
            npFsBtnExit?.visibility = View.GONE
            npVideoView?.visibility = if (playerVm.state.value.videoMode) View.VISIBLE else View.GONE
            npBtnFullscreenExit?.visibility = View.GONE
            npBtnFullscreenEnter?.visibility =
                if (playerVm.state.value.videoMode) View.VISIBLE else View.GONE
        }
    }

    // ======================= Navegación =======================

    private fun selectSection(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    private fun navigate(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun openPlayer() {
        if (isDesktop) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, PlayerFragment.newInstance(showClose = true))
            .addToBackStack("player")
            .commit()
    }

    fun openQueue() = navigate(QueueFragment())

    fun openPlaylistDetail(playlistId: Long) {
        activeFilter = SidebarFilter.LISTAS
        likedActive = false
        activePlaylistId = playlistId
        sidebarPlaylistAdapter?.setActive(playlistId)
        renderSidebarFilterStates()
        navigate(PlaylistDetailFragment.newInstance(playlistId))
    }

    fun openOfflineMusic() = navigate(OfflineFragment())

    fun openSettings() = navigate(com.seldia.music.ui.settings.SettingsFragment())

    fun openLikedSongs() {
        activeFilter = SidebarFilter.LISTAS
        likedActive = true
        activePlaylistId = null
        sidebarPlaylistAdapter?.setActive(null)
        renderSidebarFilterStates()
        navigate(LikedSongsFragment())
    }

    fun openGroups(mode: String) {
        activeFilter = SidebarFilter.ARTISTAS
        likedActive = false
        activePlaylistId = null
        sidebarPlaylistAdapter?.setActive(null)
        renderSidebarFilterStates()
        navigate(MediaGroupsFragment.newInstance(mode))
    }

    fun openGroupTracks(mode: String, name: String) = navigate(GroupTracksFragment.newInstance(mode, name))

    fun openLibrary(initialTab: Int) = navigate(LibraryFragment.newInstance(initialTab))

    private fun setupBottomNav() {
        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> selectSection(HomeFragment())
                R.id.nav_search -> selectSection(SearchFragment())
                R.id.nav_library -> selectSection(LibraryFragment())
                else -> return@setOnItemSelectedListener false
            }
            true
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyExternalTheme(intent)) recreate()
        handleVoicePlayIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(com.seldia.music.LauncherThemeReceiver.ACTION_THEME_APPLIED)
        try {
            registerReceiver(themeReceiver, filter)
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        try { unregisterReceiver(themeReceiver) } catch (_: Throwable) {}
        super.onPause()
    }

    private fun applyExternalTheme(i: Intent?): Boolean {
        val theme = i?.getStringExtra("com.fordlauncher.theme") ?: i?.getStringExtra("theme") ?: return false
        return com.seldia.music.util.ThemePrefs.applyExternalTheme(this, theme.equals("light", ignoreCase = true))
    }



    private fun handleVoicePlayIntent(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = (intent.getStringExtra(SearchManager.QUERY)
            ?: intent.getStringExtra("query")
            ?: intent.getStringExtra("com.seldia.music.extra.SEARCH_QUERY"))?.trim().orEmpty()
        if (query.isBlank()) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("SeldiaVoicePlay", "MEDIA_PLAY_FROM_SEARCH query=\"$query\"")

        // Resuelve y reproduce en segundo plano sin depender de inflar/esperar la UI de búsqueda.
        lifecycleScope.launch {
            try {
                val container = app.container
                val tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val local = runCatching {
                        container.offlineMusicRepository.loadTracks().filter { tr ->
                            tr.title.contains(query, true) || tr.artist.contains(query, true) || tr.album.contains(query, true)
                        }
                    }.getOrDefault(emptyList())
                    if (local.isNotEmpty()) return@withContext local
                    if (!container.networkConnectivityManager.isCurrentlyOnline()) return@withContext emptyList<Track>()
                    kotlinx.coroutines.withTimeoutOrNull(4000L) {
                        container.youtubeRepository.search(query).getOrElse { emptyList() }
                    } ?: emptyList()
                }
                if (tracks.isNotEmpty()) {
                    pendingVoiceQuery = null
                    playerVm.playQueue(tracks, 0)
                    android.util.Log.i("SeldiaVoicePlay", "PLAY_START(UI-intent) query=\"$query\" first=\"${tracks.first().title}\" t=${android.os.SystemClock.elapsedRealtime() - t0}ms")
                    return@launch
                }
            } catch (t: Throwable) {
                android.util.Log.w("SeldiaVoicePlay", "Búsqueda rápida falló, fallback UI", t)
            }
            // Fallback: flujo UI de búsqueda
            searchVm.clear()
            pendingVoiceQuery = query
            observeVoiceSearch()
            searchVm.search(query)
            navigate(com.seldia.music.ui.search.SearchFragment.newInstance(query))
            hideControlsHandler.postDelayed({
                if (pendingVoiceQuery == query) pendingVoiceQuery = null
            }, 20000)
        }
    }

    private fun observeVoiceSearch() {
        if (voiceSearchObserved) return
        voiceSearchObserved = true
        searchVm.results.observe(this) { results ->
            val pending = pendingVoiceQuery ?: return@observe
            if (!results.isNullOrEmpty()) {
                pendingVoiceQuery = null
                playerVm.playQueue(results, 0)
            }
        }
    }

    private companion object {
        const val DESKTOP_MIN_WIDTH_DP = 840
        const val OVERLAY_HIDE_DELAY_MS = 5000L
    }
}
