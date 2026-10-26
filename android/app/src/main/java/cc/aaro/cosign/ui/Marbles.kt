package cc.aaro.cosign.ui

import cc.aaro.cosign.R

// Written by android/tools/marbles.py from the collection; run it again to add a marble.

/**
 * One of the daily marbles: its name, its picture (light and dark, shadow included), and its radius in the render,
 * in pixels (how big it can be shown). [file]: the night's own marble instead (a transparent, centred PNG; no shadow).
 */
data class Marble(val name: String, val res: Int, val detail: Int, val file: java.io.File? = null)

/** The collection; the day's is picked by date ([Marbles.today]). */
val MARBLES = listOf(
    Marble("Amber Swirl", R.drawable.marble_amber_swirl, 267),
    Marble("Cobalt Swirl", R.drawable.marble_cobalt_swirl, 262),
    Marble("Rainbow Ribbon", R.drawable.marble_rainbow_ribbon, 266),
    Marble("Sea Glass", R.drawable.marble_sea_glass, 325),
)
