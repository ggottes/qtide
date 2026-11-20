package com.qtide

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.ModuleHost
import java.net.URLEncoder

/**
 * Deezer: the ARL cookie a signed-in deezer.com keeps, then the licence's own stream.
 *
 * The gateway only answers once the session has the site's own cookies, so the first call visits
 * deezer.com the way a browser would. Deezer's stream comes as 2048-byte slices with every third
 * one Blowfish-encrypted; that file is downloaded, decrypted and saved so the player gets one
 * ordinary mp3/flac.
 */
internal object Deezer {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private class Session(
        val apiToken: String,
        val licenseToken: String,
        val canLossless: Boolean,
        val country: String,
    )

    class Found(val bytes: ByteArray, val mimeType: String, val label: String, val codec: String, val sampleRate: Int)

    private var cookies = ""
    private var session: Session? = null

    // ---- session --------------------------------------------------------------------------

    private fun warmUp(host: ModuleHost) {
        if (cookies.isNotBlank()) return
        val res = host.http(HttpRequest("https://www.deezer.com/en/", headers = mapOf("User-Agent" to UA)))
        if (!res.ok) return
        // a host bridge hands headers back lowercased; the site sets several cookies in one header
        val setCookie = res.headers["set-cookie"] ?: res.headers["Set-Cookie"] ?: return
        cookies = setCookie.split("\n").joinToString("; ") { it.substringBefore(";").trim() }
            .trim(';', ' ')
    }

    private fun open(host: ModuleHost, arl: String): Session? {
        session?.let { return it }
        if (arl.isBlank()) return null
        warmUp(host)
        val user = gateway(host, arl, "deezer.getUserData", null) ?: return null
        val token = Json.text(user, "checkForm") ?: return null
        val options = Json.objOf(Json.objOf(user["USER"])?.get("OPTIONS"))
        val made = Session(
            apiToken = token,
            licenseToken = Json.text(options, "license_token").orEmpty(),
            canLossless = Json.bool(options, "web_lossless") || Json.bool(options, "mobile_lossless"),
            country = Json.text(user, "COUNTRY") ?: "US",
        )
        session = made
        return made
    }

    private fun gateway(host: ModuleHost, arl: String, method: String, args: Map<String, Any?>?, token: String? = null): Map<String, Any?>? {
        val url = "https://www.deezer.com/ajax/gw-light.php?api_version=1.0&api_token=" +
            enc(token ?: "null") + "&input=3&method=$method"
        val cookie = "arl=" + arl + if (cookies.isBlank()) "" else "; $cookies"
        val response = host.http(
            HttpRequest(
                url = url,
                method = "POST",
                headers = mapOf("Content-Type" to "application/json", "User-Agent" to UA, "Cookie" to cookie),
                body = Json.write(args ?: emptyMap<String, Any?>()).toByteArray(),
            ),
        )
        if (!response.ok) return null
        val body = Json.obj(response.text) ?: return null
        val errors = Json.objOf(body["error"])
        if (errors != null && errors.isNotEmpty()) return null
        return Json.objOf(body["results"])
    }

    // ---- search ---------------------------------------------------------------------------

    fun search(host: ModuleHost, arl: String, query: String, limit: Int): List<CatalogTrack> {
        val open = open(host, arl) ?: return emptyList()
        val response = host.http(
            HttpRequest(
                "https://api.deezer.com/search?q=${enc(query)}&limit=$limit",
                headers = mapOf("User-Agent" to UA),
            ),
        )
        if (!response.ok) return emptyList()
        val data = Json.list(Json.obj(response.text), "data") ?: return emptyList()
        return data.mapNotNull { element ->
            val o = Json.objOf(element) ?: return@mapNotNull null
            val id = Json.text(o, "id") ?: return@mapNotNull null
            CatalogTrack(
                id = id,
                title = Json.text(o, "title").orEmpty(),
                artist = Json.text(Json.objOf(o["artist"]), "name").orEmpty(),
                album = Json.text(Json.objOf(o["album"]), "title"),
                durationMs = Json.long(o, "duration")?.times(1000),
                isrc = Json.text(o, "isrc"),
            ).also { if (open.canLossless) Unit }
        }
    }

