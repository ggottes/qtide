package com.qtide

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleArt
import com.mochyy.mochymusic.module.ModuleFeature
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ModuleServer
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.ServerListModule
import com.mochyy.mochymusic.module.ServerProblem
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.module.TrackMatcher

/**
 * The pool: one Qtide server that holds each listener's own Tidal and Qobuz key and does the
 * signing, so the phone never sees a token.
 *
 * A listener adds the server's link on the module's page once, signs in on that server with their
 * own account, and from then on the module asks the server to search and to open a stream. The
 * server hands back an ordinary link, so this module is a thin client: no service keys here, no
 * refresh tokens on the device, nothing to leak if the phone is lost.
 *
 * The server itself is in `server/` in this repo (Next.js on Vercel), and its own pages are where
 * tokens are entered — a listener never types one into Mochy.
 */
class QtidePool : ServerListModule, SourceModule, ModuleArt {

    override val info = ModuleInfo(
        id = "qtide-pool",
        name = "Qtide — Pool",
        version = "1.0",
        description = "Your own Tidal and Qobuz through a Qtide server: sign in on the site, play here",
        sourceUrl = "https://github.com/ggottes/qtide",
        author = "geisselgottes",
    )

    override val adds: Set<ModuleFeature> get() = setOf(ModuleFeature.AudioSource)

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val icon: String? get() = Art.icon("pool")
    override val banner: String? get() = Art.BANNER

    // ---- servers ------------------------------------------------------------------------------

    private fun saved(): List<String> =
        host.storage.get("servers").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun save(list: List<String>) =
        host.storage.set("servers", list.distinct().joinToString("\n").ifBlank { null })

    override val servers: List<ModuleServer>
        get() = saved().map { ModuleServer(it, nameOf(it), builtIn = false) }

    /** the first server is the one asked; the rest are kept for when it's down */
    private fun primary(): String? = saved().firstOrNull()

    override suspend fun addServer(link: String): ServerProblem? {
        val base = normalise(link) ?: return ServerProblem.NotALink
        if (saved().any { it == base }) return ServerProblem.AlreadyAdded
        val answer = runCatching {
            host.http(HttpRequest("$base/api/health", headers = mapOf("accept" to "application/json")))
        }.getOrNull() ?: return ServerProblem.Unreachable
        if (!answer.ok) return ServerProblem.Unreachable
        // a Qtide server names itself in /api/health; anything else isn't one
        if (!answer.text.contains("\"qtide\"", ignoreCase = true)) return ServerProblem.NotAServer
        save(listOf(base) + saved())
        return null
    }

    override fun removeServer(url: String) {
        save(saved().filter { it != url })
    }

    override fun accountState(): AccountState {
        val base = primary() ?: return AccountState.SignedOut
        return AccountState.SignedIn(nameOf(base))
    }

    /** asks the server who the listener is; a server with no session says so, and that's the sign-in */
    override suspend fun verifyAccount(): AccountState {
        val base = primary() ?: return AccountState.SignedOut
        val answer = runCatching {
            host.http(HttpRequest("$base/api/me", headers = mapOf("accept" to "application/json")))
        }.getOrNull() ?: return AccountState.Expired("$base didn't answer")
        if (answer.code == 401) return AccountState.SignedOut
        if (!answer.ok) return AccountState.Expired("$base answered ${answer.code}")
        val plan = Json.text(Json.obj(answer.text), "plan")
        val who = Json.text(Json.obj(answer.text), "name")
        return AccountState.SignedIn(accountLabel(nameOf(base), who, plan))
    }

    override fun signOut() {
        val base = primary() ?: return
        // the server forgets the session it made for this device; nothing else is stored here
        runCatching {
            host.http(HttpRequest("$base/api/signout", method = "POST", headers = mapOf("content-type" to "application/json"), body = "{}".toByteArray()))
        }
    }

