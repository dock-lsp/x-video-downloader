package com.xvideo.downloader.ui.player

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.*
import android.widget.PopupMenu
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.xvideo.downloader.R
import com.xvideo.downloader.data.model.VideoInfo
import com.xvideo.downloader.databinding.ActivityPlayerBinding
import com.xvideo.downloader.util.FileUtils
import com.xvideo.downloader.util.PlaybackHistoryManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()
    private val historyManager by lazy { PlaybackHistoryManager.getInstance(this) }

    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var currentPosition = 0L
    private var playbackPosition = 0L

    // Gesture detection
    private var gestureDetector: GestureDetector? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var currentVolume = 1f
    private var currentBrightness = 0.5f
    private var isSeeking = false
    private var isChangingVolume = false
    private var isChangingBrightness = false
    private var seekDelta = 0L

    private var videoUrl: String? = null
    private var videoInfo: VideoInfo? = null
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get intent data
        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        videoInfo = intent.getParcelableExtra(EXTRA_VIDEO_INFO)
        isStreaming = intent.getBooleanExtra(EXTRA_IS_STREAMING, false)
        playbackPosition = intent.getLongExtra(EXTRA_POSITION, 0L)

        setupUI()
        setupGestures()
        observeViewModel()
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Play/Pause
        binding.btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                } else {
                    it.play()
                }
            }
        }

        // Lock/Unlock
        binding.btnLock.setOnClickListener {
            viewModel.toggleLock()
        }

        binding.btnUnlock.setOnClickListener {
            viewModel.unlock()
        }

        // Speed control
        binding.btnSpeed.setOnClickListener { view ->
            showSpeedMenu(view)
        }

        // Fullscreen
        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // Seek bar
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = FileUtils.formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
                player?.seekTo(seekBar?.progress?.toLong() ?: 0)
            }
        })

        // Video info
        videoInfo?.let { info ->
            binding.tvVideoTitle.text = info.authorName
            binding.tvVideoAuthor.text = "@${info.authorUsername}"
        }

        // Apply initial brightness
        val layoutParams = window.attributes
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (viewModel.isLocked.value) {
                    // When locked, only toggle unlock button visibility
                    toggleControls()
                    return true
                }
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (viewModel.isLocked.value) {
                    // When locked, skip double-tap seek
                    return true
                }
                player?.let {
                    val screenWidth = binding.playerView.width
                    if (e.x < screenWidth / 2) {
                        // Double tap left - rewind 10s
                        it.seekTo(maxOf(0, it.currentPosition - 10000))
                        showSeekFeedback(-10000)
                    } else {
                        // Double tap right - forward 10s
                        it.seekTo(minOf(it.duration, it.currentPosition + 10000))
                        showSeekFeedback(10000)
                    }
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            if (viewModel.isLocked.value) {
                // When locked, let gesture detector handle single tap to toggle unlock button
                gestureDetector?.onTouchEvent(event)
                return@setOnTouchListener true
            }

            gestureDetector?.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.x
                    initialTouchY = event.y
                    currentVolume = player?.volume ?: 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - initialTouchX
                    val deltaY = event.y - initialTouchY

                    if (abs(deltaX) > 50 && abs(deltaX) > abs(deltaY) && !isSeeking) {
                        // Horizontal seek
                        isSeeking = true
                        val seekDelta = ((deltaX / binding.playerView.width) * player?.duration!! / 10).toLong()
                        seekDelta.let {
                            player?.seekTo(player!!.currentPosition + it)
                            showSeekFeedback(it)
                        }
                    } else if (abs(deltaY) > 50 && abs(deltaY) > abs(deltaX) && !isSeeking) {
                        // Vertical volume/brightness
                        val screenThird = binding.playerView.width / 3
                        if (initialTouchX < screenThird) {
                            // Left side - brightness
                            isChangingBrightness = true
                            val brightnessDelta = -deltaY / binding.playerView.height
                            currentBrightness = (currentBrightness + brightnessDelta).coerceIn(0.1f, 1f)
                            val layoutParams = window.attributes
                            layoutParams.screenBrightness = currentBrightness
                            window.attributes = layoutParams
                            showBrightnessIndicator()
                        } else if (initialTouchX > screenThird * 2) {
                            // Right side - volume
                            isChangingVolume = true
                            val volumeDelta = -deltaY / binding.playerView.height
                            currentVolume = (currentVolume + volumeDelta).coerceIn(0f, 1f)
                            player?.volume = currentVolume
                            showVolumeIndicator()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSeeking = false
                    isChangingVolume = false
                    isChangingBrightness = false
                }
            }
            true
        }

        // Unlock button click handler
        binding.btnUnlock.setOnClickListener {
            viewModel.unlock()
            binding.btnUnlock.visibility = View.GONE
            // Show controls after unlocking
            binding.controlsOverlay.visibility = View.VISIBLE
            binding.topControls.visibility = View.VISIBLE
            binding.btnLock.visibility = View.VISIBLE
        }
    }

    private fun showSpeedMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        PlayerViewModel.PLAYBACK_SPEEDS.forEach { speed ->
            popup.menu.add("${speed}x")
        }
        popup.setOnMenuItemClickListener { item ->
            val speed = item.title.toString().replace("x", "").toFloatOrNull() ?: 1f
            viewModel.setPlaybackSpeed(speed)
            player?.setPlaybackSpeed(speed)
            binding.tvSpeed.text = "${speed}x"
            true
        }
        popup.show()
    }

    private fun toggleFullscreen() {
        val config = Configuration()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun toggleControls() {
        if (viewModel.isLocked.value) {
            // When locked, toggle unlock button visibility
            val unlockVisible = binding.btnUnlock.visibility == View.VISIBLE
            binding.btnUnlock.visibility = if (unlockVisible) View.GONE else View.VISIBLE
            return
        }

        val isVisible = binding.controlsOverlay.visibility == View.VISIBLE
        binding.controlsOverlay.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.topControls.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.btnLock.visibility = if (isVisible) View.GONE else View.VISIBLE
    }

    private fun showSeekFeedback(delta: Long) {
        binding.tvSeekFeedback.visibility = View.VISIBLE
        binding.tvSeekFeedback.text = if (delta > 0) "+${delta / 1000}s" else "${delta / 1000}s"
        binding.tvSeekFeedback.postDelayed({
            binding.tvSeekFeedback.visibility = View.GONE
        }, 500)
    }

    private fun showVolumeIndicator() {
        binding.volumeIndicator.visibility = View.VISIBLE
        binding.volumeIndicator.progress = (currentVolume * 100).toInt()
        binding.volumeIndicator.postDelayed({
            binding.volumeIndicator.visibility = View.GONE
        }, 500)
    }

    private fun showBrightnessIndicator() {
        binding.brightnessIndicator.visibility = View.VISIBLE
        binding.brightnessIndicator.progress = (currentBrightness * 100).toInt()
        binding.brightnessIndicator.postDelayed({
            binding.brightnessIndicator.visibility = View.GONE
        }, 500)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLocked.collectLatest { isLocked ->
                        binding.controlsOverlay.visibility = if (isLocked) View.GONE else View.VISIBLE
                        // Lock button: show when controls visible and not locked
                        binding.btnLock.visibility = View.GONE
                        // Unlock button: hide when not locked (will show on tap)
                        binding.btnUnlock.visibility = if (isLocked) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.playbackSpeed.collectLatest { speed ->
                        player?.setPlaybackSpeed(speed)
                    }
                }

                launch {
                    viewModel.isPlaying.collectLatest { playing ->
                        binding.btnPlayPause.setImageResource(
                            if (playing) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                }
            }
        }
    }

    private fun initializePlayer() {
        // Enable hardware-accelerated decoding with DefaultRenderersFactory
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { exoPlayer ->
            binding.playerView.player = exoPlayer

            videoUrl?.let { url ->
                val mediaItem = MediaItem.fromUri(url.toUri())
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.prepare()

                if (playbackPosition > 0) {
                    exoPlayer.seekTo(playbackPosition)
                }
            }

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            binding.progressBar.visibility = View.GONE
                            binding.seekBar.max = exoPlayer.duration.toInt()
                            binding.tvDuration.text = FileUtils.formatDuration(exoPlayer.duration)
                            viewModel.updateDuration(exoPlayer.duration)

                            // Save playback history
                            val title = videoInfo?.authorName ?: videoUrl?.substringAfterLast("/")?.substringBefore("?") ?: "Unknown Video"
                            videoUrl?.let { url ->
                                historyManager.addHistory(title, url, exoPlayer.duration)
                            }
                        }
                        Player.STATE_BUFFERING -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            viewModel.updatePlayingState(false)
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.updatePlayingState(isPlaying)
                }
            })
        }

        lifecycleScope.launch {
            while (true) {
                player?.let {
                    binding.tvCurrentTime.text = FileUtils.formatDuration(it.currentPosition)
                    binding.seekBar.progress = it.currentPosition.toInt()
                    viewModel.updatePosition(it.currentPosition)
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun releasePlayer() {
        // Coroutine-based update is cancelled when lifecycleScope is cancelled (onStop)
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
            it.release()
        }
        player = null
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handle orientation change
    }

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_VIDEO_INFO = "video_info"
        const val EXTRA_IS_STREAMING = "is_streaming"
        const val EXTRA_POSITION = "position"
    }
}
