package com.locality.mobile.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.session.MediaSession
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.locality.mobile.LocalityApp
import com.locality.mobile.ui.components.formatDuration
import com.locality.mobile.ui.theme.LocalityTheme
import com.locality.mobile.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import com.locality.mobile.party.PartyMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    // Shared with the Composable: whether PiP is allowed right now (playing) and
    // the current video aspect. `inPip` lets the UI hide its controls in PiP.
    var allowPip = false
    var videoAspect = Rational(16, 9)
    val inPip = mutableStateOf(false)

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive: video owns the whole screen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val forParty = intent.getBooleanExtra(EXTRA_PARTY, false)
        setContent {
            LocalityTheme {
                PlayerScreen(fileId = fileId, forParty = forParty, onExit = { finish() })
            }
        }
    }

    // Leaving the app (Home / recents) while playing shrinks video into PiP so it
    // keeps playing. Audio-only playback continues via the MediaSession service.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    fun enterPipIfPossible() {
        if (!allowPip) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /**
     * Register the current PiP params up front. On Android 12+ this is what makes
     * `setAutoEnterEnabled` work, so the system slides us into PiP on home/swipe
     * by itself — onUserLeaveHint alone is unreliable under gesture navigation.
     * Call whenever playing state or the video aspect changes.
     */
    fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val ratio = if (videoAspect.denominator > 0) videoAspect.numerator.toFloat() / videoAspect.denominator else 16f / 9f
        // Android rejects extreme ratios; fall back to 16:9 outside the allowed band.
        val aspect = if (ratio in 0.42f..2.39f) videoAspect else Rational(16, 9)
        val b = PictureInPictureParams.Builder().setAspectRatio(aspect)
        // Auto-enter only while something is actually playing.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(allowPip)
        return b.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    companion object {
        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_PARTY = "forParty"

        fun intent(context: Context, fileId: Long): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_FILE_ID, fileId)

        /** Play whatever the active party is watching (own copy, or the host's stream). */
        fun partyIntent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_FILE_ID, -1L)
                .putExtra(EXTRA_PARTY, true)
    }
}

