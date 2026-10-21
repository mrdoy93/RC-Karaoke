package com.example.offlinekaraoke

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.math.abs

class MainActivity : Activity() {

    private class Song(
        val number: String,
        val title: String,
        val artist: String,
        val offset: Long,
        val packedLength: Long,
        val rawLength: Int
    ) {
        val searchKey: String = "$number $title $artist".lowercase(Locale.ROOT)
    }

    private enum class LibraryFilter {
        ALL,
        LANGUAGE,
        FAVORITES,
        RECENT
    }

    private enum class SongLanguageGroup(val displayName: String) {
        LATIN("Latin script"),
        JAPANESE("Japanese"),
        KOREAN("Korean"),
        CJK("Chinese / CJK"),
        CYRILLIC("Cyrillic"),
        ARABIC("Arabic"),
        OTHER("Other")
    }

    private data class SongRowHolder(
        val number: TextView,
        val title: TextView,
        val artist: TextView,
        val favorite: TextView,
        val add: TextView
    )

    private lateinit var screens: ViewFlipper
    private lateinit var songList: ListView
    private lateinit var songAdapter: SongAdapter
    private lateinit var queueList: LinearLayout
    private lateinit var queueBadge: TextView
    private lateinit var playerQueueBadge: TextView
    private lateinit var songNumberInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var allSongsFilter: TextView
    private lateinit var languageFilter: TextView
    private lateinit var favoritesFilter: TextView
    private lateinit var recentFilter: TextView
    private lateinit var remoteSettingsButton: TextView

    private lateinit var playerSongTitle: TextView
    private lateinit var currentLyric: TextView
    private lateinit var nextLyric: TextView
    private lateinit var songProgress: SeekBar
    private lateinit var elapsedTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var pauseButton: Button
    private lateinit var keyValue: TextView
    private lateinit var tempoValue: TextView
    private lateinit var micVolumeValue: TextView
    private lateinit var backgroundVideoSurface: SurfaceView
    private lateinit var playerScreen: View
    private lateinit var reservePanel: View
    private lateinit var reservePanelScrim: View
    private lateinit var reserveNumberDisplay: TextView
    private lateinit var reserveSongPreview: TextView
    private lateinit var playerReserveBadge: TextView
    private lateinit var playerReserveCount: TextView
    private lateinit var reserveKeypad: View
    private lateinit var reserveTitleSearch: EditText
    private lateinit var reserveTitleResults: ListView
    private lateinit var reserveNumberModeButton: Button
    private lateinit var reserveTitleModeButton: Button
    private lateinit var reserveSubmitButton: Button
    private lateinit var backgroundVideoButton: Button
    private lateinit var reserveSearchAdapter: ReserveSearchAdapter

    private val catalog = mutableListOf<Song>()
    private val catalogByNumber = mutableMapOf<String, Song>()
    private val queue = mutableListOf<Song>()
    private val favoriteSongNumbers = mutableSetOf<String>()
    private val recentSongNumbers = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    private var browserRemoteServer: BrowserRemoteServer? = null
    @Volatile private var remoteEnabled = false
    @Volatile private var remoteQueueLimit = DEFAULT_REMOTE_QUEUE_LIMIT
    @Volatile private var publishedQueueSize = 0

    private var currentSong: Song? = null
    private var currentDurationSeconds = DEFAULT_DURATION_SECONDS
    private var elapsedSeconds = 0
    private var isPlaying = false
    private var keyShift = 0
    private var tempoPercent = 100
    private var micVolumePercent = 50
    private var mediaPlayer: MediaPlayer? = null
    private var backgroundVideoPlayer: MediaPlayer? = null
    private var micAudioRecord: AudioRecord? = null
    private var micAudioTrack: AudioTrack? = null
    private var micMonitorThread: Thread? = null
    @Volatile private var micMonitorRunning = false
    private var backgroundVideoAssets: List<String> = emptyList()
    private var backgroundVideoSelection = VIDEO_SELECTION_AUTO
    private var selectedBackgroundAsset: String? = null
    private var videoSurfaceReady = false
    private var timedLyrics: List<MidiLyricsParser.TimedLine> = emptyList()
    private var lyricDisplayIndices: List<Int> = emptyList()
    private var displayedLyricIndex = Int.MIN_VALUE
    private var displayedHighlightEnd = -1
    private var displayedLyricMode = LYRIC_MODE_UNSET
    private var playbackPositionMs = 0L
    private var reserveDigits = ""
    private var playerTouchDownX = 0f
    private var playerTouchDownY = 0f
    private var reservationTitleMode = false
    private var activeLibraryFilter = LibraryFilter.ALL
    private var selectedLanguageGroup: SongLanguageGroup? = null

    private val progressTick = object : Runnable {
        override fun run() {
            if (isPlaying) {
                playbackPositionMs = mediaPlayer?.let { player ->
                    runCatching { player.currentPosition.toLong() }.getOrDefault(playbackPositionMs + PROGRESS_TICK_MS)
                } ?: (playbackPositionMs + PROGRESS_TICK_MS)
                elapsedSeconds = (playbackPositionMs / 1000L).toInt()
                updatePlaybackUi()
                updateLyrics(playbackPositionMs)
                if (playbackPositionMs >= currentDurationSeconds * 1000L) {
                    showScore()
                    return
                }
            }
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )

        bindViews()
        configureBackgroundVideo()
        catalog += loadEmbeddedCatalog()
        catalogByNumber.putAll(catalog.associateBy { it.number })
        loadLibraryState()
        loadRemoteSettings()
        songAdapter = SongAdapter(catalog)
        songList.adapter = songAdapter
        songList.setOnItemClickListener { _, _, position, _ ->
            addToQueue(songAdapter.getItem(position))
        }

        wireHomeScreen()
        wirePlayerScreen()
        wireScoreScreen()
        renderQueue()
        updateLibraryFilterUi()
        updateRemoteControlUi()
        if (remoteEnabled) startBrowserRemote(showFailureMessage = true)
        handler.post(progressTick)
    }

    private fun bindViews() {
        screens = findViewById(R.id.screenFlipper)
        songList = findViewById(R.id.songList)
        queueList = findViewById(R.id.queueList)
        queueBadge = findViewById(R.id.queueBadge)
        playerQueueBadge = findViewById(R.id.playerQueueBadge)
        songNumberInput = findViewById(R.id.songNumberInput)
        searchInput = findViewById(R.id.searchInput)
        allSongsFilter = findViewById(R.id.allSongsFilter)
        languageFilter = findViewById(R.id.languageFilter)
        favoritesFilter = findViewById(R.id.favoritesFilter)
        recentFilter = findViewById(R.id.recentFilter)
        remoteSettingsButton = findViewById(R.id.remoteSettingsButton)
        playerSongTitle = findViewById(R.id.playerSongTitle)
        currentLyric = findViewById(R.id.currentLyric)
        nextLyric = findViewById(R.id.nextLyric)
        songProgress = findViewById(R.id.songProgress)
        elapsedTime = findViewById(R.id.elapsedTime)
        totalTime = findViewById(R.id.totalTime)
        pauseButton = findViewById(R.id.pauseButton)
        keyValue = findViewById(R.id.keyValue)
        tempoValue = findViewById(R.id.tempoValue)
        micVolumeValue = findViewById(R.id.micVolumeValue)
        backgroundVideoSurface = findViewById(R.id.backgroundVideoSurface)
        playerScreen = findViewById(R.id.playerScreen)
        reservePanel = findViewById(R.id.reservePanel)
        reservePanelScrim = findViewById(R.id.reservePanelScrim)
        reserveNumberDisplay = findViewById(R.id.reserveNumberDisplay)
        reserveSongPreview = findViewById(R.id.reserveSongPreview)
        playerReserveBadge = findViewById(R.id.playerReserveBadge)
        playerReserveBadge.isSelected = true
        playerReserveCount = findViewById(R.id.playerReserveCount)
        reserveKeypad = findViewById(R.id.reserveKeypad)
        reserveTitleSearch = findViewById(R.id.reserveTitleSearch)
        reserveTitleResults = findViewById(R.id.reserveTitleResults)
        reserveNumberModeButton = findViewById(R.id.reserveNumberModeButton)
        reserveTitleModeButton = findViewById(R.id.reserveTitleModeButton)
        reserveSubmitButton = findViewById(R.id.reserveSubmitButton)
        backgroundVideoButton = findViewById(R.id.backgroundVideoButton)
    }