    // ---- stream ---------------------------------------------------------------------------

    /**
     * Deezer's files are 2048-byte slices with every third one Blowfish-encrypted under a key made
     * from the track's own id. The file is decrypted here and handed over whole, so the player opens
     * one ordinary flac/mp3 and the 30-second preview is never the fallback for a listening account.
     */
    fun stream(host: ModuleHost, arl: String, trackId: String, quality: String): Found? {
        val open = open(host, arl) ?: return null
        val wantFlac = (quality == "lossless" || quality == "hires") && open.canLossless
        val format = if (wantFlac) "FLAC" else if (quality == "low") "MP3_128" else "MP3_320"

        // the web api's public track row carries the preview and the hashes; a full file needs the licence
        val row = host.http(HttpRequest("https://api.deezer.com/track/${enc(trackId)}", headers = mapOf("User-Agent" to UA)))
        val rowJson = if (row.ok) Json.obj(row.text) else null

        // no account licence: the preview is what's left, and it is a plain public file
        val preview = Json.text(rowJson, "preview")

        val info = gateway(host, arl, "song.getData", mapOf("SNG_ID" to trackId), open.apiToken)
        val trackToken = Json.text(info, "TRACK_TOKEN")
        var url: String? = null
        if (open.licenseToken.isNotBlank() && trackToken != null) {
            url = licenceUrl(host, open.licenseToken, trackToken, format)
            if (url == null && format == "FLAC") url = licenceUrl(host, open.licenseToken, trackToken, "MP3_320")
        }

        if (url == null) {
            url = preview ?: return null
            val bytes = download(host, url) ?: return null
            return Found(bytes, "audio/mpeg", "Preview · MP3", "MP3", 44100)
        }

        val raw = download(host, url) ?: return null
        // every /media/ and /mobile/ path is striped; decrypted in place, one buffer
        val bytes = if (url.contains("/media/") || url.contains("/mobile/")) {
            runCatching { Blowfish.decryptStripe(raw, blowfishKey(trackId)) }.getOrDefault(raw)
        } else raw

        return if (format == "FLAC") {
            Found(bytes, "audio/flac", "CD · FLAC", "FLAC", 44100)
        } else {
            Found(bytes, "audio/mpeg", if (format == "MP3_128") "MP3 128" else "MP3 320", "MP3", 44100)
        }
    }

    private fun licenceUrl(host: ModuleHost, licenseToken: String, trackToken: String, format: String): String? {
        val response = host.http(
            HttpRequest(
                url = "https://media.deezer.com/v1/get_url",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json", "User-Agent" to UA),
                body = """{"license_token":"$licenseToken","media":[{"type":"FULL","formats":[{"cipher":"BF_CBC_STRIPE","format":"$format"}]}],"track_tokens":["$trackToken"]}""".toByteArray(),
            ),
        )
        if (!response.ok) return null
        val data = Json.list(Json.obj(response.text), "data")?.firstOrNull()?.let(Json::objOf) ?: return null
        val media = Json.list(data, "media")?.firstOrNull()?.let(Json::objOf)
        val sources = Json.list(media, "sources")?.firstOrNull()?.let(Json::objOf) ?: return null
        return Json.text(sources, "url")
    }

    /** md5 of the track id, its two halves xored with Deezer's app secret: the striping key */
    private fun blowfishKey(trackId: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(trackId.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        val key = CharArray(16) { i ->
            (digest[i].code xor digest[i + 16].code xor "g4el58wc0zvf9na1"[i].code).toChar()
        }
        return ByteArray(16) { key[it].code.toByte() }
    }

    private fun download(host: ModuleHost, url: String): ByteArray? {
        val response = host.http(HttpRequest(url, headers = mapOf("User-Agent" to UA)))
        return if (response.ok && response.body.isNotEmpty()) response.body else null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
