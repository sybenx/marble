package cc.aaro.cosign.web

import cc.aaro.cosign.ui.Saving

/**
 * The picture or video under a long-press, asked of the page: WebView names a plain picture itself, but not a video
 * (or a picture inside a link, or one the page keeps from taking touches). [script] gives back its address.
 */
object MediaAt {
    /**
     * [x], [y]: the press in the WebView's own pixels. Found by where things sit, not by what takes the touch: Jumble
     * gives its pictures `pointer-events: none`, so the browser's own "what's under the finger" never names them.
     * The smallest picture or video around the point wins (a thumbnail over a backdrop).
     */
    fun script(x: Float, y: Float): String = """
        (function(x, y) {
          var r = window.devicePixelRatio || 1, px = x / r, py = y / r, best = null, area = Infinity;
          document.querySelectorAll('img, video').forEach(function(e) {
            var b = e.getBoundingClientRect();
            if (b.width < 24 || b.height < 24 || px < b.left || px > b.right || py < b.top || py > b.bottom) return;
            var s = getComputedStyle(e);
            if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return;
            var a = b.width * b.height;
            if (a < area) { area = a; best = e; }
          });
          if (!best) return null;
          if (best.tagName === 'VIDEO') {
            var u = best.currentSrc || best.src || '';
            if (!u) { var so = best.querySelector('source[src]'); if (so) u = so.src; }
            return { k: 'video', u: u };
          }
          return { k: 'picture', u: best.currentSrc || best.src || '' };
        })(${x}, ${y})
    """.trimIndent()

    /** What [script] gave back (as evaluateJavascript hands it over), or null when there was nothing. */
    fun parse(out: String?): Pair<String, Saving.Kind>? {
        if (out.isNullOrBlank() || out == "null") return null
        return try {
            val o = org.json.JSONObject(out)
            val kind = when (o.optString("k")) { "video" -> Saving.Kind.Video; "picture" -> Saving.Kind.Picture; else -> return null }
            o.optString("u") to kind
        } catch (_: Exception) { null }
    }
}