    private fun configureBackgroundVideo() {
        backgroundVideoAssets = assets.list("")
            .orEmpty()
            .filter { name -> VIDEO_EXTENSIONS.any { extension -> name.endsWith(extension, ignoreCase = true) } }
            .sorted()
        val savedSelection = getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
            .getString(BACKGROUND_VIDEO_KEY, VIDEO_SELECTION_AUTO)
            .orEmpty()
        backgroundVideoSelection = when {
            savedSelection == VIDEO_SELECTION_AUTO -> VIDEO_SELECTION_AUTO
            savedSelection == VIDEO_SELECTION_OFF -> VIDEO_SELECTION_OFF
            savedSelection in backgroundVideoAssets -> savedSelection
            else -> VIDEO_SELECTION_AUTO
        }
        updateBackgroundVideoButton()

        backgroundVideoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                videoSurfaceReady = true
                prepareBackgroundVideo()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoSurfaceReady = false
                releaseBackgroundVideo()
            }
        })
    }

    private fun showBackgroundVideoDialog() {
        val selections = buildList {
            add(VIDEO_SELECTION_AUTO)
            add(VIDEO_SELECTION_OFF)
            addAll(backgroundVideoAssets)
        }
        val labels = buildList {
            add("Automatic — change with each song")
            add("Off — black background")
            backgroundVideoAssets.forEach { assetName ->
                add(backgroundVideoLabel(assetName))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Background video")
            .setSingleChoiceItems(
                labels.toTypedArray(),
                selections.indexOf(backgroundVideoSelection).coerceAtLeast(0)
            ) { dialog, which ->
                backgroundVideoSelection = selections[which]
                getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(BACKGROUND_VIDEO_KEY, backgroundVideoSelection)
                    .apply()
                applyBackgroundVideoSelection()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBackgroundVideoSelection() {
        selectedBackgroundAsset = currentSong?.let(::resolveBackgroundVideo)
        releaseBackgroundVideo()
        prepareBackgroundVideo()
        updateBackgroundVideoButton()
        val message = when (backgroundVideoSelection) {
            VIDEO_SELECTION_AUTO -> "Background video set to Automatic"
            VIDEO_SELECTION_OFF -> "Background video turned off"
            else -> "Background changed to ${backgroundVideoLabel(backgroundVideoSelection)}"
        }
        showMessage(message)
    }

    private fun resolveBackgroundVideo(song: Song): String? = when (backgroundVideoSelection) {
        VIDEO_SELECTION_OFF -> null
        VIDEO_SELECTION_AUTO -> if (backgroundVideoAssets.isEmpty()) {
            null
        } else {
            val songIndex = song.number.toIntOrNull() ?: song.number.hashCode()
            backgroundVideoAssets[Math.floorMod(songIndex, backgroundVideoAssets.size)]
        }
        else -> backgroundVideoSelection.takeIf { it in backgroundVideoAssets }
    }

    private fun updateBackgroundVideoButton() {
        val selectedIndex = backgroundVideoAssets.indexOf(backgroundVideoSelection)
        val buttonText = when {
            backgroundVideoSelection == VIDEO_SELECTION_AUTO -> "BG Auto"
            backgroundVideoSelection == VIDEO_SELECTION_OFF -> "BG Off"
            selectedIndex >= 0 -> "BG ${selectedIndex + 1}"
            else -> "BG Auto"
        }
        val description = when {
            backgroundVideoSelection == VIDEO_SELECTION_AUTO -> "Automatic background video"
            backgroundVideoSelection == VIDEO_SELECTION_OFF -> "Background video off"
            selectedIndex >= 0 -> "Background video ${selectedIndex + 1}: ${backgroundVideoLabel(backgroundVideoSelection)}"
            else -> "Automatic background video"
        }
        backgroundVideoButton.text = buttonText
        backgroundVideoButton.contentDescription = "$description. Tap to change."
    }

    private fun backgroundVideoLabel(assetName: String): String {
        val baseName = assetName.substringBeforeLast('.')
        return when {
            baseName.contains("Abstract Tunnel", ignoreCase = true) -> "Neon Tunnel"
            baseName.contains("Sky Stage", ignoreCase = true) -> "Sky Stage"
            baseName.contains("mtv1", ignoreCase = true) -> "Motion Stage"
            baseName.contains("view-12", ignoreCase = true) -> "Classic Stage"
            else -> baseName
                .replace('-', ' ')
                .replace('_', ' ')
                .replace(LYRIC_WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_BACKGROUND_LABEL_LENGTH)
                .trimEnd()
        }
    }

    private fun wireHomeScreen() {
        findViewById<Button>(R.id.playNumberButton).setOnClickListener { playEnteredNumber() }
        findViewById<Button>(R.id.startQueueButton).setOnClickListener { startNextQueuedSong() }
        songNumberInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                playEnteredNumber()
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                filterSongs(text?.toString().orEmpty())
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        allSongsFilter.setOnClickListener {
            activeLibraryFilter = LibraryFilter.ALL
            selectedLanguageGroup = null
            searchInput.setText("")
            applySongFilters()
        }
        languageFilter.setOnClickListener {
            showLanguageFilterDialog()
        }
        favoritesFilter.setOnClickListener {
            activeLibraryFilter = LibraryFilter.FAVORITES
            searchInput.setText("")
            applySongFilters()
            if (favoriteSongNumbers.isEmpty()) {
                showMessage("No favorites yet. Tap the heart beside a song to add one.")
            }
        }
        recentFilter.setOnClickListener {
            activeLibraryFilter = LibraryFilter.RECENT
            searchInput.setText("")
            applySongFilters()
            if (recentSongNumbers.isEmpty()) {
                showMessage("Songs you play will appear here.")
            }
        }
        remoteSettingsButton.setOnClickListener { showBrowserRemoteDialog() }
    }

    private fun loadRemoteSettings() {
        val preferences = getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
        remoteEnabled = preferences.getBoolean(REMOTE_ENABLED_KEY, false)
        remoteQueueLimit = preferences.getInt(REMOTE_QUEUE_LIMIT_KEY, DEFAULT_REMOTE_QUEUE_LIMIT)
            .coerceIn(MIN_REMOTE_QUEUE_LIMIT, MAX_REMOTE_QUEUE_LIMIT)
    }

    private fun persistRemoteSettings() {
        getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(REMOTE_ENABLED_KEY, remoteEnabled)
            .putInt(REMOTE_QUEUE_LIMIT_KEY, remoteQueueLimit)
            .apply()
    }

    private fun showBrowserRemoteDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(2))
        }
        val statusText = simpleText("", color(R.color.cyan), 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        val addressText = simpleText("", Color.WHITE, 16f).apply {
            setPadding(0, dp(9), 0, dp(8))
            setTextIsSelectable(true)
        }
        val helpText = simpleText(
            "Connect the other device to the same Wi-Fi or hotspot, then open this address in its browser.",
            color(R.color.muted),
            13f
        ).apply { setPadding(0, 0, 0, dp(14)) }
        val enableRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        enableRow.addView(simpleText("Allow browser reservations", Color.WHITE, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTypeface(typeface, Typeface.BOLD)
        })
        val enabledSwitch = Switch(this).apply { isChecked = remoteEnabled }
        enableRow.addView(enabledSwitch)
        val limitLabel = simpleText("", Color.WHITE, 14f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        val limitBar = SeekBar(this).apply {
            max = MAX_REMOTE_QUEUE_LIMIT - MIN_REMOTE_QUEUE_LIMIT
            progress = remoteQueueLimit - MIN_REMOTE_QUEUE_LIMIT
        }
        val limitHelp = simpleText(
            "Browser requests stop when the main queue reaches this limit. Songs can still be added on this device.",
            color(R.color.muted),
            12f
        )
        content.addView(statusText)
        content.addView(addressText)
        content.addView(helpText)
        content.addView(enableRow)
        content.addView(limitLabel)
        content.addView(limitBar)
        content.addView(limitHelp)

        fun refresh() {
            enabledSwitch.isChecked = remoteEnabled
            statusText.text = if (remoteEnabled) "●  REMOTE IS ON" else "○  REMOTE IS OFF"
            statusText.setTextColor(if (remoteEnabled) color(R.color.cyan) else color(R.color.muted))
            val addresses = browserRemoteServer?.browserAddresses().orEmpty()
            addressText.text = when {
                !remoteEnabled -> "Turn on to create the browser address"
                addresses.isEmpty() -> "Waiting for a Wi-Fi or hotspot connection"
                else -> addresses.joinToString("\n")
            }
            limitLabel.text = "Remote queue limit: $remoteQueueLimit songs"
        }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == remoteEnabled) return@setOnCheckedChangeListener
            if (checked) startBrowserRemote(showFailureMessage = true) else stopBrowserRemote()
            refresh()
        }
        limitBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                remoteQueueLimit = progress + MIN_REMOTE_QUEUE_LIMIT
                limitLabel.text = "Remote queue limit: $remoteQueueLimit songs"
                persistRemoteSettings()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        refresh()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Browser remote")
            .setView(content)
            .setPositiveButton("Done", null)
            .setNeutralButton("Copy address") { _, _ ->
                val address = browserRemoteServer?.browserAddresses()?.firstOrNull()
                if (address == null) {
                    showMessage("Turn on the remote and connect to Wi-Fi or a hotspot first.")
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Karaoke remote", address))
                    showMessage("Remote address copied.")
                }
            }
            .create()
        dialog.show()
    }

    private fun startBrowserRemote(showFailureMessage: Boolean): Boolean {
        if (browserRemoteServer != null) {
            remoteEnabled = true
            persistRemoteSettings()
            updateRemoteControlUi()
            return true
        }
        val server = BrowserRemoteServer(
            catalog = catalog.map { RemoteCatalogSong(it.number, it.title, it.artist) },
            port = REMOTE_PORT,
            statusProvider = { RemoteQueueStatus(publishedQueueSize, remoteQueueLimit) },
            reservationHandler = ::reserveSongFromBrowser
        )
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(1)
        Thread {
            runCatching { server.start() }.onFailure(failure::set)
            ready.countDown()
        }.apply {
            name = "KaraokeRemoteStartup"
            isDaemon = true
            start()
        }
        val startedInTime = ready.await(REMOTE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val error = failure.get()
        if (!startedInTime || error != null) {
            server.stop()
            remoteEnabled = false
            persistRemoteSettings()
            updateRemoteControlUi()
            if (showFailureMessage) showMessage("Could not start browser remote. Port $REMOTE_PORT may be in use.")
            return false
        }
        browserRemoteServer = server
        remoteEnabled = true
        persistRemoteSettings()
        updateRemoteControlUi()
        if (showFailureMessage) {
            val address = server.browserAddresses().firstOrNull()
            showMessage(address?.let { "Remote ready at $it" } ?: "Remote is on. Connect this device to Wi-Fi or a hotspot.")
        }
        return true
    }

    private fun stopBrowserRemote() {
        browserRemoteServer?.stop()
        browserRemoteServer = null
        remoteEnabled = false
        persistRemoteSettings()
        updateRemoteControlUi()
        showMessage("Browser remote turned off.")
    }

    private fun updateRemoteControlUi() {
        if (!::remoteSettingsButton.isInitialized) return
        remoteSettingsButton.text = if (remoteEnabled) "REMOTE  ON" else "REMOTE  OFF"
        remoteSettingsButton.setTextColor(if (remoteEnabled) color(R.color.cyan) else color(R.color.muted))
        remoteSettingsButton.setTypeface(
            remoteSettingsButton.typeface,
            if (remoteEnabled) Typeface.BOLD else Typeface.NORMAL
        )
    }

    private fun reserveSongFromBrowser(rawNumber: String): RemoteReservationResult {
        val number = rawNumber.filter(Char::isDigit).take(6).padStart(6, '0')
        val song = catalogByNumber[number]
            ?: return RemoteReservationResult(false, "Song $number was not found.")
        val result = AtomicReference(RemoteReservationResult(false, "The main device did not respond."))
        val completed = CountDownLatch(1)
        runOnUiThread {
            result.set(
                if (!remoteEnabled) {
                    RemoteReservationResult(false, "Browser reservations are turned off.")
                } else if (queue.size >= remoteQueueLimit) {
                    RemoteReservationResult(false, "The queue is full. Please wait for the next song.")
                } else {
                    queue += song
                    renderQueue()
                    RemoteReservationResult(true, "${song.title} was added as #${queue.size} in the queue.")
                }
            )
            completed.countDown()
        }
        completed.await(REMOTE_RESERVATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun wirePlayerScreen() {
        wireReservationPanel()
        findViewById<Button>(R.id.playerHomeButton).setOnClickListener { showHome() }
        findViewById<Button>(R.id.restartButton).setOnClickListener {
            elapsedSeconds = 0
            playbackPositionMs = 0L
            mediaPlayer?.let { runCatching { it.seekTo(0); it.start() } }
            backgroundVideoPlayer?.let { runCatching { it.seekTo(0); it.start() } }
            isPlaying = true
            pauseButton.text = "Pause"
            updatePlaybackUi()
            updateLyrics(0L)
        }
        pauseButton.setOnClickListener {
            if (isPlaying) {
                mediaPlayer?.let { runCatching { it.pause() } }
                backgroundVideoPlayer?.let { runCatching { it.pause() } }
                isPlaying = false
                pauseButton.text = "Resume"
            } else {
                mediaPlayer?.let { runCatching { it.start() } }
                backgroundVideoPlayer?.let { runCatching { it.start() } }
                isPlaying = true
                pauseButton.text = "Pause"
            }
        }
        findViewById<Button>(R.id.finishButton).setOnClickListener { showScore() }
        findViewById<Button>(R.id.keyDownButton).setOnClickListener {
            keyShift = (keyShift - 1).coerceAtLeast(-12)
            updateMusicControls()
        }
        findViewById<Button>(R.id.keyUpButton).setOnClickListener {
            keyShift = (keyShift + 1).coerceAtMost(12)
            updateMusicControls()
        }
        findViewById<Button>(R.id.tempoDownButton).setOnClickListener {
            tempoPercent = (tempoPercent - 5).coerceAtLeast(70)
            updateMusicControls()
        }
        findViewById<Button>(R.id.tempoUpButton).setOnClickListener {
            tempoPercent = (tempoPercent + 5).coerceAtMost(130)
            updateMusicControls()
        }
        findViewById<Button>(R.id.micVolumeDownButton).setOnClickListener {
            micVolumePercent = (micVolumePercent - 10).coerceAtLeast(0)
            updateMusicControls()
            ensureMicrophoneMonitor()
        }
        findViewById<Button>(R.id.micVolumeUpButton).setOnClickListener {
            micVolumePercent = (micVolumePercent + 10).coerceAtMost(100)
            updateMusicControls()
            ensureMicrophoneMonitor()
        }
        songProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                elapsedSeconds = (currentDurationSeconds * (value / 1000f)).toInt()
                playbackPositionMs = elapsedSeconds * 1000L
                mediaPlayer?.let { runCatching { it.seekTo(playbackPositionMs.toInt()) } }
                updatePlaybackUi()
                updateLyrics(playbackPositionMs)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    @Suppress("ClickableViewAccessibility")
    private fun wireReservationPanel() {
        reservePanel.layoutParams = reservePanel.layoutParams.apply {
            width = (resources.displayMetrics.widthPixels * 0.28f).toInt().coerceIn(dp(260), dp(360))
        }
        reserveSearchAdapter = ReserveSearchAdapter()
        reserveTitleResults.adapter = reserveSearchAdapter
        reserveTitleResults.setOnItemClickListener { _, _, position, _ ->
            reserveSearchAdapter.getItem(position)?.let(::reserveSong)
        }
        reserveTitleSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                filterReservationTitles(text?.toString().orEmpty())
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        reserveNumberModeButton.setOnClickListener { setReservationMode(titleMode = false) }
        reserveTitleModeButton.setOnClickListener { setReservationMode(titleMode = true) }
        backgroundVideoButton.setOnClickListener { showBackgroundVideoDialog() }

        playerScreen.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    playerTouchDownX = event.x
                    playerTouchDownY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val horizontalDistance = event.x - playerTouchDownX
                    val verticalDistance = event.y - playerTouchDownY
                    if (horizontalDistance < -dp(70) && abs(horizontalDistance) > abs(verticalDistance)) {
                        showReservationPanel()
                    }
                    true
                }
                else -> true
            }
        }

        reservePanelScrim.setOnClickListener { hideReservationPanel() }
        findViewById<View>(R.id.playerReserveControl).setOnClickListener { showReservationPanel() }
        playerReserveBadge.setOnClickListener { showReservationPanel() }
        findViewById<Button>(R.id.reserveCloseButton).setOnClickListener { hideReservationPanel() }

        val digitButtons = intArrayOf(
            R.id.reserveDigit0,
            R.id.reserveDigit1,
            R.id.reserveDigit2,
            R.id.reserveDigit3,
            R.id.reserveDigit4,
            R.id.reserveDigit5,
            R.id.reserveDigit6,
            R.id.reserveDigit7,
            R.id.reserveDigit8,
            R.id.reserveDigit9
        )
        digitButtons.forEachIndexed { digit, buttonId ->
            findViewById<Button>(buttonId).setOnClickListener {
                if (reserveDigits.length < 6) {
                    reserveDigits += digit.toString()
                    updateReservationEntry()
                }
            }
        }
        findViewById<Button>(R.id.reserveClearButton).setOnClickListener {
            reserveDigits = ""
            updateReservationEntry()
        }
        findViewById<Button>(R.id.reserveBackspaceButton).setOnClickListener {
            if (reserveDigits.isNotEmpty()) reserveDigits = reserveDigits.dropLast(1)
            updateReservationEntry()
        }
        reserveSubmitButton.setOnClickListener { reserveEnteredSong() }
        updateReservationEntry()
        setReservationMode(titleMode = false)
    }

    private fun showReservationPanel() {
        if (reservePanel.visibility == View.VISIBLE) return
        reservePanelScrim.animate().cancel()
        reservePanelScrim.visibility = View.VISIBLE
        reservePanelScrim.alpha = 0f
        reservePanelScrim.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()
        reservePanel.visibility = View.VISIBLE
        setReservationMode(reservationTitleMode)
        reservePanel.alpha = 0f
        reservePanel.translationX = reservePanel.layoutParams.width.toFloat()
        reservePanel.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220L)
            .start()
    }

    private fun hideReservationPanel(animate: Boolean = true) {
        if (reservePanel.visibility != View.VISIBLE) return
        hideReservationKeyboard()
        if (!animate) {
            reservePanel.animate().cancel()
            reservePanelScrim.animate().cancel()
            reservePanel.visibility = View.GONE
            reservePanel.translationX = 0f
            reservePanelScrim.visibility = View.GONE
            reservePanelScrim.alpha = 1f
            return
        }
        reservePanelScrim.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                reservePanelScrim.visibility = View.GONE
                reservePanelScrim.alpha = 1f
            }
            .start()
        reservePanel.animate()
            .alpha(0f)
            .translationX(reservePanel.width.toFloat())
            .setDuration(180L)
            .withEndAction {
                reservePanel.visibility = View.GONE
                reservePanel.alpha = 1f
                reservePanel.translationX = 0f
            }
            .start()
    }

    private fun updateReservationEntry() {
        reserveNumberDisplay.text = if (reserveDigits.isEmpty()) "------" else reserveDigits
        val song = findReservedSong()
        reserveSongPreview.text = when {
            reserveDigits.isEmpty() -> "Enter a song number"
            song == null -> "Song not found"
            else -> "${song.title} — ${song.artist}"
        }
        reserveSongPreview.setTextColor(if (song == null) color(R.color.muted) else color(R.color.white))
    }

    private fun findReservedSong(): Song? {
        if (reserveDigits.isEmpty()) return null
        val number = reserveDigits.padStart(6, '0')
        val index = catalog.binarySearchBy(number) { it.number }
        return if (index >= 0) catalog[index] else null
    }

    private fun reserveEnteredSong() {
        if (reserveDigits.isEmpty()) {
            showMessage("Enter a song number to reserve.")
            return
        }
        val song = findReservedSong()
        if (song == null) {
            showMessage("Song ${reserveDigits.padStart(6, '0')} was not found.")
            return
        }
        reserveSong(song)
    }

    private fun setReservationMode(titleMode: Boolean) {
        reservationTitleMode = titleMode
        val numberVisibility = if (titleMode) View.GONE else View.VISIBLE
        val titleVisibility = if (titleMode) View.VISIBLE else View.GONE
        reserveNumberDisplay.visibility = numberVisibility
        reserveSongPreview.visibility = numberVisibility
        reserveKeypad.visibility = numberVisibility
        reserveSubmitButton.visibility = numberVisibility
        reserveTitleSearch.visibility = titleVisibility
        reserveTitleResults.visibility = titleVisibility
        reserveNumberModeButton.setBackgroundResource(
            if (titleMode) R.drawable.bg_panel_control else R.drawable.bg_panel_primary
        )
        reserveTitleModeButton.setBackgroundResource(
            if (titleMode) R.drawable.bg_panel_primary else R.drawable.bg_panel_control
        )

        if (titleMode && reservePanel.visibility == View.VISIBLE) {
            reserveTitleSearch.requestFocus()
            reserveTitleSearch.post {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(reserveTitleSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (!titleMode) {
            hideReservationKeyboard()
        }
    }

    private fun filterReservationTitles(query: String) {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) {
            reserveSearchAdapter.update(emptyList())
            return
        }
        val results = catalog.asSequence()
            .filter { song -> terms.all { term -> song.searchKey.contains(term) } }
            .take(MAX_RESERVATION_RESULTS)
            .toList()
        reserveSearchAdapter.update(results)
    }

    private fun reserveSong(song: Song) {
        queue += song
        renderQueue()
        showMessage("Reserved ${song.number}: ${song.title}")
        reserveDigits = ""
        reserveTitleSearch.setText("")
        updateReservationEntry()
        hideReservationPanel()
    }

    private fun hideReservationKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(reserveTitleSearch.windowToken, 0)
        reserveTitleSearch.clearFocus()
    }

    private fun wireScoreScreen() {
        findViewById<Button>(R.id.singAgainButton).setOnClickListener {
            currentSong?.let(::startSong) ?: showHome()
        }
        findViewById<Button>(R.id.nextSongButton).setOnClickListener {
            if (queue.isEmpty()) showHome() else startNextQueuedSong()
        }
        findViewById<Button>(R.id.scoreHomeButton).setOnClickListener { showHome() }
    }

    private fun loadEmbeddedCatalog(): List<Song> = try {
        assets.open(CATALOG_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            buildList {
                reader.readLine()
                while (true) {
                    val line = reader.readLine() ?: break
                    val columns = line.split('\t', limit = 6)
                    if (columns.size == 6) {
                        add(
                            Song(
                                number = columns[0],
                                title = columns[1],
                                artist = columns[2],
                                offset = columns[3].toLong(),
                                packedLength = columns[4].toLong(),
                                rawLength = columns[5].toInt()
                            )
                        )
                    }
                }
            }
        }
    } catch (error: Exception) {
        showMessage("Could not load the embedded song catalog.")
        emptyList()
    }

    private fun filterSongs(query: String) {
        applySongFilters(query)
    }

    private fun applySongFilters(query: String = searchInput.text.toString()) {
        val source = when (activeLibraryFilter) {
            LibraryFilter.ALL -> catalog
            LibraryFilter.LANGUAGE -> {
                val group = selectedLanguageGroup
                if (group == null) catalog else catalog.filter { languageGroupFor(it) == group }
            }
            LibraryFilter.FAVORITES -> catalog.filter { it.number in favoriteSongNumbers }
            LibraryFilter.RECENT -> recentSongNumbers.mapNotNull(catalogByNumber::get)
        }
        val normalized = query.trim().lowercase(Locale.ROOT)
        renderSongs(if (normalized.isEmpty()) source else source.filter { it.searchKey.contains(normalized) })
        updateLibraryFilterUi()
    }

    private fun loadLibraryState() {
        val preferences = getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
        favoriteSongNumbers.clear()
        favoriteSongNumbers += preferences.getStringSet(FAVORITES_KEY, emptySet()).orEmpty()
            .filter(catalogByNumber::containsKey)
        recentSongNumbers.clear()
        recentSongNumbers += preferences.getString(RECENTS_KEY, "")
            .orEmpty()
            .split(',')
            .filter(catalogByNumber::containsKey)
            .distinct()
            .take(MAX_RECENT_SONGS)
    }

    private fun persistFavorites() {
        getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putStringSet(FAVORITES_KEY, favoriteSongNumbers.toSet())
            .apply()
    }

    private fun persistRecents() {
        getSharedPreferences(LIBRARY_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(RECENTS_KEY, recentSongNumbers.joinToString(","))
            .apply()
    }

    private fun toggleFavorite(song: Song) {
        val added = if (song.number in favoriteSongNumbers) {
            favoriteSongNumbers.remove(song.number)
            false
        } else {
            favoriteSongNumbers.add(song.number)
            true
        }
        persistFavorites()
        applySongFilters()
        showMessage(if (added) "${song.title} added to Favorites" else "${song.title} removed from Favorites")
    }

    private fun recordRecentSong(song: Song) {
        recentSongNumbers.remove(song.number)
        recentSongNumbers.add(0, song.number)
        while (recentSongNumbers.size > MAX_RECENT_SONGS) {
            recentSongNumbers.removeAt(recentSongNumbers.lastIndex)
        }
        persistRecents()
        if (activeLibraryFilter == LibraryFilter.RECENT) applySongFilters() else updateLibraryFilterUi()
    }

    private fun showLanguageFilterDialog() {
        val counts = catalog.groupingBy(::languageGroupFor).eachCount()
        val groups = SongLanguageGroup.entries.filter { counts.getOrDefault(it, 0) > 0 }
        val labels = buildList {
            add("All scripts (${catalog.size})")
            groups.forEach { group -> add("${group.displayName} (${counts.getOrDefault(group, 0)})") }
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a language script")
            .setSingleChoiceItems(
                labels.toTypedArray(),
                if (activeLibraryFilter == LibraryFilter.LANGUAGE) groups.indexOf(selectedLanguageGroup) + 1 else 0
            ) { dialog, which ->
                selectedLanguageGroup = if (which == 0) null else groups[which - 1]
                activeLibraryFilter = if (which == 0) LibraryFilter.ALL else LibraryFilter.LANGUAGE
                searchInput.setText("")
                applySongFilters()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun languageGroupFor(song: Song): SongLanguageGroup {
        var hasLatin = false
        var hasJapaneseKana = false
        var hasKorean = false
        var hasHan = false
        var hasCyrillic = false
        var hasArabic = false
        "${song.title} ${song.artist}".forEach { character ->
            when (Character.UnicodeScript.of(character.code)) {
                Character.UnicodeScript.LATIN -> hasLatin = true
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> hasJapaneseKana = true
                Character.UnicodeScript.HANGUL -> hasKorean = true
                Character.UnicodeScript.HAN -> hasHan = true
                Character.UnicodeScript.CYRILLIC -> hasCyrillic = true
                Character.UnicodeScript.ARABIC -> hasArabic = true
                else -> Unit
            }
        }
        return when {
            hasJapaneseKana -> SongLanguageGroup.JAPANESE
            hasKorean -> SongLanguageGroup.KOREAN
            hasHan -> SongLanguageGroup.CJK
            hasCyrillic -> SongLanguageGroup.CYRILLIC
            hasArabic -> SongLanguageGroup.ARABIC
            hasLatin -> SongLanguageGroup.LATIN
            else -> SongLanguageGroup.OTHER
        }
    }

    private fun updateLibraryFilterUi() {
        allSongsFilter.text = "♫  All Songs (${catalog.size})"
        languageFilter.text = selectedLanguageGroup?.let { "◎  ${it.displayName}" } ?: "◎  Languages"
        favoritesFilter.text = "♥  Favorites (${favoriteSongNumbers.size})"
        recentFilter.text = "◷  Recent (${recentSongNumbers.size})"
        mapOf(
            allSongsFilter to LibraryFilter.ALL,
            languageFilter to LibraryFilter.LANGUAGE,
            favoritesFilter to LibraryFilter.FAVORITES,
            recentFilter to LibraryFilter.RECENT
        ).forEach { (view, filter) ->
            view.setBackgroundResource(
                if (activeLibraryFilter == filter) R.drawable.bg_home_filter_selected else R.drawable.bg_home_filter
            )
            view.setTypeface(view.typeface, if (activeLibraryFilter == filter) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderSongs(songs: List<Song>) {
        songAdapter.update(songs)
    }

    private fun addToQueue(song: Song) {
        queue += song
        renderQueue()
        showMessage("${song.title} added to the queue")
    }

    private fun renderQueue() {
        publishedQueueSize = queue.size
        queueList.removeAllViews()
        queueBadge.text = "Queue  ${queue.size}"
        playerQueueBadge.text = "Queue ${queue.size}"
        val nextSong = queue.firstOrNull()
        playerReserveCount.text = queue.size.toString()
        playerReserveBadge.isSelected = false
        playerReserveBadge.text = if (nextSong == null) {
            "Next: none"
        } else {
            val preview = "Next: ${nextSong.title} — ${nextSong.artist}"
            "$preview     •     $preview     •     $preview"
        }
        playerReserveBadge.post {
            playerReserveBadge.isSelected = nextSong != null
        }
        if (queue.isEmpty()) {
            queueList.addView(simpleText("Queue is empty\nTap + beside a song to add it.", color(R.color.muted), 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(18), dp(8), dp(18))
            })
            return
        }
        queue.forEachIndexed { index, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(3), dp(3), dp(3))
                setBackgroundResource(R.drawable.bg_home_queue_item)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply {
                    bottomMargin = dp(5)
                }
            }
            row.addView(simpleText("${index + 1}.  ${song.title}", Color.WHITE, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { startQueuedSongAt(index) }
            })
            row.addView(simpleText("×", color(R.color.pink), 23f).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_home_icon_button)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                setOnClickListener {
                    queue.removeAt(index)
                    renderQueue()
                }
            })
            queueList.addView(row)
        }
    }

    private fun playEnteredNumber() {
        val entered = songNumberInput.text.toString().trim()
        if (entered.isEmpty()) {
            showMessage("Enter a song number.")
            return
        }
        val number = entered.padStart(6, '0')
        val index = catalog.binarySearchBy(number) { it.number }
        if (index < 0) showMessage("Song $number was not found.") else startSong(catalog[index])
    }

    private fun startNextQueuedSong() {
        if (queue.isEmpty()) {
            showMessage("Add a song to the queue first.")
            return
        }
        startSong(queue.removeAt(0))
        renderQueue()
    }

    private fun startQueuedSongAt(index: Int) {
        if (index !in queue.indices) return
        startSong(queue.removeAt(index))
        renderQueue()
    }

    private fun startSong(song: Song) {
        hideReservationPanel(animate = false)
        releasePlayer()
        releaseBackgroundVideo()
        currentSong = song
        recordRecentSong(song)
        selectedBackgroundAsset = resolveBackgroundVideo(song)
        currentDurationSeconds = DEFAULT_DURATION_SECONDS
        elapsedSeconds = 0
        playbackPositionMs = 0L
        timedLyrics = emptyList()
        lyricDisplayIndices = emptyList()
        displayedLyricIndex = Int.MIN_VALUE
        displayedHighlightEnd = -1
        displayedLyricMode = LYRIC_MODE_UNSET
        keyShift = 0
        tempoPercent = 100
        isPlaying = false
        pauseButton.text = "Loading…"
        playerSongTitle.text = "${song.title} — ${song.artist}"
        currentLyric.text = "♪  ${song.title}  ♪"
        nextLyric.text = "Loading embedded MIDI instrumental…"
        totalTime.text = "--:--"
        updateMusicControls()
        updatePlaybackUi()
        screens.displayedChild = SCREEN_PLAYER
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicrophoneMonitor()
        }
        prepareBackgroundVideo()
        prepareEmbeddedMidi(song)
    }

    private fun prepareBackgroundVideo() {
        val assetName = selectedBackgroundAsset ?: run {
            backgroundVideoSurface.visibility = View.INVISIBLE
            return
        }
        backgroundVideoSurface.visibility = View.VISIBLE
        if (!videoSurfaceReady || backgroundVideoPlayer != null || screens.displayedChild != SCREEN_PLAYER) return

        try {
            val descriptor = assets.openFd(assetName)
            val player = MediaPlayer()
            backgroundVideoPlayer = player
            player.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            player.setDisplay(backgroundVideoSurface.holder)
            player.setVolume(0f, 0f)
            player.isLooping = true
            player.setOnPreparedListener { prepared ->
                if (backgroundVideoPlayer === prepared && screens.displayedChild == SCREEN_PLAYER) {
                    prepared.start()
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                if (backgroundVideoPlayer === failed) releaseBackgroundVideo()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releaseBackgroundVideo()
        }
    }

    private fun prepareEmbeddedMidi(song: Song) {
        try {
            val cachedMidi = File(cacheDir, "karaoke-current.mid")
            assets.openFd(PACK_ASSET).use { packDescriptor ->
                FileInputStream(packDescriptor.fileDescriptor).use { packInput ->
                    packInput.channel.position(packDescriptor.startOffset + song.offset)
                    LimitedInputStream(packInput, song.packedLength).use { compressedSong ->
                        GZIPInputStream(compressedSong).use { midiInput ->
                            cachedMidi.outputStream().use { output -> midiInput.copyTo(output) }
                        }
                    }
                }
            }
            if (cachedMidi.length() != song.rawLength.toLong()) {
                throw IllegalStateException("Extracted MIDI length does not match the catalog")
            }
            timedLyrics = runCatching { MidiLyricsParser.parse(cachedMidi) }.getOrDefault(emptyList())
            lyricDisplayIndices = buildLyricDisplayIndices(timedLyrics)
            displayedLyricIndex = Int.MIN_VALUE
            displayedHighlightEnd = -1
            displayedLyricMode = LYRIC_MODE_UNSET
            updateLyrics(0L)
            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(cachedMidi.absolutePath)
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer !== prepared) return@setOnPreparedListener
                currentDurationSeconds = (prepared.duration / 1000).coerceAtLeast(1)
                totalTime.text = formatTime(currentDurationSeconds)
                pauseButton.text = "Pause"
                isPlaying = true
                playbackPositionMs = 0L
                prepared.start()
                updatePlaybackUi()
                updateLyrics(0L)
            }
            player.setOnCompletionListener {
                if (screens.displayedChild == SCREEN_PLAYER) showScore()
            }
            player.setOnErrorListener { _, _, _ ->
                usePlaybackFallback("This MIDI could not be played on this device.")
                true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            usePlaybackFallback("Unable to open this embedded MIDI file.")
        }
    }

    private fun usePlaybackFallback(message: String) {
        releasePlayer()
        currentDurationSeconds = DEFAULT_DURATION_SECONDS
        totalTime.text = formatTime(currentDurationSeconds)
        nextLyric.text = "Playback unavailable • interface preview continues"
        pauseButton.text = "Pause"
        isPlaying = true
        showMessage(message)
    }

    private fun updatePlaybackUi() {
        val safePositionMs = playbackPositionMs.coerceIn(0L, currentDurationSeconds * 1000L)
        val safeElapsed = (safePositionMs / 1000L).toInt()
        songProgress.progress = ((safePositionMs / (currentDurationSeconds * 1000f)) * 1000).toInt()
        elapsedTime.text = formatTime(safeElapsed)
    }

    private fun updateLyrics(positionMs: Long) {
        val song = currentSong ?: return
        if (timedLyrics.isEmpty() || lyricDisplayIndices.isEmpty()) {
            if (displayedLyricMode != LYRIC_MODE_NO_LYRICS) {
                currentLyric.text = "♪  ${song.title}  ♪"
                nextLyric.text = "Instrumental only • no embedded lyrics"
                displayedLyricIndex = NO_LYRICS_INDEX
                displayedHighlightEnd = -1
                displayedLyricMode = LYRIC_MODE_NO_LYRICS
            }
            return
        }

        var low = 0
        var high = timedLyrics.lastIndex
        var activeIndex = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (timedLyrics[middle].timeMs <= positionMs) {
                activeIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        var renderIndex = activeIndex
        var renderMode = LYRIC_MODE_ACTIVE
        if (activeIndex < 0) {
            renderIndex = 0
            renderMode = if (timedLyrics.first().timeMs - positionMs <= LYRIC_PREVIEW_LEAD_MS) {
                LYRIC_MODE_PREVIEW
            } else {
                LYRIC_MODE_CLEAR
            }
        } else {
            val activeLine = timedLyrics[activeIndex]
            val nextIndex = (activeIndex + 1).takeIf { it < timedLyrics.size }
            val releaseTime = lyricReleaseTime(activeLine)
            if (positionMs >= releaseTime) {
                renderMode = when {
                    nextIndex == null -> LYRIC_MODE_CLEAR
                    timedLyrics[nextIndex].timeMs - positionMs > LYRIC_PREVIEW_LEAD_MS -> LYRIC_MODE_CLEAR
                    timedLyrics[nextIndex].timeMs > positionMs -> LYRIC_MODE_PREVIEW
                    else -> LYRIC_MODE_ACTIVE
                }
                if (renderMode == LYRIC_MODE_PREVIEW && nextIndex != null) renderIndex = nextIndex
            }
        }

        if (renderMode == LYRIC_MODE_CLEAR) {
            if (displayedLyricMode != LYRIC_MODE_CLEAR) {
                currentLyric.text = ""
                nextLyric.text = ""
                displayedLyricIndex = renderIndex
                displayedHighlightEnd = -1
                displayedLyricMode = LYRIC_MODE_CLEAR
            }
            return
        }

        val highlightEnd = if (renderMode == LYRIC_MODE_ACTIVE && renderIndex >= 0) {
            timedLyrics[renderIndex].segments
                .takeWhile { segment -> segment.timeMs <= positionMs }
                .lastOrNull()
                ?.endCharacter
                ?: 0
        } else 0
        if (
            renderIndex == displayedLyricIndex &&
            highlightEnd == displayedHighlightEnd &&
            renderMode == displayedLyricMode
        ) return

        val activeDisplayPosition = findLyricDisplayPosition(renderIndex)
        val pageStartPosition = if (activeDisplayPosition < 0) 0 else (activeDisplayPosition / 2) * 2
        val firstLine = lyricDisplayIndices.getOrNull(pageStartPosition)
            ?.let(timedLyrics::get)
        val secondLine = lyricDisplayIndices.getOrNull(pageStartPosition + 1)
            ?.let(timedLyrics::get)

        if (renderMode == LYRIC_MODE_PREVIEW || activeDisplayPosition < 0) {
            currentLyric.text = firstLine?.text.orEmpty()
            nextLyric.text = secondLine?.text.orEmpty()
        } else if (activeDisplayPosition == pageStartPosition) {
            currentLyric.text = styleLyricLine(firstLine?.text.orEmpty(), highlightEnd)
            nextLyric.text = secondLine?.text.orEmpty()
        } else {
            val firstText = firstLine?.text.orEmpty()
            currentLyric.text = styleLyricLine(firstText, firstText.length)
            nextLyric.text = styleLyricLine(secondLine?.text.orEmpty(), highlightEnd)
        }
        displayedLyricIndex = renderIndex
        displayedHighlightEnd = highlightEnd
        displayedLyricMode = renderMode
    }

    private fun lyricReleaseTime(line: MidiLyricsParser.TimedLine): Long {
        val finalSegmentTime = line.segments.lastOrNull()?.timeMs ?: line.timeMs
        val estimatedReadingTime = (line.text.length * LYRIC_CHARACTER_DURATION_MS)
            .coerceIn(MIN_LYRIC_HOLD_MS, MAX_LYRIC_HOLD_MS)
        return maxOf(
            finalSegmentTime + LYRIC_SEGMENT_TAIL_MS,
            line.timeMs + estimatedReadingTime
        )
    }

    private fun buildLyricDisplayIndices(lines: List<MidiLyricsParser.TimedLine>): List<Int> {
        var previousText = ""
        return buildList {
            lines.forEachIndexed { index, line ->
                val normalizedText = normalizeLyricText(line.text)
                if (normalizedText.isNotEmpty() && normalizedText != previousText) {
                    add(index)
                    previousText = normalizedText
                }
            }
        }
    }

    private fun findLyricDisplayPosition(rawIndex: Int): Int {
        if (rawIndex < 0) return -1
        var low = 0
        var high = lyricDisplayIndices.lastIndex
        var selected = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lyricDisplayIndices[middle] <= rawIndex) {
                selected = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return selected
    }

    private fun styleLyricLine(text: String, highlightEnd: Int): SpannableString {
        val styledLine = SpannableString(text)
        if (styledLine.isEmpty()) return styledLine
        styledLine.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0,
            styledLine.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (highlightEnd > 0) {
            styledLine.setSpan(
                ForegroundColorSpan(color(R.color.blue)),
                0,
                highlightEnd.coerceAtMost(styledLine.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return styledLine
    }

    private fun normalizeLyricText(text: String): String =
        text.trim().replace(LYRIC_WHITESPACE_REGEX, " ").lowercase(Locale.ROOT)

    private fun updateMusicControls() {
        val keyText = if (keyShift > 0) "+$keyShift" else keyShift.toString()
        keyValue.text = keyText
        tempoValue.text = "$tempoPercent%"
        micVolumeValue.text = "$micVolumePercent%"
        micAudioTrack?.setVolume(micVolumePercent / 100f)
    }

    private fun ensureMicrophoneMonitor() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicrophoneMonitor()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    private fun startMicrophoneMonitor() {
        if (micMonitorRunning || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val sampleRate = 44_100
        val recordBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val playbackBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (recordBufferSize <= 0 || playbackBufferSize <= 0) {
            showMessage("Microphone monitoring is not supported on this device.")
            return
        }
        val bufferSize = maxOf(recordBufferSize, playbackBufferSize, 4096)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (_: Exception) {
            showMessage("Unable to open the microphone.")
            return
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
            recorder.release()
            track.release()
            showMessage("Unable to start microphone monitoring.")
            return
        }
        try {
            track.setVolume(micVolumePercent / 100f)
            recorder.startRecording()
            track.play()
        } catch (_: Exception) {
            recorder.release()
            track.release()
            showMessage("Unable to start microphone monitoring.")
            return
        }
        micAudioRecord = recorder
        micAudioTrack = track
        micMonitorRunning = true
        micMonitorThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(bufferSize / 2)
            while (micMonitorRunning) {
                val sampleCount = runCatching {
                    recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
                if (sampleCount > 0 && micMonitorRunning) {
                    runCatching {
                        track.write(samples, 0, sampleCount, AudioTrack.WRITE_BLOCKING)
                    }
                }
            }
        }.apply {
            name = "KaraokeMicMonitor"
            isDaemon = true
            start()
        }
    }

    private fun stopMicrophoneMonitor() {
        micMonitorRunning = false
        micAudioRecord?.let { recorder ->
            runCatching { recorder.stop() }
            recorder.release()
        }
        micAudioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
        micAudioRecord = null
        micAudioTrack = null
        micMonitorThread?.interrupt()
        micMonitorThread = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (screens.displayedChild == SCREEN_PLAYER) startMicrophoneMonitor()
        } else {
            showMessage("Microphone permission is required for mic volume control.")
        }
    }

    private fun showScore() {
        val song = currentSong ?: return
        hideReservationPanel(animate = false)
        stopMicrophoneMonitor()
        isPlaying = false
        releasePlayer()
        releaseBackgroundVideo()
        val seed = song.number.toIntOrNull() ?: 1
        val pitch = 82 + (seed % 12)
        val rhythm = 80 + ((seed / 3) % 14)
        val stability = 81 + ((seed / 7) % 13)
        val total = (pitch + rhythm + stability) / 3
        findViewById<TextView>(R.id.totalScore).text = total.toString()
        findViewById<TextView>(R.id.pitchScore).text = pitch.toString()
        findViewById<TextView>(R.id.rhythmScore).text = rhythm.toString()
        findViewById<TextView>(R.id.stabilityScore).text = stability.toString()
        findViewById<TextView>(R.id.scoredSongTitle).text = "♪  ${song.title} — ${song.artist}  ♪"
        findViewById<Button>(R.id.nextSongButton).text = if (queue.isEmpty()) "Back Home" else "Next Song  ▶"
        screens.displayedChild = SCREEN_SCORE
        findViewById<View>(R.id.scoreMedallion).apply {
            animate().cancel()
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(520L)
                .start()
        }
    }

    private fun showHome() {
        hideReservationPanel(animate = false)
        stopMicrophoneMonitor()
        isPlaying = false
        releasePlayer()
        releaseBackgroundVideo()
        screens.displayedChild = SCREEN_HOME
        renderQueue()
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.reset() }
            player.release()
        }
        mediaPlayer = null
    }

    private fun releaseBackgroundVideo() {
        backgroundVideoPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.reset() }
            player.release()
        }
        backgroundVideoPlayer = null
    }

    private class LimitedInputStream(
        source: InputStream,
        private var remaining: Long
    ) : InputStream() {
        private val input = source

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = input.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val allowed = minOf(length.toLong(), remaining).toInt()
            val read = input.read(buffer, offset, allowed)
            if (read > 0) remaining -= read.toLong()
            return read
        }

        override fun close() = Unit
    }

    @Deprecated("Handled for the framework-only prototype")
    override fun onBackPressed() {
        if (screens.displayedChild != SCREEN_HOME) showHome() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTick)
        browserRemoteServer?.stop()
        browserRemoteServer = null
        stopMicrophoneMonitor()
        releasePlayer()
        releaseBackgroundVideo()
        super.onDestroy()
    }

    private inner class ReserveSearchAdapter : BaseAdapter() {
        private var songs: List<Song> = emptyList()

        fun update(updatedSongs: List<Song>) {
            songs = updatedSongs
            notifyDataSetChanged()
        }

        override fun getCount(): Int = songs.size
        override fun getItem(position: Int): Song = songs[position]
        override fun getItemId(position: Int): Long = songs[position].number.toLongOrNull() ?: position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
            val row = (recycled as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(6), dp(4))
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            val song = getItem(position)
            row.text = "${song.number}  ${song.title}\n${song.artist}"
            return row
        }
    }

    private inner class SongAdapter(initialSongs: List<Song>) : BaseAdapter() {
        private var songs: List<Song> = initialSongs
        fun update(updatedSongs: List<Song>) {
            songs = updatedSongs
            notifyDataSetChanged()
        }
        override fun getCount(): Int = songs.size
        override fun getItem(position: Int): Song = songs[position]
        override fun getItemId(position: Int): Long = songs[position].number.toLongOrNull() ?: position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holder: SongRowHolder
            if (recycled is LinearLayout && recycled.tag is SongRowHolder) {
                row = recycled
                holder = recycled.tag as SongRowHolder
            } else {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                    setBackgroundResource(R.drawable.bg_home_row)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                }
                val number = simpleText("", color(R.color.cyan), 15f).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_home_number_badge)
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(34))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val title = simpleText("", Color.WHITE, 15f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
                }
                val artist = simpleText("", color(R.color.muted), 14f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f)
                }
                val favorite = simpleText("♡", color(R.color.muted), 25f).apply {
                    gravity = Gravity.CENTER
                    isFocusable = false
                    setBackgroundResource(R.drawable.bg_home_icon_button)
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                }
                val add = simpleText("+", Color.WHITE, 24f).apply {
                    gravity = Gravity.CENTER
                    isFocusable = false
                    elevation = dp(3).toFloat()
                    setBackgroundResource(R.drawable.bg_home_add_button)
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                }
                row.addView(number)
                row.addView(title)
                row.addView(artist)
                row.addView(favorite)
                row.addView(add)
                holder = SongRowHolder(number, title, artist, favorite, add)
                row.tag = holder
            }
            val song = getItem(position)
            holder.number.text = song.number
            holder.title.text = song.title
            holder.artist.text = song.artist
            val isFavorite = song.number in favoriteSongNumbers
            holder.favorite.text = if (isFavorite) "♥" else "♡"
            holder.favorite.setTextColor(if (isFavorite) color(R.color.pink) else color(R.color.muted))
            holder.favorite.setBackgroundResource(
                if (isFavorite) R.drawable.bg_home_favorite_active else R.drawable.bg_home_icon_button
            )
            holder.favorite.contentDescription = if (isFavorite) {
                "Remove ${song.title} from Favorites"
            } else {
                "Add ${song.title} to Favorites"
            }
            holder.favorite.setOnClickListener { toggleFavorite(song) }
            holder.add.contentDescription = "Add ${song.title} to queue"
            holder.add.setOnClickListener { addToQueue(song) }
            return row
        }
    }

    private fun simpleText(value: String, textColor: Int, sizeSp: Float) = TextView(this).apply {
        text = value
        setTextColor(textColor)
        textSize = sizeSp
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    @Suppress("DEPRECATION")
    private fun color(resource: Int): Int = resources.getColor(resource)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCREEN_HOME = 0
        private const val SCREEN_PLAYER = 1
        private const val SCREEN_SCORE = 2
        private const val DEFAULT_DURATION_SECONDS = 240
        private const val PROGRESS_TICK_MS = 200L
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val NO_LYRICS_INDEX = -2
        private const val LYRIC_MODE_UNSET = -1
        private const val LYRIC_MODE_ACTIVE = 0
        private const val LYRIC_MODE_PREVIEW = 1
        private const val LYRIC_MODE_CLEAR = 2
        private const val LYRIC_MODE_NO_LYRICS = 3
        private const val LYRIC_PREVIEW_LEAD_MS = 4_000L
        private const val LYRIC_CHARACTER_DURATION_MS = 90L
        private const val MIN_LYRIC_HOLD_MS = 2_500L
        private const val MAX_LYRIC_HOLD_MS = 7_000L
        private const val LYRIC_SEGMENT_TAIL_MS = 1_800L
        private const val MAX_RESERVATION_RESULTS = 30
        private const val MAX_BACKGROUND_LABEL_LENGTH = 24
        private const val MAX_RECENT_SONGS = 50
        private const val REMOTE_PORT = 8765
        private const val MIN_REMOTE_QUEUE_LIMIT = 1
        private const val MAX_REMOTE_QUEUE_LIMIT = 50
        private const val DEFAULT_REMOTE_QUEUE_LIMIT = 15
        private const val REMOTE_START_TIMEOUT_MS = 2_000L
        private const val REMOTE_RESERVATION_TIMEOUT_MS = 3_000L
        private const val LIBRARY_PREFERENCES = "karaoke_library"
        private const val FAVORITES_KEY = "favorite_song_numbers"
        private const val RECENTS_KEY = "recent_song_numbers"
        private const val BACKGROUND_VIDEO_KEY = "background_video"
        private const val REMOTE_ENABLED_KEY = "browser_remote_enabled"
        private const val REMOTE_QUEUE_LIMIT_KEY = "browser_remote_queue_limit"
        private const val VIDEO_SELECTION_AUTO = "__auto__"
        private const val VIDEO_SELECTION_OFF = "__off__"
        private const val CATALOG_ASSET = "song_catalog.tsv"
        private const val PACK_ASSET = "songs.kpack"
        private val LYRIC_WHITESPACE_REGEX = Regex("\\s+")
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".webm", ".mkv", ".3gp")
    }
}
