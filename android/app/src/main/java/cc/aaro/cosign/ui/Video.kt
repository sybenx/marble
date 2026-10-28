package cc.aaro.cosign.ui

import android.app.Dialog
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import cc.aaro.cosign.net.Fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Videos as Wisp plays them (github.com/barrydeen/wisp, MIT: RichContent's InlineVideoPlayerWithFullscreen,
// FullScreenVideoPlayer, MediaDownloader): each starts muted and on its own once half of it is on screen, loops, and
// one plays at a time; tap it for the player's own controls, with sound, save and full screen at the top.

/** A video in a note: its address, a picture to show before it plays (if the note gave one), its shape and length. */
data class VideoRef(val url: String, val poster: String?, val ratio: Float, val seconds: Int?)

object Videos {
    /** Sound, for every video at once: off until you turn it on. */
    val muted = mutableStateOf(true)
    /** The one that's playing (its address). */
    val active = mutableStateOf<String?>(null)
    /** Full screen: which, and from where. */
    val full = mutableStateOf<Pair<String, Long>?>(null)

    @androidx.annotation.OptIn(UnstableApi::class)
    fun player(context: android.content.Context): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Fetch.http))).build()

    /** Into Downloads, as Wisp saves it. */
    suspend fun save(context: android.content.Context, url: String) {
        val types = mapOf("mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "m4v" to "video/mp4")
        try {
            withContext(Dispatchers.IO) {
                Fetch.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("it answered ${r.code}")
                    val raw = url.substringAfterLast('/').substringBefore('?').ifEmpty { "video" }
                    val ext = raw.substringAfterLast('.', "").lowercase()
                    val type = types[ext] ?: r.header("Content-Type")?.substringBefore(';')?.trim() ?: "video/mp4"
                    val name = if (ext.isEmpty() || ext == raw) "$raw.${types.entries.firstOrNull { it.value == type }?.key ?: "mp4"}" else raw
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, type)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS); put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("no room")
                    context.contentResolver.openOutputStream(uri)?.use { out -> r.body!!.byteStream().use { it.copyTo(out) } } ?: error("can't write")
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            }
            Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't save it: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * A video in a card. Its player exists only while it's on screen (a note can hold many, and each player holds a
 * decoder): half on screen, it plays (muted, unless you turned sound on); partly off, it pauses; all the way off, the
 * player goes and its place is kept. Paused by you, it stays paused until it's scrolled away.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoCard(v: VideoRef, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val url = v.url
    var ratio by remember(url) { mutableFloatStateOf(v.ratio) }
    val muted by Videos.muted
    var showControls by remember { mutableStateOf(false) }
    var userPaused by remember(url) { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var wanted by remember(url) { mutableStateOf(false) }
    var playOnCreate by remember(url) { mutableStateOf(false) }
    var resumeAt by remember(url) { mutableLongStateOf(0L) }
    var exo by remember(url) { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(muted) { exo?.volume = if (muted) 0f else 1f; onDispose {} }
    // The app in the background: pause.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { exo?.pause() }

    DisposableEffect(url, wanted) {
        if (!wanted) return@DisposableEffect onDispose {}
        val player = Videos.player(context).apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url))); prepare()
            volume = if (Videos.muted.value) 0f else 1f
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
        }
        if (resumeAt > 0) player.seekTo(resumeAt)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(s: VideoSize) { if (s.width > 0 && s.height > 0) ratio = s.width * s.pixelWidthHeightRatio / s.height }
            override fun onIsPlayingChanged(playing: Boolean) { if (!playing && player.playbackState == Player.STATE_READY) userPaused = true }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        }
        player.addListener(listener)
        if (playOnCreate) { playOnCreate = false; Videos.active.value = url; player.play() }
        exo = player
        onDispose {
            player.removeListener(listener)
            if (Videos.active.value == url) Videos.active.value = null
            resumeAt = player.currentPosition
            buffering = false
            exo = null
            player.release()
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(max = 500.dp).aspectRatio(ratio.coerceIn(0.3f, 4f)).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .onGloballyPositioned { c ->
                val b = c.boundsInWindow()
                val seen = ((b.bottom.coerceAtMost(view.height.toFloat()) - b.top.coerceAtLeast(0f)).coerceAtLeast(0f)) / b.height.coerceAtLeast(1f)
                val p = exo
                when {
                    seen > 0.5f -> if (!userPaused && (Videos.active.value == null || Videos.active.value == url)) {
                        if (p == null) wanted = true else if (!p.isPlaying) { Videos.active.value = url; p.play() }
                    }
                    seen <= 0f -> { if (wanted) wanted = false; userPaused = false }
                    else -> { if (p?.isPlaying == true) p.pause(); if (Videos.active.value == url) Videos.active.value = null; userPaused = false }
                }
            }) {
            val p = exo
            if (p == null) {
                // Until the player's needed: its picture and ▶; a tap plays at once.
                Box(Modifier.fillMaxSize().clickable { userPaused = false; playOnCreate = true; wanted = true }, contentAlignment = Alignment.Center) {
                    v.poster?.let { NetImage(it, Modifier.fillMaxSize(), 1080, ground = Color.Black) }
                    Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White,
                        modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp))
                }
            } else {
                AndroidView({ ctx ->
                    PlayerView(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        useController = true
                        controllerAutoShow = false
                        controllerShowTimeoutMs = 2000
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { showControls = it == android.view.View.VISIBLE })
                        hideController()
                    }
                }, Modifier.fillMaxSize(), update = { it.player = p })
                if (buffering) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
                }
                AnimatedVisibility(showControls, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.padding(8.dp)) {
                        TopButton(if (muted) Glyphs.soundOff else Glyphs.soundOn, if (muted) "Sound on" else "Sound off") { Videos.muted.value = !muted }
                        Spacer(Modifier.width(4.dp))
                        TopButton(Glyphs.download, "Save to the phone") { scope.launch { Videos.save(context, url) } }
                        Spacer(Modifier.width(4.dp))
                        TopButton(Glyphs.fullscreen, "Full screen") { val at = p.currentPosition; p.pause(); Videos.full.value = url to at }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopButton(icon: ImageVector, label: String, onClick: () -> Unit) =
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
    }

