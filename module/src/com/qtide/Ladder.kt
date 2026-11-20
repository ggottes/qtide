package com.qtide

/**
 * The steps one quality chip asks for, best first.
 *
 * A chip names a tier the account should get; when the service can't serve that tier for a track
 * (or for this plan) the next step down is tried instead, so a "Hi-Res" pick on a CD-only master
 * still plays in CD instead of failing. The last step of a chip is its floor: nothing asks for less
 * than the chip promised.
 */
internal object Ladder {

    /**
     * @param quality the chip the user picked (an id from the module's `choices`)
     * @param rungs chip id to the ordered steps it may take, best first
     * @return the steps to try; the last rung of the best matching chip when the pick isn't known
     */
    fun steps(quality: String, rungs: Map<String, List<String>>): List<String> =
        rungs[quality] ?: rungs["lossless"] ?: rungs.values.lastOrNull().orEmpty()

    /** the first step that answered, and the first that tried; null only when nothing is in the ladder */
    fun <T> over(steps: List<String>, ask: (String) -> T?): Pair<String, T>? =
        steps.firstNotNullOfOrNull { step -> ask(step)?.let { step to it } }
}
