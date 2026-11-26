package com.baksotumpah.ziptonton

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.PopupMenu
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_KEY = "key"
        const val EXTRA_ENTRY = "entry"
        private const val SAVE_EVERY_MS = 2_000L
        private const val SCENE_EVERY_MS = 10_000L
        private const val ACTION_PIP = "com.baksotumpah.ziptonton.PIP_TOGGLE"
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }

    private enum class Rotate(val label: Int) { VIDEO(R.string.rot_video), LAND(R.string.rot_land), PORT(R.string.rot_port), AUTO(R.string.rot_auto) }
    private enum class Fit(val label: Int, val mode: Int) {
        FIT(R.string.fit_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
        ZOOM(R.string.fit_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        FILL(R.string.fit_fill, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    }
    private enum class Drag { NONE, SEEK, BRIGHT, VOLUME }

    private lateinit var store: Store
    private lateinit var zipUri: Uri
    private lateinit var filmKey: String
    private lateinit var audio: AudioManager

    // view
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var titleView: TextView
    private lateinit var partView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var speedBtn: TextView
    private lateinit var speedPanel: LinearLayout
    private lateinit var indicator: View
    private lateinit var indIcon: ImageView
    private lateinit var indText: TextView
    private lateinit var indSub: TextView
    private lateinit var indBar: SegmentBar
    private lateinit var tapLeft: TextView
    private lateinit var tapRight: TextView
    private lateinit var unlockBtn: View
    private lateinit var modeLabel: TextView
    private lateinit var restartBtn: MaterialButton
    private lateinit var introBtn: TextView
    private lateinit var ccBtn: TextView
    private var allSubs: List<SubFile> = emptyList()
    private var subApplied = -1
    private lateinit var skipIntro: MaterialButton
    private lateinit var nextBox: View
    private lateinit var nextText: TextView
    private var countdown = 0

    private var player: ExoPlayer? = null
    private var videos = listOf<ZipItem>()
    private var currentIdx = -1
    private var lastPos = 0L
    private var lastDur = 0L
    private var locked = false
    private var userSeeking = false
    private var rotate = Rotate.VIDEO
    private var fit = Fit.FIT
    private var videoW = 16
    private var videoH = 9
    private var inPip = false
    private var pipExitPending = false

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 500)
        }
    }
    private val saver = object : Runnable {
        override fun run() {
            saveNow()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }
    private val sceneTaker = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) captureScene()
            handler.postDelayed(this, SCENE_EVERY_MS)
        }
    }
    private val hideControls = Runnable { setControls(false) }
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }
    private val hideTaps = Runnable { tapLeft.visibility = View.GONE; tapRight.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockBtn.visibility = View.GONE }
    private val hideMode = Runnable { modeLabel.visibility = View.GONE }
    private val hideRestart = Runnable { restartBtn.visibility = View.GONE }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP) togglePlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        store = Store(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        zipUri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: run { finish(); return })
        filmKey = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        val entry = intent.getStringExtra(EXTRA_ENTRY)

        bindViews()
        hideSystemBars()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    locked -> flashUnlock()
                    speedPanel.visibility == View.VISIBLE -> speedPanel.visibility = View.GONE
                    else -> finish()
                }
            }
        })

        io.execute {
            val ix = try { Library.loadIndexed(this, zipUri) } catch (e: Exception) { null }
            val v = ix?.videos
            allSubs = Library.subsFor(filmKey) ?: ix?.subs.orEmpty()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (v.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
                    finish(); return@runOnUiThread
                }
                videos = store.applyOrder(filmKey, v)
                startPlayer(v.indexOfFirst { it.name == entry }.coerceAtLeast(0))
            }
        }
    }

    // ---------------- View & kontrol ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        controls = findViewById(R.id.controls)
        titleView = findViewById(R.id.title)
        partView = findViewById(R.id.part)
        playBtn = findViewById(R.id.play)
        seek = findViewById(R.id.seek)
        posText = findViewById(R.id.pos)
        durText = findViewById(R.id.dur)
        speedBtn = findViewById(R.id.speed)
        speedPanel = findViewById(R.id.speedPanel)
        indicator = findViewById(R.id.indicator)
        indIcon = findViewById(R.id.indIcon)
        indText = findViewById(R.id.indText)
        indSub = findViewById(R.id.indSub)
        indBar = findViewById(R.id.indBar)
        tapLeft = findViewById(R.id.tapLeft)
        tapRight = findViewById(R.id.tapRight)
        unlockBtn = findViewById(R.id.unlock)
        modeLabel = findViewById(R.id.modeLabel)
        restartBtn = findViewById(R.id.restart)
        introBtn = findViewById(R.id.introBtn)
        ccBtn = findViewById(R.id.ccBtn)
        skipIntro = findViewById(R.id.skipIntro)
        nextBox = findViewById(R.id.nextBox)
        nextText = findViewById(R.id.nextText)
        indBar.setTrackColor(0x40FFFFFF)

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        playBtn.setOnClickListener { togglePlay(); bumpControls() }
        findViewById<View>(R.id.rew).setOnClickListener { seekBy(-10_000); bumpControls() }
        findViewById<View>(R.id.ff).setOnClickListener { seekBy(10_000); bumpControls() }
        findViewById<View>(R.id.prev).setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.currentPosition > 3_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
            bumpControls()
        }
        findViewById<View>(R.id.next).setOnClickListener { player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem(); bumpControls() }
        findViewById<View>(R.id.lock).setOnClickListener { setLocked(true) }
        unlockBtn.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.rotate).setOnClickListener { cycleRotate(); bumpControls() }
        findViewById<View>(R.id.fit).setOnClickListener { cycleFit(); bumpControls() }
        speedBtn.setOnClickListener {
            speedPanel.visibility = if (speedPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            bumpControls()
        }
        val pipBtn = findViewById<View>(R.id.pip)
        pipBtn.visibility = if (pipSupported()) View.VISIBLE else View.GONE
        pipBtn.setOnClickListener { if (Build.VERSION.SDK_INT >= 26) enterPip() }
        restartBtn.setOnClickListener { player?.seekTo(0); restartBtn.visibility = View.GONE }
        introBtn.setOnClickListener { showIntroMenu() }
        ccBtn.setOnClickListener { showSubMenu() }
        skipIntro.setOnClickListener {
            player?.seekTo(store.introEnd(filmKey))
            skipIntro.visibility = View.GONE
        }
        findViewById<View>(R.id.nextCancel).setOnClickListener { cancelCountdown(); setControls(true) }

        buildSpeedChips()

        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = player?.duration?.takeIf { it > 0 } ?: return
                    posText.text = Fmt.time(d * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true; handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                val p = player ?: return
                if (p.duration > 0) p.seekTo(p.duration * sb.progress / 1000)
                bumpControls()
            }
        })

        setupGestures(findViewById(R.id.touchLayer))
    }

    private fun buildSpeedChips() {
        speedPanel.removeAllViews()
        val inf = layoutInflater
        for (s in SPEEDS) {
            val chip = inf.inflate(R.layout.chip_speed, speedPanel, false) as TextView
            chip.text = speedLabel(s)
            chip.setOnClickListener {
                player?.playbackParameters = PlaybackParameters(s)
                store.setSpeed(filmKey, s)
                speedBtn.text = speedLabel(s)
                refreshSpeedChips()
                bumpControls()
            }
            speedPanel.addView(chip)
        }
        refreshSpeedChips()
    }

    private fun refreshSpeedChips() {
        val cur = player?.playbackParameters?.speed ?: 1f
        for (i in 0 until speedPanel.childCount) speedPanel.getChildAt(i).isSelected = abs(SPEEDS[i] - cur) < 0.01f
    }

    private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "×"

    private fun setControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        if (locked || inPip) { controls.visibility = View.GONE; return }
        controls.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) speedPanel.visibility = View.GONE
        if (show) bumpControls()
    }

    /** Tunda sembunyi otomatis kontrol 3,5 detik sejak sentuhan terakhir. */
    private fun bumpControls() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && !userSeeking) handler.postDelayed(hideControls, 3_500)
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            controls.visibility = View.GONE
            unlockBtn.visibility = View.GONE
            showMode(getString(R.string.locked))
        } else {
            unlockBtn.visibility = View.GONE
            setControls(true)
        }
    }

    private fun flashUnlock() {
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 2_000)
    }

    private fun showMode(text: String) {
        modeLabel.text = text
        modeLabel.visibility = View.VISIBLE
        handler.removeCallbacks(hideMode)
        handler.postDelayed(hideMode, 1_400)
    }

    private fun cycleRotate() {
        rotate = Rotate.values()[(rotate.ordinal + 1) % Rotate.values().size]
        applyRotate()
        showMode(getString(rotate.label))
    }

    private fun applyRotate() {
        requestedOrientation = when (rotate) {
            Rotate.VIDEO -> if (videoW >= videoH) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Rotate.LAND -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Rotate.PORT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Rotate.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    private fun cycleFit() {
        fit = Fit.values()[(fit.ordinal + 1) % Fit.values().size]
        playerView.resizeMode = fit.mode
        showMode(getString(fit.label))
    }

    // ---------------- Gestur ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures(layer: View) {
        val dp = resources.displayMetrics.density
        val slop = 16 * dp
        val edge = 28 * dp   // jauhi tepi agar tidak bentrok dengan gestur sistem
        var drag = Drag.NONE
        var x0 = 0f; var y0 = 0f
        var nearEdge = false
        var startPos = 0L; var target = 0L
        var startLevel = 0f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) flashUnlock() else setControls(controls.visibility != View.VISIBLE)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) {
                    // Mode kunci: ketuk 2 kali di mana saja = putar/jeda
                    handler.removeCallbacks(hideUnlock)
                    unlockBtn.visibility = View.GONE
                    togglePlay()
                    showMode(getString(if (player?.playWhenReady == true) R.string.play else R.string.pause))
                    return true
                }
                val third = layer.width / 3f
                when {
                    e.x < third -> { seekBy(-10_000); showTap(tapLeft) }
                    e.x > third * 2 -> { seekBy(10_000); showTap(tapRight) }
                    else -> togglePlay()
                }
                return true
            }
        })

        layer.setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            if (locked || inPip) return@setOnTouchListener true
            val w = v.width.toFloat(); val h = v.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = ev.x; y0 = ev.y; drag = Drag.NONE
                    nearEdge = x0 < edge || x0 > w - edge || y0 < edge * 1.5f || y0 > h - edge * 1.5f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - x0; val dy = ev.y - y0
                    if (drag == Drag.NONE && !nearEdge) {
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && (player?.duration ?: 0) > 0) {
                            drag = Drag.SEEK; startPos = player!!.currentPosition; target = startPos
                        } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                            if (x0 < w / 2) {
                                drag = Drag.BRIGHT; startLevel = currentBrightness()
                            } else {
                                drag = Drag.VOLUME
                                startLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            }
                        }
                        if (drag != Drag.NONE) { setControls(false); handler.removeCallbacks(hideIndicator) }
                    }
                    when (drag) {
                        Drag.SEEK -> {
                            val dur = player?.duration ?: 0L
                            // Satu lebar layar = 120 detik
                            target = (startPos + (dx / w * 120_000).toLong()).coerceIn(0, dur)
                            val delta = target - startPos
                            showIndicator(null, (if (delta >= 0) "+" else "−") + Fmt.time(abs(delta)),
                                "${Fmt.time(target)} / ${Fmt.time(dur)}", null)
                        }
                        Drag.BRIGHT -> {
                            val lv = (startLevel - dy / (h * 0.75f)).coerceIn(0.01f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = lv }
                            showIndicator(R.drawable.ic_brightness, "${(lv * 100).toInt()}%", getString(R.string.brightness), lv)
                        }
                        Drag.VOLUME -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val lv = (startLevel - dy / (h * 0.75f)).coerceIn(0f, 1f)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(lv * max), 0)
                            showIndicator(R.drawable.ic_volume, "${(lv * 100).toInt()}%", getString(R.string.volume), lv)
                        }
                        Drag.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drag == Drag.SEEK) player?.seekTo(target)
                    if (drag != Drag.NONE) handler.postDelayed(hideIndicator, 500)
                    drag = Drag.NONE
                }
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0) return w
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
    }

    private fun showIndicator(icon: Int?, text: String, sub: String, level: Float?) {
        indicator.visibility = View.VISIBLE
        indIcon.visibility = if (icon != null) View.VISIBLE else View.GONE
        if (icon != null) indIcon.setImageResource(icon)
        indText.text = text
        indSub.text = sub
        indBar.visibility = if (level != null) View.VISIBLE else View.GONE
        if (level != null) indBar.setParts(listOf(level))
    }

    private fun showTap(v: TextView) {
        v.visibility = View.VISIBLE
        handler.removeCallbacks(hideTaps)
        handler.postDelayed(hideTaps, 650)
    }

    // ---------------- Pemutaran ----------------

    private fun sourceFor(item: ZipItem): MediaSource {
        val factory = DataSource.Factory { ZipEntryDataSource(applicationContext, zipUri, item) }
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaItem = MediaItem.Builder().setUri(Uri.parse("zip://entry/${item.index}")).setMediaId(item.name).build()
        val video = ProgressiveMediaSource.Factory(factory, extractors).createMediaSource(mediaItem)
        val subs = Subtitles.forVideo(item, allSubs, videos.size, zipUri)
        if (subs.isEmpty()) return video
        return MergingMediaSource(video, *subs.mapIndexed { i, s -> subSource(s, i == 0) }.toTypedArray())
    }

    /** Subtitle dibaca langsung dari ZIP (tanpa ekstrak) atau dari file lepas. Gagal baca = video tetap jalan. */
    private fun subSource(s: SubFile, first: Boolean): MediaSource {
        val zu = s.zipUri
        val zi = s.zipItem
        val df: DataSource.Factory =
            if (zu != null && zi != null) DataSource.Factory { ZipEntryDataSource(applicationContext, zu, zi) }
            else DefaultDataSource.Factory(applicationContext)
        val uri = s.uri ?: Uri.parse("zip://sub/${zi?.index ?: 0}")
        val conf = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(s.mime)
            .setLanguage(s.language)
            .setLabel(s.label)
            .setSelectionFlags(if (first) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
        return SingleSampleMediaSource.Factory(df)
            .setTreatLoadErrorsAsEndOfStream(true)
            .createMediaSource(conf, C.TIME_UNSET)
    }

    // ---------------- Subtitle ----------------

    private fun textGroups(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    private fun selectSubtitle(group: Tracks.Group?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (group == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        p.trackSelectionParameters = b.build()
    }

    /** Terapkan pilihan terakhir untuk film ini (mati / bahasa); default: subtitle pertama menyala. */
    private fun applySubPref(tracks: Tracks) {
        val groups = textGroups(tracks)
        if (groups.isEmpty()) return
        val pref = store.subPref(filmKey)
        if (pref == "off") { selectSubtitle(null); return }
        selectSubtitle(groups.firstOrNull { it.getTrackFormat(0).label == pref } ?: groups.first())
    }

    private fun showSubMenu() {
        val p = player ?: return
        handler.removeCallbacks(hideControls)
        val groups = textGroups(p.currentTracks)
        if (groups.isEmpty()) return
        val menu = PopupMenu(this, ccBtn)
        menu.menu.add(0, 0, 0, R.string.sub_off)
        groups.forEachIndexed { i, g ->
            menu.menu.add(0, i + 1, i + 1, g.getTrackFormat(0).label ?: getString(R.string.sub_track, i + 1))
        }
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) {
                selectSubtitle(null)
                store.setSubPref(filmKey, "off")
                showMode(getString(R.string.sub_off))
            } else {
                val g = groups[item.itemId - 1]
                selectSubtitle(g)
                val label = g.getTrackFormat(0).label
                store.setSubPref(filmKey, label)
                showMode(label ?: getString(R.string.sub_track, item.itemId))
            }
            true
        }
        menu.setOnDismissListener { bumpControls() }
        menu.show()
    }

    private fun startPlayer(startIdx: Int) {
        val p = ExoPlayer.Builder(this).build()
        player = p
        playerView.player = p
        p.pauseAtEndOfMediaItems = true   // berhenti di akhir part agar hitung mundur bisa tampil
        val sp = store.speed(filmKey)
        if (sp != 1f) p.playbackParameters = PlaybackParameters(sp)
        speedBtn.text = speedLabel(sp)
        refreshSpeedChips()

        val startItem = videos[startIdx]
        val prog = store.progress(filmKey, startItem.name)
        val startPos = if (prog != null && !prog.finished) prog.positionMs else 0L

        currentIdx = startIdx
        updateTitles()
        p.setMediaSources(videos.map { sourceFor(it) }, startIdx, startPos)
        p.addListener(listener)
        p.prepare()
        p.playWhenReady = true
        if (startPos > 0) showResumed(startPos)
        applyRotate()
        setControls(true)
        handler.post(ticker)
        handler.postDelayed(saver, SAVE_EVERY_MS)
        handler.postDelayed(sceneTaker, 4_000)
    }

    private fun updateTitles() {
        val item = videos.getOrNull(currentIdx) ?: return
        titleView.text = Fmt.stripExt(item.name)
        partView.text = if (videos.size > 1) getString(R.string.part_of, currentIdx + 1, videos.size) else ""
        partView.visibility = if (videos.size > 1) View.VISIBLE else View.GONE
    }

    private fun updateTime() {
        val p = player ?: return
        val dur = p.duration
        if (!userSeeking) {
            posText.text = Fmt.time(p.currentPosition.coerceAtLeast(0))
            seek.progress = if (dur > 0) (p.currentPosition * 1000 / dur).toInt() else 0
            seek.secondaryProgress = if (dur > 0) (p.bufferedPosition * 1000 / dur).toInt() else 0
        }
        durText.text = if (dur > 0) Fmt.time(dur) else "--:--"
        updateSkipIntro()
    }

    private fun updateSkipIntro() {
        val p = player ?: return
        val s = store.introStart(filmKey)
        val e = store.introEnd(filmKey)
        val pos = p.currentPosition
        val show = s >= 0 && e > s && pos >= s && pos < e - 1_000 && p.currentMediaItemIndex == currentIdx
        skipIntro.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showIntroMenu() {
        val p = player ?: return
        handler.removeCallbacks(hideControls)
        val menu = PopupMenu(this, introBtn)
        menu.menu.add(0, 1, 0, R.string.intro_mark_start)
        menu.menu.add(0, 2, 1, R.string.intro_mark_end)
        menu.menu.add(0, 3, 2, R.string.intro_skip90)
        if (store.introStart(filmKey) >= 0 || store.introEnd(filmKey) >= 0) menu.menu.add(0, 4, 3, R.string.intro_clear)
        menu.setOnMenuItemClickListener { item ->
            val pos = p.currentPosition
            when (item.itemId) {
                1 -> { store.setIntroStart(filmKey, pos); showMode(getString(R.string.intro_start_set, Fmt.time(pos))) }
                2 -> {
                    val st = store.introStart(filmKey)
                    if (st in 0 until pos) { store.setIntroEnd(filmKey, pos); showMode(getString(R.string.intro_end_set, Fmt.time(pos))) }
                    else showMode(getString(R.string.intro_bad))
                }
                3 -> seekBy(90_000)
                4 -> { store.clearIntro(filmKey); showMode(getString(R.string.intro_cleared)) }
            }
            true
        }
        menu.setOnDismissListener { bumpControls() }
        menu.show()
    }

    // ---- Lanjut otomatis ke part berikutnya (hitung mundur 5 detik) ----

    private val countdownTick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) goNext()
            else {
                nextText.text = getString(R.string.next_in, countdown)
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private fun startCountdown() {
        countdown = 5
        nextText.text = getString(R.string.next_in, countdown)
        nextBox.visibility = View.VISIBLE
        handler.removeCallbacks(countdownTick)
        handler.postDelayed(countdownTick, 1_000)
    }

    private fun cancelCountdown() {
        handler.removeCallbacks(countdownTick)
        nextBox.visibility = View.GONE
    }

    private fun goNext() {
        cancelCountdown()
        val p = player ?: return
        if (!p.hasNextMediaItem()) return
        lastPos = 0L; lastDur = 0L   // part lama sudah ditandai selesai; jangan ditimpa
        p.seekToNextMediaItem()
        p.play()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playBtn.contentDescription = getString(if (isPlaying) R.string.pause else R.string.play)
            if (isPlaying) bumpControls() else handler.removeCallbacks(hideControls)
            if (Build.VERSION.SDK_INT >= 26 && pipSupported()) setPictureInPictureParams(pipParams())
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) { cancelCountdown(); return }
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                val p = player ?: return
                videos.getOrNull(currentIdx)?.let {
                    store.markFinished(filmKey, it.name, p.duration.takeIf { d -> d > 0 } ?: lastDur)
                }
                if (p.hasNextMediaItem()) startCountdown() else setControls(true)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val has = textGroups(tracks).isNotEmpty()
            ccBtn.visibility = if (has) View.VISIBLE else View.GONE
            val idx = player?.currentMediaItemIndex ?: return
            if (has && subApplied != idx) {   // sekali per part, supaya tidak berulang
                subApplied = idx
                applySubPref(tracks)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                videoH = videoSize.height
                if (rotate == Rotate.VIDEO) applyRotate()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            val p = player ?: return
            val prev = currentIdx
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prev in videos.indices) {
                store.markFinished(filmKey, videos[prev].name, lastDur)
            } else if (prev in videos.indices && lastDur > 0 && lastPos > 0) {
                // Player sudah pindah part, jadi pakai posisi yang tersimpan terakhir
                store.saveProgress(filmKey, videos[prev].name, lastPos, lastDur)
            }
            lastPos = 0L; lastDur = 0L
            currentIdx = p.currentMediaItemIndex
            updateTitles()
            val item = videos.getOrNull(currentIdx) ?: return
            val prog = store.progress(filmKey, item.name)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prog != null && !prog.finished && prog.positionMs > 0) {
                p.seekTo(prog.positionMs)
                showResumed(prog.positionMs)
            }
            handler.removeCallbacks(sceneTaker)
            handler.postDelayed(sceneTaker, 4_000)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                val item = videos.getOrNull(currentIdx) ?: return
                store.markFinished(filmKey, item.name, player?.duration?.takeIf { it > 0 } ?: lastDur)
                setControls(true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            ) getString(R.string.codec_unsupported) else error.cause?.message ?: error.errorCodeName
            Toast.makeText(this@PlayerActivity, getString(R.string.play_error, msg), Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.play(); return }
        if (p.isPlaying) { p.pause(); saveNow(); captureScene() } else p.play()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, d))
        updateTime()
    }

    private fun showResumed(pos: Long) {
        restartBtn.text = getString(R.string.resumed_from, Fmt.time(pos))
        restartBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideRestart)
        handler.postDelayed(hideRestart, 7_000)
    }

    private fun saveNow() {
        val p = player ?: return
        val item = videos.getOrNull(currentIdx) ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        if (p.currentMediaItemIndex != currentIdx) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = p.currentPosition
        if (pos <= 0) return
        lastPos = pos; lastDur = dur
        store.saveProgress(filmKey, item.name, pos, dur)
    }

    /** Simpan cuplikan layar saat ini (untuk kartu "Lanjutkan" di halaman utama). */
    private fun captureScene() {
        val item = videos.getOrNull(currentIdx) ?: return
        val tv = playerView.videoSurfaceView as? TextureView ?: return
        if (!tv.isAvailable || videoW <= 0 || videoH <= 0) return
        val w = 480
        val h = (w * videoH / videoW).coerceIn(1, 960)
        val bmp = try { tv.getBitmap(w, h) } catch (_: Exception) { null } ?: return
        Thumbs.saveScene(applicationContext, filmKey, item.name, bmp)
    }

    // ---------------- Picture-in-picture ----------------

    private fun pipSupported() =
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    @RequiresApi(26)
    private fun pipParams(): PictureInPictureParams {
        var r = Rational(videoW.coerceAtLeast(1), videoH.coerceAtLeast(1))
        if (r.toFloat() > 2.39f) r = Rational(239, 100)
        if (r.toFloat() < 1 / 2.39f) r = Rational(100, 239)
        val playing = player?.isPlaying == true
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val pi = PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val label = getString(if (playing) R.string.pause else R.string.play)
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(r)
            .setActions(listOf(RemoteAction(icon, label, label, pi)))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        return b.build()
    }

    @RequiresApi(26)
    private fun enterPip() {
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8-11: tekan Home saat film diputar -> otomatis jadi jendela kecil
        if (Build.VERSION.SDK_INT in 26..30 && pipSupported() && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            controls.visibility = View.GONE
            indicator.visibility = View.GONE
            restartBtn.visibility = View.GONE
            unlockBtn.visibility = View.GONE
        } else {
            // Kalau setelah ini onResume -> jendela diperbesar; kalau onStop -> jendela ditutup
            pipExitPending = true
            setControls(true)
        }
    }

    override fun onResume() {
        super.onResume()
        pipExitPending = false
    }

    // ---------------- Siklus hidup ----------------

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        saveNow()
        captureScene()
        // Dalam jendela kecil (PiP) film tetap jalan
        if (!(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) player?.pause()
    }

    override fun onStop() {
        super.onStop()
        cancelCountdown()
        saveNow()
        player?.pause()
        // Jendela kecil ditutup (tombol X) -> akhiri pemutar
        if (pipExitPending) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveNow()
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}
        player?.release()
        player = null
        io.shutdown()
        super.onDestroy()
    }
}
