package com.qtide

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import java.io.File

/**
 * Qtide: Qobuz, Tidal and Deezer in Mochy, each with the user's own account.
 *
 * One module, three catalogues. A token pasted on the module's page is enough to sign in; the
 * sources are asked in the order Tidal, Qobuz, Deezer, and the first one that has the song wins.
 *
 * A stream is handed over as a link when the service gives one (Tidal CD, Qobuz FLAC). Tidal's
 * hi-res only comes as a DASH manifest, which goes over as a `data:` uri so the player fetches its
 * segments itself; Deezer's file is downloaded and decrypted here, then saved and played as one file.
 */
class QtideModule : SourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = "qtide",
        name = "Qtide",
        version = "1.0",
        description = "Qobuz, Tidal and Deezer in Mochy: your own accounts, lossless and hi-res where they have it",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost
    private var tidal: Source.Tidal? = null
    private var qobuz: Source.Qobuz? = null
    private var deezer: Source.Deezer? = null

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    // ---- what people fill in --------------------------------------------------------------

    override val fields = listOf(
        ModuleField(
            id = "tidalRefresh",
            label = "Tidal refresh token",
            kind = FieldKind.Text,
            hint = "The refresh token of a Tidal session",
            description = "Kept on this phone only. Leave empty to skip Tidal.",
        ),
        ModuleField(
            id = "qobuzToken",
            label = "Qobuz user token",
            kind = FieldKind.Text,
            hint = "The X-User-Auth-Token header of the Qobuz web player",
            description = "Leave empty to skip Qobuz.",
        ),
        ModuleField(
            id = "qobuzApp",
            label = "Qobuz app id and secret (optional)",
            kind = FieldKind.Text,
            hint = "appId:secret",
            description = "Left empty, they are read from Qobuz's own web player.",
        ),
        ModuleField(
            id = "deezerArl",
            label = "Deezer ARL",
            kind = FieldKind.Text,
            hint = "The arl cookie of a signed-in deezer.com",
            description = "Leave empty to skip Deezer.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        values.forEach { (key, value) ->
            host.storage.set(key, value.trim().ifEmpty { null })
        }
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("hires", "Hi-Res", "Up to 24-bit, where the service has a hi-res master"),
                StreamOption("lossless", "Lossless", "CD quality, 16-bit 44.1 kHz"),
                StreamOption("high", "High", "320 kbps, for a slow connection"),
            ),
        ),
    )

    // ---- account --------------------------------------------------------------------------

    private fun tidalToken() = host.storage.get("tidalRefresh")
    private fun qobuzToken() = host.storage.get("qobuzToken")
    private fun qobuzApp() = host.storage.get("qobuzApp").orEmpty()
    private fun deezerArl() = host.storage.get("deezerArl")

    override fun accountState(): AccountState {
        val signedIn = listOfNotNull(
            tidalToken()?.takeIf { it.isNotBlank() }?.let { "Tidal" },
            qobuzToken()?.takeIf { it.isNotBlank() }?.let { "Qobuz" },
            deezerArl()?.takeIf { it.isNotBlank() }?.let { "Deezer" },
        )
        return if (signedIn.isEmpty()) AccountState.SignedOut else AccountState.SignedIn(signedIn.joinToString(" · "))
    }

    // ---- the song -------------------------------------------------------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val quality = picks["quality"] ?: "lossless"

        // the same source objects every call, so a session signed in once is kept
        val sources = buildList {
            tidalToken()?.let { add(tidal ?: Source.Tidal(it).also { tidal = it }) }
            qobuzToken()?.let { add(qobuz ?: Source.Qobuz(it).also { qobuz = it }) }
            deezerArl()?.let { add(deezer ?: Source.Deezer(it).also { deezer = it }) }
        }
        if (sources.isEmpty()) {
            report(DiagnosticStep("Account", false, "No token on the module yet"))
            return ResolveResult.Unavailable("Not signed in")
        }
        report(DiagnosticStep("Account", true, sources.joinToString(" · ") { it.label }))

        report(DiagnosticStep("Find the song", null, "Searching ${sources.joinToString(", ") { it.label }}…"))
        var lastReason = "Not on any of the services"
        for (source in sources) {
            val found = try {
                source.search(host, track)
            } catch (e: Exception) {
                report(DiagnosticStep("Find the song", false, "${source.label}: ${e.message}"))
                continue
            }
            val best = found ?: continue
            report(DiagnosticStep("Find the song", true, "${best.track.title} · ${best.track.artist} (${source.label})"))

            report(DiagnosticStep("Stream", null, "Opening the stream…"))
            val stream = try {
                source.stream(host, best.track.id, quality)
            } catch (e: Exception) {
                report(DiagnosticStep("Stream", false, "${source.label}: ${e.message}"))
                null
            }
            if (stream == null) {
                lastReason = "${source.label} gave no stream"
                report(DiagnosticStep("Stream", false, lastReason))
                continue
            }
            report(DiagnosticStep("Stream", true, "${stream.label} · ${source.label}"))

            val url = stream.url ?: stream.audio?.let { save(it, stream) }
                ?: return ResolveResult.Unavailable("$lastReason: no audio")

            return ResolveResult.Found(
                SourceStream(
                    url = url,
                    format = stream.format,
                    sourceLabel = source.label,
                    qualityLabel = stream.label,
                    codec = stream.codec,
                    sampleRateHz = stream.sampleRate,
                    matched = MatchedTrack(
                        id = best.track.id,
                        title = best.track.title,
                        artist = best.track.artist,
                        album = best.track.album,
                        durationMs = best.track.durationMs,
                        confidence = best.score,
                    ),
                ),
            )
        }
        return ResolveResult.NotFound(lastReason)
    }

    // ---- the services ---------------------------------------------------------------------

    /** one catalogue, with its own sign-in and its own way of handing over a stream */
    private sealed interface Source {
        val label: String

        suspend fun search(host: ModuleHost, track: TrackQuery): TrackMatcher.Scored?
        suspend fun stream(host: ModuleHost, id: String, quality: String): Handoff?

        class Tidal(private val refresh: String) : Source {
            override val label = "Tidal"
            private var signedIn: com.qtide.Tidal.Session? = null

            private suspend fun open(host: ModuleHost): com.qtide.Tidal.Session? =
                signedIn ?: com.qtide.Tidal.login(host, refresh)?.also { signedIn = it }

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

        class Qobuz(private val userToken: String) : Source {
            override val label = "Qobuz"
            private var signedIn: com.qtide.Qobuz.Session? = null

            private fun open(host: ModuleHost): com.qtide.Qobuz.Session? {
                signedIn?.let { return it }
                val pair = host.storage.get("qobuzApp").orEmpty().split(":", limit = 2)
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
    private class Handoff(
        val url: String?,
        val audio: ByteArray?,
        val format: StreamFormat,
        val label: String,
        val codec: String,
        val sampleRate: Int,
    )

    /**
     * Bytes (a decrypted Deezer file) go in the module's own folder as one plain file, so the player
     * opens it like any other and a repeat play costs nothing. The folder is kept small: a hi-res or
     * FLAC track is tens of megabytes, so only the newest few stay.
     */
    private fun save(bytes: ByteArray, stream: Handoff): String {
        if (bytes.isEmpty()) return ""
        val folder = host.folder
        folder.mkdirs()
        val extension = if (stream.codec.equals("FLAC", ignoreCase = true)) ".flac" else ".mp3"
        val file = File(folder, "qtide-" + bytes.size + "-" + Integer.toHexString(bytes.contentHashCode()) + extension)
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            file.writeBytes(bytes)
            prune(folder, keep = file)
        }
        // three slashes: the uri form every android player reads as a plain path
        return "file://" + file.absolutePath
    }

    /** keeps the folder at [MAX_FILES] files and [MAX_BYTES] in all, dropping the oldest first */
    private fun prune(folder: File, keep: File) {
        val files = folder.listFiles()?.filter { it.isFile && it != keep && it.name.startsWith("qtide-") }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() } + keep.length()
        while (files.isNotEmpty() && (files.size >= MAX_FILES || total > MAX_BYTES)) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private companion object {
        const val TOKEN_KEY = "tidalRefresh"
        // what the module's folder may hold before the oldest songs are dropped: about ten flac tracks
        const val MAX_FILES = 10
        const val MAX_BYTES = 500L * 1024 * 1024
    }

    private fun Handoff.mimeTypeIsFlac() = codec.equals("flac", ignoreCase = true)
}
