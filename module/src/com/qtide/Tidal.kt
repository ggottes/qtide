package com.qtide

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.StreamFormat
import java.net.URLEncoder
import java.util.Base64

/**
 * Tidal: a session from a refresh token (or a device login), a search, and the one stream the
 * player can open.
 *
 * Two client pairs, because Tidal hands out different tiers by client: the hi-res pair is asked for
 * the top tier, the other gives CD. Both are the public pairs every downloader uses; they name an
 * app, never an account. Measured against a real account on 2026-11-20.
 *
 * A chip is a ceiling, not a demand: Hi-Res asks for `HI_RES_LOSSLESS` and falls to `LOSSLESS`, and
 * High asks for `HIGH` and falls to `LOW`, so a track Tidal has no master for still plays instead of
 * failing. The label always names the tier that actually answered.
 */
internal object Tidal {

    private const val HIRES_ID = "fX2JxdmntZWK0ixT"
    private const val HIRES_SECRET = "1Nm5AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAavxAg="
    // the dash manifest's own shapes: an attribute's value sits in double quotes
    private const val ATTRIBUTE_RATE = "audioSamplingRate=\"(\\d+)\""
    private const val ATTRIBUTE_INIT = "initialization=\"([^\"]+)\""
    private const val ATTRIBUTE_MEDIA = "media=\"([^\"]+)\""
    private const val SEGMENT_TIMELINE = "<S d=\"\\d+\"(?: r=\"(\\d+)\")?"

    private const val CD_ID = "4N3n6Q1x95LL5K7p"
    private const val CD_SECRET = "oKOXfJW371cX6xaZ0PyhgGNBdNLlBZd4AKKYougMjik="

    private const val AUTH = "https://auth.tidal.com/v1/oauth2"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    // what a pending device login remembers, in the module's own storage
    private const val PENDING_CODE = "deviceCode"
    private const val PENDING_USER = "deviceUserCode"
    private const val PENDING_UNTIL = "deviceExpiresAt"
    private const val PENDING_TRIED = "deviceRequestedAt"
    private const val PENDING_LINK = "deviceLink"

    /** the steps each chip asks for, best first */
    private val RUNGS = mapOf(
        "hires" to listOf("HI_RES_LOSSLESS", "LOSSLESS"),
        "lossless" to listOf("LOSSLESS"),
        "high" to listOf("HIGH", "LOW"),
    )

    class Session(val accessToken: String, val hiresToken: String, val country: String) {
        /** the token a step needs: the hi-res pair mints the top tier, the CD pair everything else */
        fun tokenFor(step: String): String = if (step.startsWith("HI_RES") || step == "HIGH") hiresToken else accessToken
    }

    /** either a link the player opens, or the bytes of a file the module has to save first */
    class Found(
        val url: String? = null,
        val audio: ByteArray? = null,
        val format: StreamFormat = StreamFormat.Progressive,
        val label: String,
        val codec: String,
        val sampleRate: Int,
    )

    // ---- session --------------------------------------------------------------------------

    fun login(host: ModuleHost, refreshToken: String): Session? {
        // the CD tier's token is the one every call can use; the hi-res pair mints its own on top
        val cd = exchange(host, refreshToken, CD_ID, CD_SECRET) ?: return null
        val hires = exchange(host, refreshToken, HIRES_ID, HIRES_SECRET) ?: cd
        val me = bodyOf(host, "https://api.tidal.com/v1/sessions", cd) ?: return null
        return Session(cd, hires, Json.text(me, "countryCode") ?: "US")
    }

    private fun exchange(host: ModuleHost, refreshToken: String, id: String, secret: String): String? {
        val body = form(
            "client_id" to id,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
            "scope" to "r_usr+w_usr+w_sub",
        )
        val response = host.http(
            HttpRequest(
                url = "$AUTH/token",
                method = "POST",
                headers = mapOf(
                    "authorization" to "Basic " + basic(id, secret),
                    "content-type" to "application/x-www-form-urlencoded",
                ),
                body = body.toByteArray(),
            ),
        )
        if (!response.ok) return null
        return Json.text(Json.obj(response.text), "access_token")
    }

    // ---- device login ---------------------------------------------------------------------

