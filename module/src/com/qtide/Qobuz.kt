package com.qtide

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.StreamFormat
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Qobuz: an account token plus the app id and secret their own web player ships.
 *
 * A stream is a plain signed URL to a FLAC file, so there is nothing to stitch or decrypt; the
 * signature is the same md5 the web player makes (track id, format, timestamp, secret).
 */
internal object Qobuz {

    private const val API = "https://www.qobuz.com/api.json/0.2"

    // Qobuz's own format ids: 27 is up to 24/192, 7 is 24/44.1-96, 6 is CD. A Studio master asked
    // for at 27 can come back at 7 (Qobuz negotiates down), and a track with no hi-res at all is
    // refused rather than downgraded, so the ladder does the stepping.
    private val RUNGS = mapOf(
        "hires" to listOf("27", "7", "6"),
        "lossless" to listOf("6"),
    )

    class Session(
        val userToken: String,
        val appId: String,
        val secrets: List<String>,
        val country: String,
        /** what user/get says about the account, for the page: display_name, email, subscription label */
        val who: String?,
        val plan: String?,
    )

    /** the last answer's status, for the page when a call comes back empty */
    private var lastCode: Int? = null

    /** what went wrong the last time [login] came back empty, as a sentence for the module's page */
    var lastRefusal = ""
        private set

    class Found(val url: String, val label: String, val codec: String, val sampleRate: Int)

    // ---- session --------------------------------------------------------------------------

    /**
     * [userToken] from the account, [appId]/[appSecret] optional. Without a pair the app id and the
     * secrets are read from the web player's own script, the way every downloader does it.
     */
    fun login(host: ModuleHost, userToken: String, appId: String, appSecret: String): Session? {
        lastRefusal = ""
        if (userToken.isBlank()) return null
        var id = appId.trim()
        var secrets = if (appSecret.isBlank()) emptyList() else listOf(appSecret.trim())

        if (id.isBlank() || secrets.isEmpty()) {
            val scraped = scrape(host) ?: return null
            if (id.isBlank()) id = scraped.first
            if (secrets.isEmpty()) secrets = scraped.second
        }

        val me = call(host, "user/get", mapOf("app_id" to id), userToken)
        if (me == null) {
            lastRefusal = "Qobuz answered user/get with ${lastCode ?: "nothing"} for app id $id"
            return null
        }
        // the same call the token is checked with also names the account and its plan
        val sub = Json.objOf(me["subscription"])
        val plan = Json.text(sub, "offer").orEmpty().ifBlank { Json.text(sub, "label").orEmpty() }.ifBlank { null }
        val who = Json.text(me, "display_name").orEmpty().ifBlank { Json.text(me, "login").orEmpty() }
            .ifBlank { Json.text(me, "email").orEmpty() }.ifBlank { null }
        return Session(userToken, id, secrets, Json.text(me, "country_code") ?: "US", who, plan)
    }

    /** the app id and candidate secrets out of play.qobuz.com's bundle, as the reference does */
    private fun scrape(host: ModuleHost): Pair<String, List<String>>? {
        val login = host.http(HttpRequest("https://play.qobuz.com/login"))
        if (!login.ok) return null
        val bundlePath = Regex("""<script src="(/resources/[^"]+bundle\.js)"></script>""")
            .find(login.text)?.groupValues?.get(1) ?: return null
        val bundle = host.http(HttpRequest("https://play.qobuz.com$bundlePath"))
        if (!bundle.ok) return null

        val appId = Regex("""production:\{api:\{appId:"(\d{9})"""").find(bundle.text)?.groupValues?.get(1) ?: return null
        val seeds = Regex("""[a-z]\.initialSeed\("([\w=]+)",window\.utimezone\.([a-z]+)\)""")
            .findAll(bundle.text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val infos = Regex("""name:"\w+/([A-Za-z]+)",info:"([\w=]+)",extras:"([\w=]+)"""")
            .findAll(bundle.text).associate { it.groupValues[1].lowercase() to (it.groupValues[2] to it.groupValues[3]) }
        if (seeds.isEmpty()) return null

        val secrets = seeds.mapNotNull { (seed, tz) ->
            val info = infos[tz] ?: return@mapNotNull null
            var raw = seed + info.first + info.second
            raw = raw.dropLast(44)
            raw += "=".repeat((4 - raw.length % 4) % 4)
            runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrNull()
        }
        return appId to secrets
    }

    // ---- search ---------------------------------------------------------------------------

    fun search(host: ModuleHost, session: Session, query: String, limit: Int): List<CatalogTrack> {
        val body = call(
            host, "track/search",
            mapOf("query" to query, "limit" to "$limit", "app_id" to session.appId),
            session.userToken,
        ) ?: return emptyList()
        val tracks = (Json.objOf(body["tracks"]))?.let { Json.list(it, "items") } ?: return emptyList()
        return tracks.mapNotNull { element ->
            val o = Json.objOf(element) ?: return@mapNotNull null
            val id = Json.text(o, "id") ?: return@mapNotNull null
            val performer = Json.text(Json.objOf(o["performer"]), "name")
                ?: Json.text(Json.objOf(Json.objOf(o["album"])?.get("artist")), "name")
            CatalogTrack(
                id = id,
                title = Json.text(o, "title").orEmpty(),
                artist = performer.orEmpty(),
                album = Json.text(Json.objOf(o["album"]), "title"),
                durationMs = Json.long(o, "duration")?.times(1000),
                isrc = Json.text(o, "isrc"),
            )
        }
    }

    // ---- stream ---------------------------------------------------------------------------

    /** quality: "lossless" (16/44.1) or "hires" (up to 24/192, falling back to 24/96 and then CD) */
    fun stream(host: ModuleHost, session: Session, trackId: String, quality: String): Found? {
        // the tier is the outer loop: a secret that signs 27 badly would otherwise cost the account
        // its CD copy too, because the ladder never gets a second secret at the same tier
        for (format in Ladder.steps(quality, RUNGS)) {
            for (secret in session.secrets) {
                one(host, session, secret, trackId, format.toInt())?.let { return it }
            }
        }
        return null
    }

    private fun one(host: ModuleHost, session: Session, secret: String, trackId: String, format: Int): Found? {
        val ts = System.currentTimeMillis() / 1000
        val signed = "trackgetFileUrlformat_id${format}intentstreamtrack_id$trackId$ts"
        val sig = md5(signed + secret)
        val body = call(
            host, "track/getFileUrl",
            mapOf(
                "request_ts" to "$ts",
                "request_sig" to sig,
                "track_id" to trackId,
                "format_id" to "$format",
                "intent" to "stream",
            ),
            session.userToken,
        ) ?: return null
        val url = Json.text(body, "url") ?: return null
        val bits = Json.long(body, "bit_depth")?.toInt() ?: if (format == 6) 16 else 24
        val rate = Json.long(body, "sampling_rate")?.toInt() ?: if (format == 6) 44100 else 96000
        return Found(url, "$bits-bit · ${rate / 1000} kHz · FLAC", "FLAC", rate)
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun call(host: ModuleHost, endpoint: String, params: Map<String, String>, token: String): Map<String, Any?>? {
        val qs = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val headers = mutableMapOf("X-User-Auth-Token" to token)
        params["app_id"]?.let { headers["X-App-Id"] = it }
        val response = host.http(HttpRequest("$API/$endpoint?$qs", headers = headers))
        lastCode = response.code
        if (!response.ok) return null
        return Json.obj(response.text)
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
