package com.qtide

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.StreamFormat
import java.net.URLEncoder
import java.util.Base64

/**
 * Tidal: a session from a refresh token, a search, and the one stream the player can open.
 *
 * Two client pairs, because Tidal hands out different tiers by client: the hi-res pair is asked for
 * the top tier, the other gives CD. Both are the public pairs every downloader uses; they name an
 * app, never an account. Measured against a real account on 2026-11-20.
 */
internal object Tidal {

    private const val HIRES_ID = "fX2JxdmntZWK0ixT"
    private const val HIRES_SECRET = "1Nm5AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAavxAg="
    // the dash manifest's own shapes. kotlin raw strings keep every backslash, and java's regex
    // reads \u0022 as a quote, so none of these needs escaping inside the source.
    private const val ATTRIBUTE_RATE = """audioSamplingRate=\u0022(\d+)\u0022"""
    private const val ATTRIBUTE_INIT = """initialization=\u0022([^\u0022]+)\u0022"""
    private const val ATTRIBUTE_MEDIA = """media=\u0022([^\u0022]+)\u0022"""
    private const val SEGMENT_TIMELINE = """<S d=\u0022\d+\u0022(?: r=\u0022(\d+)\u0022)?"""

    private const val CD_ID = "4N3n6Q1x95LL5K7p"
    private const val CD_SECRET = "oKOXfJW371cX6xaZ0PyhgGNBdNLlBZd4AKKYougMjik="

    class Session(val accessToken: String, val hiresToken: String, val country: String)

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
        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray())
        val response = host.http(
            HttpRequest(
                url = "https://auth.tidal.com/v1/oauth2/token",
                method = "POST",
                headers = mapOf(
                    "authorization" to "Basic $basic",
                    "content-type" to "application/x-www-form-urlencoded",
                ),
                body = body.toByteArray(),
            ),
        )
        if (!response.ok) return null
        return Json.text(Json.obj(response.text)?.let { it }, "access_token")
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

    fun stream(host: ModuleHost, session: Session, trackId: String, quality: String): Found? {
        val hires = quality == "hires"
        val token = if (hires) session.hiresToken else session.accessToken
        val wanted = if (hires) "HI_RES_LOSSLESS" else "LOSSLESS"
        val body = playback(host, token, trackId, wanted, session.country) ?: return null
        val mime = Json.text(body, "manifestMimeType").orEmpty()
        val manifest = Json.text(body, "manifest")?.let(::decode) ?: return null

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
        val flac = Json.text(parsed, "codecs").orEmpty().contains("flac", ignoreCase = true)
        return if (flac) {
            Found(url = url, label = "CD · FLAC", codec = "FLAC", sampleRate = 44100)
        } else {
            val granted = Json.text(body, "audioQuality").orEmpty().ifBlank { "AAC" }
            Found(url = url, label = granted, codec = "AAC", sampleRate = 0)
        }
    }

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

    private fun fetch(host: ModuleHost, url: String): ByteArray? {
        val response = host.http(HttpRequest(url))
        return if (response.ok) response.body else null
    }

    // ---- plumbing -------------------------------------------------------------------------

    private fun get(host: ModuleHost, url: String, token: String) =
        host.http(HttpRequest(url, headers = mapOf("authorization" to "Bearer $token")))

    private fun decode(base64: String): String =
        String(Base64.getDecoder().decode(base64), Charsets.UTF_8)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${enc(v)}" }

}