private const val PROGRESS_BATCH_MS = 45_000L // spec: batch sync every ~30–60 s

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(fileId: Long, forParty: Boolean = false, onExit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as PlayerActivity
    val app = context.applicationContext as LocalityApp
    val scope = rememberCoroutineScope()
    val inPip by activity.inPip

    // ---- watch party ---------------------------------------------------------
    val partySnap by app.container.party.state.collectAsState()
    val activeParty = partySnap.party

    var source by remember { mutableStateOf<PlaybackSource?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var audioTracks by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    // Remembered audio track is applied once per source (setting it re-fires onTracksChanged).
    var audioApplied by remember { mutableStateOf(false) }

    // ---- subtitles: our own overlay (Media3 text rendering is disabled) -------
    val prefs = remember { context.getSharedPreferences("player", Context.MODE_PRIVATE) }
    var activeSubId by remember { mutableStateOf<Long?>(null) }
    var subCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var subOverlayText by remember { mutableStateOf<String?>(null) }
    var subOffsetMs by remember { mutableLongStateOf(0L) }
    var subPos by remember {
        mutableStateOf(runCatching { SubPosition.valueOf(prefs.getString("subPos", SubPosition.Bottom.name)!!) }.getOrDefault(SubPosition.Bottom))
    }
    var speed by remember { mutableStateOf(prefs.getFloat("speed", 1f)) }

    // gesture indicator state
    var volumeLevel by remember { mutableStateOf<Float?>(null) }
    var brightnessLevel by remember { mutableStateOf<Float?>(null) }
    var seekOffsetSec by remember { mutableStateOf<Int?>(null) }
    var gestureTick by remember { mutableLongStateOf(0L) }

    // marker overlay state
    var introCancelled by remember { mutableStateOf(false) }
    var introSkipDeadline by remember { mutableStateOf<Long?>(null) } // epoch ms when auto-skip fires
    var upNextDismissed by remember { mutableStateOf(false) }
    var upNextShownAt by remember { mutableStateOf<Long?>(null) }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // The player lives in the Activity. A MediaSession attached to it publishes
    // the OS lockscreen / notification media controls and handles media buttons;
    // playback keeps running while the app is backgrounded (and via PiP when the
    // user leaves the app).
    val player = remember {
        val httpFactory = OkHttpDataSource.Factory(app.container.client.okHttp)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            // Hardware decode first; fall back only when a codec genuinely can't.
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .build()
            .apply {
                // Subtitles are drawn by our own overlay — keep Media3's text renderer off.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                setPlaybackSpeed(prefs.getFloat("speed", 1f))
            }
    }
    val mediaSession = remember { MediaSession.Builder(context, player).build() }
    // Chromecast engine (guarded; null on devices without Google Play services).
    val castContext = remember { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    val castPlayer = remember(castContext) { castContext?.let { runCatching { CastPlayer(it) }.getOrNull() } }
    var casting by remember { mutableStateOf(false) }
    // Whichever player transport controls should drive right now (cast or local).
    fun activePlayer(): Player? = if (casting) castPlayer else player

    fun recordNow(watchedOverride: Boolean? = null) {
        val s = source ?: return
        val c = activePlayer() ?: return
        val pos = c.currentPosition / 1000.0
        val dur = if (c.duration > 0) c.duration / 1000.0 else 0.0
        scope.launch {
            app.container.repo.recordProgress(s.fingerprint, s.filename, s.sizeBytes, pos, dur, watchedOverride)
        }
    }

    fun flushProgress() {
        scope.launch { app.container.repo.flushPendingProgress() }
    }

    // Per-show track memory. The local choice (SharedPreferences, keyed by item
    // id) works offline and wins; the server value seeds it and keeps PC + phone
    // consistent when online. subLang uses the sentinel "off".
    fun localAudioPref(itemId: Long?): String? = itemId?.let { prefs.getString("aud_$it", null) }
    fun localSubPref(itemId: Long?): String? = itemId?.let { prefs.getString("sub_$it", null) }
    fun rememberAudioLang(itemId: Long?, lang: String?) {
        if (itemId == null) return
        prefs.edit().putString("aud_$itemId", lang).apply()
        scope.launch { app.container.repo.setTrackPrefs(itemId, audioLang = lang) }
    }
    fun rememberSubLang(itemId: Long?, lang: String?) {
        if (itemId == null) return
        val v = lang ?: "off"
        prefs.edit().putString("sub_$itemId", v).apply()
        scope.launch { app.container.repo.setTrackPrefs(itemId, subLang = v) }
    }
    // 2- vs 3-letter codes (en/eng) and cross-device probes don't always agree,
    // so match loosely rather than exact.
    fun langMatches(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        val x = a.lowercase(); val y = b.lowercase()
        return x.startsWith(y) || y.startsWith(x) || x.take(2) == y.take(2)
    }

    // Build a Cast-friendly MediaItem (URL the Chromecast can reach + MIME +
    // title + the active subtitle sideloaded as a text track).
    fun castMediaItem(s: PlaybackSource): MediaItem {
        val uri = s.mediaItem.localConfiguration?.uri ?: Uri.EMPTY
        val b = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).build())
        val sub = s.subs.firstOrNull { it.id == activeSubId }
        if (sub?.url != null) {
            b.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(sub.lang)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return b.build()
    }

    fun loadSource(id: Long) {
        val local = player
        scope.launch {
            resolveSource(app, id).fold(
                onSuccess = { s ->
                    source = s
                    introCancelled = false
                    introSkipDeadline = null
                    upNextDismissed = false
                    upNextShownAt = null
                    subOffsetMs = 0L
                    audioApplied = false
                    // Remembered subtitle for this show (local pick wins, else server pref).
                    val subPref = localSubPref(s.itemId) ?: s.prefSubLang
                    activeSubId = if (subPref == null || subPref == "off") null
                        else s.subs.firstOrNull { langMatches(it.lang, subPref) }?.id
                    local.setMediaItem(s.mediaItem, s.startPositionMs)
                    local.prepare()
                    val cp = castPlayer
                    if (casting && cp != null && !s.isLocal) {
                        // Keep playing on the cast device (e.g. auto-advance while casting).
                        cp.setMediaItem(castMediaItem(s), s.startPositionMs)
                        cp.playWhenReady = true
                        cp.prepare()
                        local.pause()
                    } else {
                        local.play()
                    }
                },
                onFailure = { loadError = it.message ?: "Could not start playback" },
            )
        }
    }

    fun playNext() {
        val n = source?.next ?: return
        recordNow(watchedOverride = true) // leaving via Up Next = finished
        loadSource(n.fileId)
    }

    /** Opened from the Party tab: play our own copy, or the host's stream. */
    fun loadPartySource() {
        val p = app.container.party.state.value.party
        if (p == null) {
            loadError = "That party has ended"
            return
        }
        val local = player
        scope.launch {
            resolvePartySource(
                app,
                ActivePartyRef(p.title, p.fingerprint, p.hostFileId, p.localFileId, p.hostServer),
            ).fold(
                onSuccess = { s ->
                    source = s
                    introCancelled = false
                    introSkipDeadline = null
                    upNextDismissed = false
                    subOffsetMs = 0L
                    audioApplied = false
                    activeSubId = null
                    local.setMediaItem(s.mediaItem, s.startPositionMs)
                    local.prepare()
                    // Start paused: the party's own state decides when to roll,
                    // so nobody's autoplay yanks everyone else's playhead.
                    local.playWhenReady = false
                },
                onFailure = { loadError = it.message ?: "Could not join the party's video" },
            )
        }
    }

    LaunchedEffect(Unit) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (!casting) isPlaying = playing
                if (!playing) {
                    // batch point: pause/stop
                    recordNow()
                    flushProgress()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                // Re-apply the remembered audio language for this show, once.
                val src = source
                val want = localAudioPref(src?.itemId) ?: src?.prefAudioLang
                if (!audioApplied && want != null && audioTracks.size > 1) {
                    audioTracks.firstOrNull { langMatches(it.getTrackFormat(0).language, want) }?.let { group ->
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                            .build()
                    }
                    audioApplied = true
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    activity.videoAspect = Rational(videoSize.width, videoSize.height)
                    activity.updatePipParams()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!casting && state == Player.STATE_ENDED) {
                    recordNow(watchedOverride = true)
                    flushProgress()
                    val s = source
                    if (s?.next != null && s.autoplayNext && !upNextDismissed) playNext() else onExit()
                }
            }
        })
        if (forParty) loadPartySource() else loadSource(fileId)
    }

    // Final record + release on exit.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            recordNow()
            flushProgress()
            mediaSession.release()
            player.release()
        }
    }

    // Chromecast: hand playback to/from a cast device at the current position.
    androidx.compose.runtime.DisposableEffect(castPlayer) {
        val cp = castPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (casting) isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (casting && state == Player.STATE_ENDED) {
                    recordNow(watchedOverride = true)
                    flushProgress()
                    val s = source
                    if (s?.next != null && s.autoplayNext && !upNextDismissed) playNext()
                }
            }
        }
        cp.addListener(listener)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                val s = source ?: return
                if (s.isLocal) return // a phone-only download isn't reachable by the Chromecast
                cp.setMediaItem(castMediaItem(s), player.currentPosition)
                cp.playWhenReady = true
                cp.prepare()
                player.pause()
                casting = true
                isPlaying = true
            }

            override fun onCastSessionUnavailable() {
                val resumeAt = cp.currentPosition
                casting = false
                player.seekTo(resumeAt)
                player.play()
            }
        })
        onDispose {
            cp.setSessionAvailabilityListener(null)
            cp.removeListener(listener)
            cp.release()
        }
    }

    // ---- party playback sync -------------------------------------------------
    //
    // Same rules as the desktop's partySync.ts: apply an action once (by seq),
    // never act on our own echo, stay quiet briefly after applying one, and
    // ignore everything the player does to itself while starting up.
    var quietUntil by remember { mutableLongStateOf(0L) }
    var settledAt by remember { mutableLongStateOf(0L) }
    var appliedSeq by remember { mutableLongStateOf(-1L) }
    var announced by remember { mutableStateOf(false) }
    var partyError by remember { mutableStateOf<String?>(null) }
    var lastCorrectionAt by remember { mutableLongStateOf(0L) }

    val partyActive = activeParty != null &&
        (forParty || activeParty.localFileId == fileId || activeParty.hostFileId == fileId)

    fun publishParty() {
        if (!partyActive) return
        val now = System.currentTimeMillis()
        if (now < quietUntil || now < settledAt) return // our own echo, or start-up
        val c = activePlayer() ?: return
        val pos = c.currentPosition / 1000.0
        val paused = !c.isPlaying
        scope.launch { app.container.party.publish(pos, paused) }
    }

    LaunchedEffect(partyActive, source?.fileId) {
        if (partyActive) settledAt = System.currentTimeMillis() + SETTLE_MS
    }

    LaunchedEffect(partyActive, activeParty?.playback?.seq, source) {
        val pb = activeParty?.playback ?: return@LaunchedEffect
        if (!partyActive || source == null) return@LaunchedEffect
        // seq 0 is a party nobody has acted in yet: applying it would yank
        // whoever opens their player first back to 0:00.
        if (pb.seq <= 0 || pb.seq <= appliedSeq) return@LaunchedEffect
        appliedSeq = pb.seq
        if (pb.updatedBy != null && pb.updatedBy == partySnap.userId) return@LaunchedEffect
        quietUntil = System.currentTimeMillis() + ECHO_QUIET_MS
        val c = activePlayer() ?: return@LaunchedEffect
        val target = partyPositionNow(pb, System.currentTimeMillis())
        if (kotlin.math.abs(c.currentPosition / 1000.0 - target) > SYNC_TOLERANCE_S) {
            c.seekTo((target * 1000).toLong().coerceAtLeast(0))
            lastCorrectionAt = System.currentTimeMillis()
        }
        if (c.isPlaying == pb.paused) if (pb.paused) c.pause() else c.play()
    }

    /**
     * Keep matching the party after the action has been applied.
     *
     * Applying once isn't enough: seeking a stream costs a second or three of
     * buffering, and the others don't wait — without this the gap that opens
     * during that buffer never closes again. It also re-asserts pause/play, so
     * anything that resumes us locally (audio focus, a session command) is
     * pulled back into line within a second instead of quietly desyncing.
     *
     * Purely local: it never publishes, so correcting ourselves can't drag
     * anyone else around.
     */
    LaunchedEffect(partyActive, activeParty?.playback?.seq) {
        val pb = activeParty?.playback
        if (!partyActive || pb == null || pb.seq <= 0) return@LaunchedEffect
        while (true) {
            delay(RECONCILE_MS)
            val now = System.currentTimeMillis()
            if (now < quietUntil || now < settledAt) continue
            val c = activePlayer() ?: continue
            if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) continue

            // 1. play/pause must match the party, whatever moved us out of it.
            if (pb.paused && c.isPlaying) {
                c.pause()
                continue
            }
            if (!pb.paused && !c.isPlaying && c.playbackState == Player.STATE_READY) {
                c.play()
                continue
            }
            // 2. drift, once we're actually rolling (buffering isn't drift yet).
            if (!pb.paused && c.isPlaying && now - lastCorrectionAt > RESEEK_COOLDOWN_MS) {
                val expected = partyPositionNow(pb, now)
                val diff = expected - c.currentPosition / 1000.0
                if (kotlin.math.abs(diff) > SYNC_TOLERANCE_S) {
                    c.seekTo((expected * 1000).toLong().coerceAtLeast(0))
                    lastCorrectionAt = now
                }
            }
        }
    }

    // Someone has to put a position on the board or a guest joining mid-film has
    // nothing to sync to. That's the host's job — if guests announced too,
    // whoever opened last would drag everyone to wherever they happened to be.
    LaunchedEffect(partyActive, activeParty?.isHost, source) {
        if (!partyActive || activeParty?.isHost != true || announced || source == null) return@LaunchedEffect
        announced = true
        delay(SETTLE_MS)
        val c = activePlayer() ?: return@LaunchedEffect
        app.container.party.publish(c.currentPosition / 1000.0, !c.isPlaying)
    }

    // 1 s UI tick + 45 s progress batching while playing.
    LaunchedEffect(isPlaying) {
        activity.allowPip = isPlaying // only auto-PiP while there's something playing
        activity.updatePipParams()
        var lastBatch = System.currentTimeMillis()
        while (isPlaying) {
            activePlayer()?.let { c ->
                positionMs = c.currentPosition
                durationMs = c.duration.coerceAtLeast(0)
            }
            if (System.currentTimeMillis() - lastBatch >= PROGRESS_BATCH_MS) {
                lastBatch = System.currentTimeMillis()
                recordNow()
                flushProgress()
            }
            delay(1000)
        }
    }

    // Load & parse the selected subtitle track (server VTT or downloaded file).
    LaunchedEffect(activeSubId, source) {
        val ref = source?.subs?.firstOrNull { it.id == activeSubId }
        if (ref == null) {
            subCues = emptyList()
            subOverlayText = null
            return@LaunchedEffect
        }
        subCues = withContext(Dispatchers.IO) {
            try {
                when {
                    ref.path != null -> parseVttFile(File(ref.path))
                    ref.url != null -> app.container.client.okHttp
                        .newCall(Request.Builder().url(ref.url).build())
                        .execute()
                        .use { parseVtt(it.body?.string() ?: "") }
                    else -> emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // Cue clock — fine-grained (the 1 s progress tick is too coarse for subs).
    LaunchedEffect(subCues, subOffsetMs) {
        if (subCues.isEmpty()) {
            subOverlayText = null
            return@LaunchedEffect
        }
        while (true) {
            subOverlayText = activeCueText(subCues, activePlayer()?.currentPosition ?: 0L, subOffsetMs)
            delay(200)
        }
    }

    // Gesture indicators fade ~1 s after the last change.
    LaunchedEffect(gestureTick) {
        if (gestureTick == 0L) return@LaunchedEffect
        delay(1100)
        volumeLevel = null
        brightnessLevel = null
        seekOffsetSec = null
    }

    // Controls auto-hide.
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Intro skip: show a 3 s countdown inside the window; auto-seeks unless tapped.
    val s = source
    val posSec = positionMs / 1000.0
    val inIntro = s?.skipIntro == true && !introCancelled &&
        s.introStart != null && s.introEnd != null &&
        posSec >= s.introStart && posSec < s.introEnd - 1
    LaunchedEffect(inIntro) {
        if (inIntro) {
            val deadline = System.currentTimeMillis() + 3000
            introSkipDeadline = deadline
            delay(3000)
            if (!introCancelled && introSkipDeadline == deadline) {
                source?.introEnd?.let { activePlayer()?.seekTo((it * 1000).toLong()) }
                introSkipDeadline = null
            }
        } else {
            introSkipDeadline = null
        }
    }

    // Up Next overlay at credits_start (fallback: 18 s before the end, as on desktop).
    val creditsAtSec = s?.let { src ->
        src.creditsStart ?: (if (durationMs > 60_000) durationMs / 1000.0 - 18 else null)
    }
    val showUpNext = s?.next != null && !upNextDismissed && creditsAtSec != null && posSec >= creditsAtSec && durationMs > 0
    LaunchedEffect(showUpNext) {
        if (showUpNext) {
            if (upNextShownAt == null) upNextShownAt = System.currentTimeMillis()
            if (s?.autoplayNext == true) {
                delay((s.upNextSeconds * 1000).toLong())
                if (!upNextDismissed) playNext()
            }
        } else {
            upNextShownAt = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            update = { view ->
                // Spec: wake flag only while actively playing.
                view.keepScreenOn = isPlaying
            },
            modifier = Modifier.fillMaxSize(),
        )

        GestureLayer(
            onTap = { controlsVisible = !controlsVisible },
            onDoubleTapSeek = { deltaSec ->
                activePlayer()?.let { c -> c.seekTo((c.currentPosition + deltaSec * 1000L).coerceIn(0, c.duration.coerceAtLeast(0))) }
                seekOffsetSec = deltaSec
                gestureTick = System.currentTimeMillis()
                publishParty()
            },
            onVolumeDelta = { frac ->
                val current = volumeLevel ?: (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
                val next = (current + frac).coerceIn(0f, 1f)
                volumeLevel = next
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (next * maxVolume).roundToInt(), 0)
                gestureTick = System.currentTimeMillis()
            },
            onBrightnessDelta = { frac ->
                val lp = activity.window.attributes
                val current = brightnessLevel ?: (if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f)
                val next = (current + frac).coerceIn(0.02f, 1f)
                brightnessLevel = next
                activity.window.attributes = lp.apply { screenBrightness = next }
                gestureTick = System.currentTimeMillis()
            },
            onSeekPreview = { off ->
                seekOffsetSec = off
                gestureTick = System.currentTimeMillis()
            },
            onSeekCommit = { off ->
                activePlayer()?.let { c -> c.seekTo((c.currentPosition + off * 1000L).coerceIn(0, c.duration.coerceAtLeast(0))) }
                gestureTick = System.currentTimeMillis()
                publishParty()
            },
        )

        // --- subtitle overlay (our renderer: instant offset + position presets) ---
        subOverlayText?.let { line ->
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                Text(
                    line,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = maxHeight * subPos.bottomFraction)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // --- gesture indicators ---
        volumeLevel?.let { LevelIndicator(icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Palette.Heading) }, level = it, modifier = Modifier.align(Alignment.Center)) }
        brightnessLevel?.let { LevelIndicator(icon = { Icon(Icons.Filled.BrightnessMedium, null, tint = Palette.Heading) }, level = it, modifier = Modifier.align(Alignment.Center)) }
        seekOffsetSec?.let { off ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                val target = ((positionMs / 1000).toInt() + off).coerceAtLeast(0)
                Text(
                    "${if (off >= 0) "+" else "−"}${kotlin.math.abs(off)}s  ·  ${formatDuration(target)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Heading,
                )
            }
        }

        // --- intro skip card ---
        AnimatedVisibility(
            visible = introSkipDeadline != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            var remaining by remember { mutableStateOf(3) }
            LaunchedEffect(introSkipDeadline) {
                while (true) {
                    val d = introSkipDeadline ?: break
                    remaining = (((d - System.currentTimeMillis()) / 1000) + 1).toInt().coerceAtLeast(1)
                    delay(200)
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Raised.copy(alpha = 0.95f))
                    .clickable {
                        // Tap CANCELS the skip (desktop behavior).
                        introCancelled = true
                        introSkipDeadline = null
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("Skipping intro in $remaining… tap to watch it", color = Palette.Heading, style = MaterialTheme.typography.titleSmall)
            }
        }

        // --- up next overlay ---
        AnimatedVisibility(
            visible = showUpNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Raised.copy(alpha = 0.97f))
                    .padding(14.dp),
            ) {
                Column {
                    Text("Up next", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Text(s?.next?.label ?: "", color = Palette.Heading, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.width(14.dp))
                IconButton(onClick = { playNext() }) {
                    Icon(Icons.Filled.SkipNext, "Play next", tint = Palette.Accent)
                }
                Text(
                    "✕",
                    color = Palette.Muted,
                    modifier = Modifier
                        .clickable { upNextDismissed = true }
                        .padding(8.dp),
                )
            }
        }

        // --- load error ---
        loadError?.let {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = Palette.Danger)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Close",
                    color = Palette.Accent,
                    modifier = Modifier.clickable(onClick = onExit).padding(8.dp),
                )
            }
        }
        if (source == null && loadError == null) {
            CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.align(Alignment.Center))
        }

        // --- controls overlay (hidden in PiP: the window is too small for it) ---
        AnimatedVisibility(visible = controlsVisible && !inPip, enter = fadeIn(), exit = fadeOut()) {
            ControlsOverlay(
                title = s?.title ?: "",
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                hasNext = s?.next != null,
                subs = s?.subs ?: emptyList(),
                activeSubId = activeSubId,
                onSelectSub = { id ->
                    activeSubId = id
                    rememberSubLang(s?.itemId, id?.let { sid -> s?.subs?.firstOrNull { it.id == sid }?.lang })
                },
                onAudioLang = { lang -> rememberAudioLang(s?.itemId, lang) },
                subOffsetMs = subOffsetMs,
                onNudgeSub = { subOffsetMs += it },
                subPos = subPos,
                onSubPos = { p ->
                    subPos = p
                    prefs.edit().putString("subPos", p.name).apply()
                },
                speed = speed,
                onSpeed = { sp ->
                    speed = sp
                    activePlayer()?.setPlaybackSpeed(sp)
                    prefs.edit().putFloat("speed", sp).apply()
                },
                audioTracks = audioTracks,
                player = player,
                showCast = castContext != null,
                onBack = onExit,
                onPlayPause = {
                    activePlayer()?.let { if (it.isPlaying) it.pause() else it.play() }
                    publishParty()
                },
                onSeekTo = {
                    activePlayer()?.seekTo(it)
                    publishParty()
                },
                onNext = { playNext() },
                partyLabel = if (partyActive && activeParty != null) {
                    val joined = activeParty.members.count { m -> m.status == "joined" }
                    "Party · $joined watching" + if (activeParty.mode == PartyMode.Stream) " · streaming" else ""
                } else null,
                canHostParty = partySnap.signedIn && !partyActive && s != null && s.fingerprint.isNotEmpty(),
                onHostParty = { mode ->
                    val src = s ?: return@ControlsOverlay
                    scope.launch {
                        val r = app.container.party.host(src.fileId, src.fingerprint, src.title, mode)
                        partyError = r.exceptionOrNull()?.message
                    }
                },
                onLeaveParty = { scope.launch { app.container.party.leave() } },
            )
        }

        // Party errors (no username yet, remote access off, …) need to be visible
        // here: the Party tab isn't on screen while the video is.
        partyError?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Raised.copy(alpha = 0.97f))
                    .clickable { partyError = null }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(msg, color = Palette.Warn, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LevelIndicator(icon: @Composable () -> Unit, level: Float, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        LinearProgressIndicator(
            progress = { level },
            color = Palette.Accent,
            trackColor = Palette.Track,
            modifier = Modifier.width(120.dp).height(4.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ControlsOverlay(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasNext: Boolean,
    subs: List<SubRef>,
    activeSubId: Long?,
    onSelectSub: (Long?) -> Unit,
    onAudioLang: (String?) -> Unit,
    subOffsetMs: Long,
    onNudgeSub: (Long) -> Unit,
    subPos: SubPosition,
    onSubPos: (SubPosition) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    audioTracks: List<Tracks.Group>,
    player: Player?,
    showCast: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    partyLabel: String? = null,
    canHostParty: Boolean = false,
    onHostParty: (PartyMode) -> Unit = {},
    onLeaveParty: () -> Unit = {},
) {
    var subsMenuOpen by remember { mutableStateOf(false) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var partyMenuOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // top bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Heading) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Heading, maxLines = 1)
                partyLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Accent, maxLines = 1)
                }
            }
            if (partyLabel != null || canHostParty) {
                Box {
                    IconButton(onClick = { partyMenuOpen = true }) {
                        Icon(
                            Icons.Filled.Groups,
                            "Watch together",
                            tint = if (partyLabel != null) Palette.Accent else Palette.Heading,
                        )
                    }
                    DropdownMenu(expanded = partyMenuOpen, onDismissRequest = { partyMenuOpen = false }) {
                        if (partyLabel != null) {
                            DropdownMenuItem(
                                text = { Text("Leave party", color = Palette.Danger) },
                                onClick = {
                                    partyMenuOpen = false
                                    onLeaveParty()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Watch together — everyone plays their own copy") },
                                onClick = {
                                    partyMenuOpen = false
                                    onHostParty(PartyMode.Local)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Watch together — stream mine to them") },
                                onClick = {
                                    partyMenuOpen = false
                                    onHostParty(PartyMode.Stream)
                                },
                            )
                        }
                    }
                }
            }
            if (showCast) {
                // MediaRouteButton hides itself automatically when no Cast device is
                // nearby. Its inflation can fail on non-AppCompat themes, so degrade
                // to nothing rather than crashing the player.
                AndroidView(
                    factory = { ctx ->
                        runCatching {
                            MediaRouteButton(ctx).apply {
                                CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
                            }
                        }.getOrElse { android.view.View(ctx) }
                    },
                    modifier = Modifier.padding(horizontal = 4.dp).size(40.dp),
                )
            }
            if (audioTracks.size > 1) {
                Box {
                    IconButton(onClick = { audioMenuOpen = true }) {
                        Icon(Icons.Filled.Audiotrack, "Audio track", tint = Palette.Heading)
                    }
                    DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                        audioTracks.forEachIndexed { i, group ->
                            val fmt = group.getTrackFormat(0)
                            val selected = group.isTrackSelected(0)
                            val label = fmt.label ?: fmt.language ?: "Track ${i + 1}"
                            DropdownMenuItem(
                                text = { Text(label, color = if (selected) Palette.Accent else Palette.Body) },
                                onClick = {
                                    player?.let { p ->
                                        p.trackSelectionParameters = p.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                            .build()
                                    }
                                    onAudioLang(fmt.language)
                                    audioMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { speedMenuOpen = true }) {
                    Icon(Icons.Filled.Speed, "Playback speed", tint = if (speed != 1f) Palette.Accent else Palette.Heading)
                }
                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { sp ->
                        DropdownMenuItem(
                            text = { Text("${sp}×", color = if (speed == sp) Palette.Accent else Palette.Body) },
                            onClick = {
                                onSpeed(sp)
                                speedMenuOpen = false
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { subsMenuOpen = true }) {
                    Icon(Icons.Filled.Subtitles, "Subtitles", tint = if (activeSubId != null) Palette.Accent else if (subs.isNotEmpty()) Palette.Heading else Palette.Muted)
                }
                DropdownMenu(expanded = subsMenuOpen, onDismissRequest = { subsMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Off", color = if (activeSubId == null) Palette.Accent else Palette.Body) },
                        onClick = {
                            onSelectSub(null)
                            subsMenuOpen = false
                        },
                    )
                    subs.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, color = if (s.id == activeSubId) Palette.Accent else Palette.Body) },
                            onClick = {
                                onSelectSub(s.id)
                                subsMenuOpen = false
                            },
                        )
                    }
                    if (activeSubId != null) {
                        HorizontalDivider(color = Palette.Divider)
                        // Timing: + shows subs later (badly-early subs), − earlier.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Timing", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "−0.5s",
                                color = Palette.Accent,
                                modifier = Modifier.clickable { onNudgeSub(-500L) }.padding(6.dp),
                            )
                            Text(
                                "%+.1fs".format(subOffsetMs / 1000.0),
                                color = Palette.Heading,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Text(
                                "+0.5s",
                                color = Palette.Accent,
                                modifier = Modifier.clickable { onNudgeSub(+500L) }.padding(6.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            SubPosition.entries.forEach { p ->
                                Text(
                                    p.label,
                                    color = if (p == subPos) Palette.Accent else Palette.Muted,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable { onSubPos(p) }.padding(6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // center transport
        Row(
            horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = { onSeekTo((positionMs - 10_000).coerceAtLeast(0)) }) {
                Icon(Icons.Filled.Replay10, "Back 10s", tint = Palette.Heading, modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause",
                    tint = Palette.Heading,
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(onClick = { onSeekTo((positionMs + 10_000).coerceAtMost(durationMs.coerceAtLeast(0))) }) {
                Icon(Icons.Filled.Forward10, "Forward 10s", tint = Palette.Heading, modifier = Modifier.size(40.dp))
            }
        }

        // bottom bar
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            val dur = durationMs.coerceAtLeast(1)
            Slider(
                value = scrubbing ?: (positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { onSeekTo((it * dur).toLong()) }
                    scrubbing = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Accent,
                    activeTrackColor = Palette.Accent,
                    inactiveTrackColor = Palette.Track,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration((positionMs / 1000).toInt()), color = Palette.Body, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (hasNext) {
                    IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next episode", tint = Palette.Heading) }
                }
                Text(formatDuration((durationMs / 1000).toInt().coerceAtLeast(0)), color = Palette.Body, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