/** Full screen, as Wisp's: its own player (with sound, from where it was), the player's controls; save, copy, close. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoFullScreen() {
    val (url, from) = Videos.full.value ?: return
    val context = LocalContext.current
    DisposableEffect(url) {
        val activity = context as? android.app.Activity ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
            ?: return@DisposableEffect onDispose { Videos.full.value = null }
        val exo = Videos.player(activity).apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url))); prepare(); seekTo(from)
            playWhenReady = true; repeatMode = Player.REPEAT_MODE_ONE
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(true)
        dialog.setOnDismissListener { Videos.full.value = null }
        val root = FrameLayout(activity)
        root.addView(PlayerView(activity).apply { player = exo; useController = true; layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) })
        val d = activity.resources.displayMetrics.density
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { val m = (16 * d).toInt(); setMargins(m, m, m, m) }
        }
        fun button(icon: Int, label: String, onClick: () -> Unit) = ImageButton(activity).apply {
            setImageResource(icon); contentDescription = label; setBackgroundColor(0x80000000.toInt()); setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener { onClick() }
        }
        bar.addView(button(android.R.drawable.stat_sys_download_done, "Save to the phone") {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch { Videos.save(activity, url) }
        })
        bar.addView(button(android.R.drawable.ic_menu_share, "Copy its address") {
            (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Video", url))
            Toast.makeText(activity, "Address copied", Toast.LENGTH_SHORT).show()
        })
        bar.addView(button(android.R.drawable.ic_menu_close_clear_cancel, "Close") { dialog.dismiss() })
        root.addView(bar)
        dialog.setContentView(root)
        dialog.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        dialog.show()
        onDispose { exo.release(); if (dialog.isShowing) dialog.dismiss() }
    }
}
