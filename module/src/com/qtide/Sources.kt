package com.qtide

import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One catalogue, with its own sign-in and its own way of handing a song over.
 *
 * The three zips share this file: each module only makes the source it is about, so the same Tidal
 * code is one module, the Qobuz code another and the Deezer code a third, built from this project.
 */
internal sealed interface Source {
    val label: String

    /**
     * The last call's timings, for a failure that is really "that took too long": mochy gives a
     * module 12 s per song before it moves on, and a Deezer FLAC is downloaded and unwound here
     * before anything is handed over, so a slow line must read as a slow line and not as a refusal.
     */
    val timings: String get() = ""

    /** why the last [stream] came back empty, in the service's own words; blank when it just failed */
    val reason: String get() = ""

    /** true when [reason] is about having no usable account, so the app is told "signed out" */
    fun signedOut(): Boolean = false

    suspend fun search(host: ModuleHost, track: TrackQuery): TrackMatcher.Scored?
    suspend fun stream(host: ModuleHost, id: String, quality: String): Handoff?

    /**
     * [refresh] may be blank: a module signed out that way is still signed in by a pending device
     * login, whose approval the user has just finished at link.tidal.com.
     */
    class Tidal(private val refresh: String, private val signedInByDevice: (String) -> Unit = {}) : Source {
        override val label = "Tidal"
        private var signedIn: com.qtide.Tidal.Session? = null
        private var problem = ""

        override val reason: String get() = problem

        /** true when [reason] is about having no account, not about the catalogue */
        override fun signedOut(): Boolean = refresh.isBlank() && signedIn == null

        private fun open(host: ModuleHost): com.qtide.Tidal.Session? {
            signedIn?.let { return it }
            val token = refresh.ifBlank {
                com.qtide.Tidal.pollDevice(host)?.also(signedInByDevice).orEmpty()
            }
            if (token.isBlank()) {
                val code = com.qtide.Tidal.beginDevice(host)
                problem = if (code != null) {
                    "Not signed in — open link.tidal.com and approve code $code, then play again"
                } else {
                    "Not signed in, and Tidal wouldn't hand out a sign-in code"
                }
                return null
            }
            return com.qtide.Tidal.login(host, token)?.also { signedIn = it }
        }

        override suspend fun search(host: ModuleHost, track: TrackQuery): TrackMatcher.Scored? {
            val session = open(host) ?: return null
            val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            return TrackMatcher.ranked(track, com.qtide.Tidal.search(host, session, query, 8)).firstOrNull()
        }

        override suspend fun stream(host: ModuleHost, id: String, quality: String): Handoff? {
            val found = com.qtide.Tidal.stream(host, open(host) ?: return null, id, quality) ?: return null
            return Handoff(found.url, found.audio, found.format, found.label, found.codec, found.sampleRate)
        }
    }

    class Qobuz(private val userToken: String, private val appPair: String) : Source {
        override val label = "Qobuz"
        private var signedIn: com.qtide.Qobuz.Session? = null

        override val reason: String get() = com.qtide.Qobuz.lastRefusal

        private fun open(host: ModuleHost): com.qtide.Qobuz.Session? {
            signedIn?.let { return it }
            val pair = appPair.split(":", limit = 2)
            return com.qtide.Qobuz.login(host, userToken, pair.getOrNull(0).orEmpty(), pair.getOrNull(1).orEmpty())
                ?.also { signedIn = it }
        }

        override suspend fun search(host: ModuleHost, track: TrackQuery): TrackMatcher.Scored? {
            val session = open(host) ?: return null
            val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            return TrackMatcher.ranked(track, com.qtide.Qobuz.search(host, session, query, 8)).firstOrNull()
        }

        override suspend fun stream(host: ModuleHost, id: String, quality: String): Handoff? {
            val found = com.qtide.Qobuz.stream(host, open(host) ?: return null, id, quality) ?: return null
            return Handoff(found.url, null, StreamFormat.Progressive, found.label, found.codec, found.sampleRate)
        }
    }

    class Deezer(private val arl: String) : Source {
        override val label = "Deezer"

        override val reason: String get() = com.qtide.Deezer.lastReason

        override val timings: String get() = com.qtide.Deezer.lastTiming

        /** true when the ARL itself was turned down, rather than the track */
        override fun signedOut(): Boolean =
            com.qtide.Deezer.lastReason.contains("ARL") || com.qtide.Deezer.lastReason.contains("licence token")

        override suspend fun search(host: ModuleHost, track: TrackQuery): TrackMatcher.Scored? {
            val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            return TrackMatcher.ranked(track, com.qtide.Deezer.search(host, arl, query, 8)).firstOrNull()
        }

        override suspend fun stream(host: ModuleHost, id: String, quality: String): Handoff? {
            val found = com.qtide.Deezer.stream(host, arl, id, quality) ?: return null
            return Handoff(null, found.bytes, StreamFormat.Progressive, found.label, found.codec, found.sampleRate)
        }
    }
}