    // ---- the song flow ------------------------------------------------------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (com.mochyy.mochymusic.module.DiagnosticStep) -> Unit,
    ): ResolveResult {
        val base = primary() ?: run {
            report(DiagnosticStep("Server", false, "No Qtide server added yet"))
            return ResolveResult.Unavailable("Add your Qtide server link on the module's page")
        }
        report(DiagnosticStep("Server", true, nameOf(base)))

        report(DiagnosticStep("Find the song", null, "Asking ${nameOf(base)}…"))
        val found = search(base, track)
            ?: run {
                report(DiagnosticStep("Find the song", false, "Nothing on your account for that song"))
                return ResolveResult.Unavailable("Nothing on your account for that song")
            }
        report(DiagnosticStep("Find the song", true, "${found.track.title} · ${found.track.artist}"))

        report(DiagnosticStep("Stream", null, "Opening the stream…"))
        val stream = stream(base, found.track, picks["quality"] ?: "lossless")
            ?: run {
                report(DiagnosticStep("Stream", false, "The server gave no stream"))
                return ResolveResult.Unavailable("The server gave no stream")
            }
        report(DiagnosticStep("Stream", true, stream.qualityLabel))
        return ResolveResult.Found(stream)
    }

    /** the server does the matching; it answers the song it found and its own confidence */
    private suspend fun search(base: String, track: TrackQuery): Found? {
        val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
        val url = "$base/api/search?q=${enc(query)}" +
            (track.durationMs?.let { "&durationMs=$it" } ?: "") +
            (track.isrc?.let { "&isrc=${enc(it)}" } ?: "")
        val answer = runCatching {
            host.http(HttpRequest(url, headers = mapOf("accept" to "application/json")))
        }.getOrNull() ?: return null
        if (!answer.ok) return null
        val body = Json.obj(answer.text) ?: return null
        val song = Json.objOf(body["track"]) ?: return null
        val id = Json.text(song, "id") ?: return null
        val catalog = CatalogTrack(
            id = id,
            title = Json.text(song, "title").orEmpty(),
            artist = Json.text(song, "artist").orEmpty(),
            album = Json.text(song, "album"),
            durationMs = Json.long(song, "durationMs"),
            isrc = Json.text(song, "isrc"),
        )
        return Found(catalog, Json.text(song, "source").orEmpty())
    }

    private suspend fun stream(base: String, track: CatalogTrack, quality: String): SourceStream? {
        val why = when (quality) {
            "hires" -> "27,7,6"
            "low" -> "128"
            else -> "6"
        }
        val url = "$base/api/stream?source=${enc(trackIdSource(track))}&id=${enc(track.id)}&formats=$why"
        val answer = runCatching {
            host.http(HttpRequest(url, headers = mapOf("accept" to "application/json")))
        }.getOrNull() ?: return null
        if (!answer.ok) return null
        val body = Json.obj(answer.text) ?: return null
        val stream = Json.text(body, "url")?.takeIf { it.startsWith("http") } ?: return null
        return SourceStream(
            url = stream,
            format = StreamFormat.Progressive,
            sourceLabel = nameOf(base),
            qualityLabel = Json.text(body, "label").orEmpty().ifBlank { "FLAC" },
            codec = Json.text(body, "codec") ?: "FLAC",
            sampleRateHz = Json.long(body, "sampleRate")?.toInt(),
            matched = MatchedTrack(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                confidence = 1f,
            ),
        )
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private class Found(val track: CatalogTrack, val source: String)

    /** the module id the server's track came from, kept in the catalog id as "<source>:<id>" */
    private fun trackIdSource(track: CatalogTrack): String =
        if (track.id.contains(':')) track.id.substringBefore(':') else "qobuz"

    private fun nameOf(base: String): String = base.substringAfter("//").substringBefore('/')

    private fun normalise(link: String): String? {
        val text = link.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        val cut = withScheme.trimEnd('/')
        return cut.takeIf { it.substringAfter("://").contains('.') || it.substringAfter("://").contains("localhost") }
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