    /**
     * Starts a device login and returns the code to show: the user opens link.tidal.com and types
     * it, and the next call to [pollDevice] (or a later play) picks up the refresh token.
     *
     * The code is remembered for its five minutes, so pressing play twice doesn't mint a second one.
     */
    fun beginDevice(host: ModuleHost, fresh: Boolean = false): String? {
        if (fresh) forget(host)
        val asked = host.storage.get(PENDING_TRIED)?.toLongOrNull()
        val until = host.storage.get(PENDING_UNTIL)?.toLongOrNull()
        val user = host.storage.get(PENDING_USER)
        val now = System.currentTimeMillis()
        if (user != null && until != null && now < until) return user
        if (asked != null && now - asked < 30_000) return user

        host.storage.set(PENDING_TRIED, "$now")
        val response = host.http(
            HttpRequest(
                url = "$AUTH/device_authorization",
                method = "POST",
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                body = form("client_id" to CD_ID, "scope" to "r_usr w_usr w_sub").toByteArray(),
            ),
        )
        val answer = if (response.ok) Json.obj(response.text) else null
        val device = Json.text(answer, "deviceCode")
        val code = Json.text(answer, "userCode")
        if (device == null || code == null) return null
        val ttl = (Json.long(answer, "expiresIn") ?: 300L) * 1000
        host.storage.set(PENDING_CODE, device)
        host.storage.set(PENDING_USER, code)
        host.storage.set(PENDING_UNTIL, "${now + ttl}")
        // what python-tidal and tiddl show: the page with the code already in it, so nothing is typed
        Json.text(answer, "verificationUriComplete")?.takeIf { it.isNotBlank() }?.let { link ->
            host.storage.set(PENDING_LINK, if (link.startsWith("http")) link else "https://$link")
        }
        return code
    }

    /** the link.tidal.com page for the pending code, with the code already in the address */
    fun pendingLink(host: ModuleHost): String? = host.storage.get(PENDING_LINK)

    /** one poll of a pending device login: the refresh token once the user approved it, null while waiting */
    fun pollDevice(host: ModuleHost): String? {
        val device = host.storage.get(PENDING_CODE) ?: return null
        val until = host.storage.get(PENDING_UNTIL)?.toLongOrNull()
        if (until != null && System.currentTimeMillis() > until) {
            forget(host)
            return null
        }
        val response = host.http(
            HttpRequest(
                url = "$AUTH/token",
                method = "POST",
                headers = mapOf(
                    "authorization" to "Basic " + basic(CD_ID, CD_SECRET),
                    "content-type" to "application/x-www-form-urlencoded",
                ),
                body = form(
                    "client_id" to CD_ID,
                    "device_code" to device,
                    "grant_type" to DEVICE_GRANT,
                    "scope" to "r_usr w_usr w_sub",
                ).toByteArray(),
            ),
        )
        if (response.ok) {
            val refresh = Json.text(Json.obj(response.text), "refresh_token")
            if (refresh != null) forget(host)
            return refresh
        }
        // the code ran out: drop it, so the next play asks for a fresh one
        val error = Json.text(Json.obj(response.text), "error")
        if (error == "expired_token" || error == "access_denied") forget(host)
        return null
    }

    fun forget(host: ModuleHost) {
        listOf(PENDING_CODE, PENDING_USER, PENDING_UNTIL, PENDING_TRIED, PENDING_LINK).forEach { host.storage.set(it, null) }
    }

    // ---- search ---------------------------------------------------------------------------

    fun search(host: ModuleHost, session: Session, query: String, limit: Int): List<CatalogTrack> {
        val url = "https://api.tidal.com/v1/search/tracks?query=${enc(query)}&limit=$limit&countryCode=${enc(session.country)}"
        val body = bodyOf(host, url, session.accessToken) ?: return emptyList()
        val items = Json.list(body, "items") ?: return emptyList()
        return items.mapNotNull { element ->
            val o = Json.objOf(element) ?: return@mapNotNull null
            val id = Json.text(o, "id") ?: return@mapNotNull null
            val artists = Json.list(o, "artists")
                ?.mapNotNull { Json.text(Json.objOf(it), "name") }
                ?.joinToString(", ")
                .orEmpty()
            CatalogTrack(
                id = id,
                title = Json.text(o, "title").orEmpty(),
                artist = artists.ifBlank { Json.text(Json.objOf(o["artist"]), "name").orEmpty() },
                album = Json.text(Json.objOf(o["album"]), "title"),
                durationMs = Json.long(o, "duration")?.times(1000),
            )
        }
    }

    // ---- stream ---------------------------------------------------------------------------

    fun stream(host: ModuleHost, session: Session, trackId: String, quality: String): Found? =
        plan(host, session, trackId, quality).let { steps ->
            val tries = steps.mapNotNull { step -> one(host, session, trackId, step) }
            // what the chip asked for is FLAC: take the first real FLAC, and only fall back to AAC
            // when Tidal had none, with the label saying AAC
            tries.firstOrNull { it.codec == "FLAC" } ?: tries.lastOrNull()
        }