/** what a service gives back: a link, or bytes the module has to save first */
internal data class Handoff(
    val url: String?,
    val audio: ByteArray?,
    val format: StreamFormat,
    val label: String,
    val codec: String,
    val sampleRate: Int,
)

/**
 * The song flow every one of the three modules runs: search the catalogue it is about, match the
 * recording, open the stream, and hand Mochy something its player can open.
 *
 * A service that answers with bytes rather than a link (Deezer's striped file) has them saved in the
 * module's own folder as one plain file.
 *
 * Nothing here throws: every failure comes back as a [Step] with ok = false and a sentence for the
 * module's page, so a network problem reads as one line instead of a red "didn't start".
 */
internal class SourceRunner(
    private val host: ModuleHost,
    private val source: Source,
    private val folder: () -> java.io.File,
) {

    class Step(val name: String, val ok: Boolean?, val detail: String)

    class Outcome(val stream: Handoff, val matched: TrackMatcher.Scored)

    private var lastProblem = "Nothing came back"

    fun problem() = lastProblem

    /**
     * Mochy gives a module 12 s per song. A whole-file service stops here a little before that, so
     * the page gets a sentence instead of the app giving up with nothing to show.
     */
    private val budgetMs = 10_500L

    suspend fun find(track: TrackQuery, quality: String, report: (Step) -> Unit): Outcome? {
        val beganAt = System.currentTimeMillis()
        report(Step("Find the song", null, "Searching ${source.label}…"))
        val best = try {
            source.search(host, track)
        } catch (e: Exception) {
            lastProblem = "${source.label}: ${e.message ?: e.javaClass.simpleName}"
            report(Step("Find the song", false, lastProblem))
            return null
        }
        if (best == null) {
            // a service that couldn't even open a session says so, instead of reading like a miss
            lastProblem = source.reason.ifBlank { "No matching recording of “${track.title}” on ${source.label}" }
            report(Step("Find the song", false, lastProblem))
            return null
        }
        report(Step("Find the song", true, "${best.track.title} · ${best.track.artist}"))

        report(Step("Stream", null, "Opening the stream…"))
        // never report from another thread: the bridge belongs to the coroutine that started the call
        val startedAt = System.currentTimeMillis()
        val left = (beganAt + budgetMs - startedAt).coerceAtLeast(1L)
        val stream = try {
            withTimeoutOrNull(left) { source.stream(host, best.track.id, quality) }
        } catch (e: Exception) {
            lastProblem = "${source.label} gave no stream: ${e.message ?: e.javaClass.simpleName}"
            report(Step("Stream", false, lastProblem))
            return null
        }
        if (stream == null && System.currentTimeMillis() - beganAt >= budgetMs) {
            // stopped here rather than by mochy, so the sentence survives to the page
            lastProblem = "${source.label} was still working after ${budgetMs / 1000} s" +
                (source.timings.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                (source.reason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            report(Step("Stream", false, lastProblem))
            return null
        }
        val took = (System.currentTimeMillis() - startedAt) / 1000
        if (stream == null) {
            lastProblem = (source.reason.ifBlank { "${source.label} gave no stream" }) +
                if (took >= 5) " (${took} s)" else ""
            report(Step("Stream", false, lastProblem))
            return null
        }

        val url = stream.url ?: stream.audio?.let { save(it, stream) }?.takeIf { it.isNotBlank() }
        if (url == null) {
            lastProblem = "${source.label} gave no audio"
            report(Step("Stream", false, lastProblem))
            return null
        }
        // how the file came, when it was slow enough that the user felt it
        val detail = stream.label + if (took >= 4) ", in ${took} s" else ""
        report(Step("Stream", true, detail))
        return Outcome(stream.copy(url = url), best)
    }

    /** bytes go in the module's own folder as one plain file; the folder is kept to the newest few */
    private fun save(bytes: ByteArray, stream: Handoff): String {
        if (bytes.isEmpty()) return ""
        val dir = folder().apply { mkdirs() }
        val extension = if (stream.codec.equals("FLAC", ignoreCase = true)) ".flac" else ".mp3"
        val file = java.io.File(dir, "qtide-" + bytes.size + "-" + Integer.toHexString(bytes.contentHashCode()) + extension)
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.writeBytes(bytes)
            prune(dir, keep = file)
        }
        // three slashes: the uri form every android player reads as a plain path
        return "file://" + file.absolutePath
    }

    private fun prune(dir: java.io.File, keep: java.io.File) {
        val files = dir.listFiles()?.filter { it.isFile && it != keep && it.name.startsWith("qtide-") }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() } + keep.length()
        while (files.isNotEmpty() && (files.size >= MAX_FILES || total > MAX_BYTES)) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private companion object {
        const val MAX_FILES = 10
        const val MAX_BYTES = 500L * 1024 * 1024
    }
}
