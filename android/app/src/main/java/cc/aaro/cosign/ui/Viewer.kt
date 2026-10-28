package cc.aaro.cosign.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A note's pictures, full screen: swipe between them, pinch or double-tap to zoom, swipe down
 * (or back) to close. Each is loaded at full size only now; the feed's copy shows meanwhile.
 */
@Composable
fun ImageViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(start.coerceIn(0, urls.lastIndex)) { urls.size }
    var zoomed by remember { mutableStateOf(false) }
    // Swiping down closes; the picture follows your finger and the dark behind it fades.
    var drop by remember { mutableFloatStateOf(0f) }
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val px = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.toPx() * 2).toInt().coerceIn(1080, 2560) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(drop) / (closeAt * 3)).coerceIn(0.4f, 1f)))
            .draggable(rememberDraggableState { drop += it }, Orientation.Vertical, enabled = !zoomed,
                onDragStopped = { if (abs(drop) > closeAt) onClose() else drop = 0f }),
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = drop }, userScrollEnabled = !zoomed, key = { urls[it] + it }) { page ->
            Zoomable(urls[page], px, onZoom = { if (page == pager.currentPage) zoomed = it })
        }
        IconButton(onClose, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        if (urls.size > 1) Text("${pager.currentPage + 1} / ${urls.size}", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class) // transformable's canPan
@Composable
private fun Zoomable(url: String, px: Int, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Never panned past the picture's edges.
    fun set(s: Float, o: Offset) {
        scale = s
        val mx = box.width * (s - 1) / 2; val my = box.height * (s - 1) / 2
        offset = if (s <= 1f) Offset.Zero else Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        onZoom(s > 1.01f)
    }
    // The picture moves with your finger, exactly (the pan is already in screen pixels: scaling it by the zoom made a
    // double-tapped picture slide 2.5 times as far as the finger). A pinch keeps the middle of the picture where it was.
    val state = rememberTransformableState { zoom, pan, _ -> val s = (scale * zoom).coerceIn(1f, 5f); set(s, offset * (s / scale) + pan) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(url) { detectTapGestures(onDoubleTap = { at -> if (scale > 1f) set(1f, Offset.Zero) else set(2.5f, (Offset(size.width / 2f, size.height / 2f) - at) * 1.5f) }) }
            // Panning only once zoomed in, so an unzoomed swipe still goes to the next picture.
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        // A GIF or WebP moves here too (decoded whole; a still decode would keep only its first frame).
        val moving = androidx.compose.runtime.remember(url) { Regex("\\.(gif|webp)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url) }
        val anim by androidx.compose.runtime.produceState<android.graphics.drawable.Drawable?>(null, url) { if (moving) value = Images.animated(url) }
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        anim?.let { d ->
            androidx.compose.ui.viewinterop.AndroidView({ c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } }, layer,
                update = { it.setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start() })
        } ?: NetImage(url, layer, px, ContentScale.Fit, "Picture", Color.Transparent) {
            // The feed's copy, while the full one loads.
            NetImage(url, Modifier.fillMaxSize(), 1080, ContentScale.Fit, "Picture", Color.Transparent)
        }
    }
}