    /**
     * What Tidal will actually serve, asked before anything is requested: `audioModes` is the song's
     * own row (`STEREO` is mp4 audio, `LOSSLESS`/`HIRES_LOSSLESS` are FLAC, and a song with no
     * `LOSSLESS`/`HIRES_LOSSLESS` has no FLAC at all).
     *
     * Without this, a track with no hi-res master still answered a hi-res request — with AAC. Asking
     * for the best tier the song has is what keeps "Hi-Res" from playing 320 kbps and calling itself
     * lossless.
     */
    private fun plan(host: ModuleHost, session: Session, trackId: String, quality: String): List<String> {
        val row = bodyOf(host, trackRows(trackId, session.country), session.accessToken)
        val song = Json.objOf(row?.let { Json.list(it, "data") }?.firstOrNull()) ?: return Ladder.steps(quality, RUNGS)
        val modes = (Json.list(song, "audioModes") ?: return Ladder.steps(quality, RUNGS))
            .mapNotNull { it?.toString() }
            .toSet()
        // no modes at all means Tidal didn't say: ask and let it answer
        if (modes.isEmpty()) return Ladder.steps(quality, RUNGS)
        val lossless = "LOSSLESS" in modes || "HIRES_LOSSLESS" in modes
        val hires = "HIRES_LOSSLESS" in modes
        val stereo = "STEREO" in modes || "DOLBY_ATMOS" in modes || lossless
        // the plan is a ceiling: ask for the best the song has, never less than the chip's floor
        val steps = when (quality) {
            "hires" -> when {
                hires -> listOf("HI_RES_LOSSLESS", "LOSSLESS")
                lossless -> listOf("LOSSLESS")
                else -> listOf("HIGH", "LOW")
            }
            else -> Ladder.steps(quality, RUNGS)
        }
        return steps.filter { step ->
            when {
                step == "HI_RES_LOSSLESS" -> hires
                step == "LOSSLESS" -> lossless
                step == "HIGH" || step == "LOW" -> stereo
                else -> true
            }
        }
    }

    private fun one(host: ModuleHost, session: Session, trackId: String, step: String): Found? {
        val body = playback(host, session.tokenFor(step), trackId, step, session.country) ?: return null
        val manifest = Json.text(body, "manifest")?.let(::decode) ?: return null
        val mime = Json.text(body, "manifestMimeType").orEmpty()

        if (mime.contains("dash") || manifest.trimStart().startsWith("<")) {
            // hi-res is a dash manifest of ~75 fmp4 segments (flac inside an mp4 container). handing
            // the manifest over as a data uri lets the player fetch the segments itself, in ranges,
            // as the song plays: nothing is downloaded here and the music starts at once. mochy's
            // own test button plays without labelling the item as dash, so it reports Play as
            // failed for this tier; the player itself takes it.
            val rate = Regex(ATTRIBUTE_RATE).find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: 96000
            val data = "data:application/dash+xml;base64," +
                Base64.getEncoder().encodeToString(manifest.toByteArray(Charsets.UTF_8))
            return Found(url = data, format = StreamFormat.Dash, label = "${rate / 1000} kHz · FLAC", codec = "FLAC", sampleRate = rate)
        }

        // a json file list: the first url is the whole file
        val parsed = Json.obj(manifest) ?: return null
        val url = Json.list(parsed, "urls")?.firstOrNull()?.let { Json.text(mapOf("u" to it), "u") } ?: return null
        val codecs = Json.text(parsed, "codecs").orEmpty()
        return when {
            codecs.contains("flac", ignoreCase = true) ->
                Found(url = url, label = "CD · FLAC", codec = "FLAC", sampleRate = 44100)
            // the lossy tiers are mp4 audio, not aac-in-adts: the player needs the codec named right
            // a lossy answer, named by the tier Tidal says it granted rather than by what was asked
            else -> when (Json.text(body, "audioQuality")) {
                "HIGH" -> Found(url = url, label = "AAC 320", codec = "AAC", sampleRate = 44100)
                "LOW" -> Found(url = url, label = "AAC 96", codec = "AAC", sampleRate = 44100)
                else -> Found(url = url, label = "AAC", codec = "AAC", sampleRate = 44100)
            }
        }
    }

    private fun trackRows(trackId: String, country: String): String =
        "https://api.tidal.com/v1/tracks/$trackId?countryCode=${enc(country)}"

    private fun playback(host: ModuleHost, token: String, trackId: String, quality: String, country: String): Map<String, Any?>? {
        val url = "https://api.tidal.com/v1/tracks/$trackId/playbackinfopostpaywall" +
            "?audioquality=$quality&playbackmode=STREAM&assetpresentation=FULL&countryCode=${enc(country)}"
        return bodyOf(host, url, token)
    }

    /** the json an http answer carries, or null when it didn't answer with one */
    private fun bodyOf(host: ModuleHost, url: String, token: String): Map<String, Any?>? {
        val response = get(host, url, token)
        if (!response.ok) return null
        return Json.obj(response.text)
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun get(host: ModuleHost, url: String, token: String) =
        host.http(HttpRequest(url, headers = mapOf("authorization" to "Bearer $token")))

    private fun basic(id: String, secret: String): String =
        Base64.getEncoder().encodeToString("$id:$secret".toByteArray())

    private fun decode(base64: String): String =
        String(Base64.getDecoder().decode(base64), Charsets.UTF_8)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${enc(v)}" }

}
