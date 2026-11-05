package cc.aaro.cosign.reader

import cc.aaro.cosign.Later
import cc.aaro.cosign.LaterItem

/**
 * One item's Star and Keep in an Undo window (one pending action per item): the note as it was before the first change
 * ([before], none if there wasn't one) and this phone's last write ([after], none if it took the note away).
 */
data class NoteChange(val url: String, val before: LaterItem?, val after: LaterItem?)

object NoteUndo {
    /**
     * Back to [c]'s before. Left alone if the note changed since (a newer edit merged in, or someone else's note there
     * now). A note that was removed comes back under a fresh id (a removed id stays removed on every device).
     */
    fun undo(later: Later, c: NoteChange) {
        val cur = later.forUrl(c.url)
        if (cur != null && (c.after == null || cur.id != c.after.id || cur.updated > c.after.updated)) return
        when {
            c.before == null -> if (cur != null) later.remove(cur)
            cur != null && cur.id == c.before.id -> later.putBack(c.before)
            else -> { if (cur != null) later.remove(cur); later.bringBack(c.before) }
        }
    }
}
