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
 * ordinary mp3/flac. The 30-second preview is never used: when the account can't be given a full
 * file, [lastReason] says which call refused and the song doesn't play.
 */
internal object Deezer {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** the formats each chip asks for, best first; a HiFi account's FLAC, then the mp3s */
    private val RUNGS = mapOf(
        "lossless" to listOf("FLAC", "MP3_320", "MP3_128"),
        "high" to listOf("MP3_320", "MP3_128"),
        "low" to listOf("MP3_128"),
    )

    private class Session(
        val apiToken: String,
        val licenseToken: String,
        val canLossless: Boolean,
        val canStream: Boolean,
        val country: String,
    )

    class Found(val bytes: ByteArray, val mimeType: String, val label: String, val codec: String, val sampleRate: Int)

    private var session: Session? = null

    /** why the last [stream] came back empty, as a sentence for the module's page */
    var lastReason = ""
        private set

    /** the last thing the gateway refused with, for the page when a call comes back empty */
    var lastRefusal = ""
        private set

    /** how long the last whole-file download took, for a step that was cut short by the app's limit */
    var lastTiming = ""
        private set

    /** a fresh ARL on the page: the old session was for the old cookie, so it goes */
    fun reset() {
        session = null
    }

    /** whether the cookie is a real account's: Deezer's anonymous answer (user id 0) is a no */
    fun check(host: ModuleHost, arl: String): Boolean {
        reset()
        return open(host, arl) != null
    }

    // ---- session --------------------------------------------------------------------------

    private fun warmUp(host: ModuleHost) {
        if (jar.isNotEmpty()) return
        val res = host.http(HttpRequest("https://www.deezer.com/en/", headers = mapOf("User-Agent" to UA)))
        if (!res.ok) return
        takeCookies(res)
    }

    /**
     * The site's cookies as a browser keeps them: one value per name, the newest wins.
     *
     * This matters more than it looks. The gateway's csrf token is bound to the session's `sid`, and
     * an answer to `deezer.getUserData` carries a *new* sid. Appending that to the anonymous one the
     * warm-up fetched leaves two `sid=...` in the header, and the gateway reads the first: every
     * later call answers `Invalid CSRF token` and no `TRACK_TOKEN` ever arrives.
     */
    private val jar = LinkedHashMap<String, String>()

    private fun takeCookies(res: com.mochyy.mochymusic.module.HttpResponse) {
        val setCookie = res.header("set-cookie") ?: return
        if (setCookie.isBlank()) return
        setCookie.split("\n").forEach { line ->
            val pair = line.substringBefore(";").trim()
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
    }

    private fun cookieHeader(arl: String): String =
        "arl=" + arl + jar.entries.filter { it.key != "arl" }.joinToString("") { "; ${it.key}=${it.value}" }

    private fun open(host: ModuleHost, arl: String): Session? {
        session?.let { return it }
        if (arl.isBlank()) return null
        warmUp(host)
        lastRefusal = ""
        val user = gateway(host, arl, "deezer.getUserData", null) ?: return null
        lastRefusal = Json.text(user, "VALID_TOKEN_REQUIRED").orEmpty()
        val token = Json.text(user, "checkForm") ?: return null
        val profile = Json.objOf(user["USER"])
        // deezer answers a rejected ARL with its anonymous user, a usable token and streaming off:
        // user id 0 is the tell every other client checks, and without it a dead cookie looked signed in
        val userId = Json.long(profile, "USER_ID") ?: 0
        if (userId == 0L) {
            // deezer answers a rejected ARL with its anonymous user: an old cookie, or this network
            // (a VPN or a datacenter exit) being treated as a guest, which also reports user 0
            lastReason = "Deezer treats this session as a guest (user 0, " +
                (Json.text(user, "OFFER_NAME") ?: "no plan") + ") — the ARL is old, or this network is " +
                "seen as anonymous; try another network or a fresh ARL"
            return null
        }
        val options = Json.objOf(profile?.get("OPTIONS"))
        val made = Session(
            apiToken = token,
            licenseToken = Json.text(options, "license_token").orEmpty(),
            canLossless = Json.bool(options, "web_lossless") || Json.bool(options, "mobile_lossless"),
            canStream = Json.bool(options, "web_streaming") || Json.bool(options, "mobile_streaming") ||
                Json.bool(options, "web_hq") || Json.bool(options, "mobile_hq"),
            country = Json.text(user, "COUNTRY") ?: "US",
        )
        session = made
        return made
    }

    private fun gateway(
        host: ModuleHost,
        arl: String,
        method: String,
        args: Map<String, Any?>?,
        token: String? = null,
        retried: Boolean = false,
    ): Map<String, Any?>? {
        val url = "https://www.deezer.com/ajax/gw-light.php?api_version=1.0&api_token=" +
            enc(token ?: "null") + "&input=3&method=$method"
        val response = host.http(
            HttpRequest(
                url = url,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to UA,
                    "Cookie" to cookieHeader(arl),
                    "Origin" to "https://www.deezer.com",
                    "Referer" to "https://www.deezer.com/",
                ),
                body = Json.write(args ?: emptyMap<String, Any?>()).toByteArray(),
            ),
        )
        if (!response.ok) {
            lastRefusal = "HTTP ${response.code}"
            return null
        }
        // the gateway hands out the sid its next csrf token is bound to
        takeCookies(response)
        val body = Json.obj(response.text) ?: return null
        val errors = Json.objOf(body["error"])
        if (errors != null && errors.isNotEmpty()) {
            val said = errors.values.joinToString("; ") { it?.toString().orEmpty() }.ifBlank { "refused" }
            lastRefusal = said
            // a csrf token only works on the session it was minted for: take a fresh one and retry once
            if (!retried && said.contains("CSRF", ignoreCase = true) && method != "deezer.getUserData") {
                session = null
                val fresh = open(host, arl)?.apiToken
                if (fresh != null) return gateway(host, arl, method, args, fresh, retried = true)
            }
            return null
        }
        return Json.objOf(body["results"])
    }

    // ---- search ---------------------------------------------------------------------------

    fun search(host: ModuleHost, arl: String, query: String, limit: Int): List<CatalogTrack> {
        open(host, arl) ?: return emptyList()
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
            )
        }
    }

    // ---- stream ---------------------------------------------------------------------------

    /**
     * Deezer's files are 2048-byte slices with every third one Blowfish-encrypted under a key made
     * from the track's own id. The file is decrypted here and handed over whole, so the player opens
     * one ordinary flac/mp3.
     *
     * Only a full file is ever handed over: when the licence refuses every format the chip allows,
     * nothing is returned and [lastReason] says why. The public 30-second preview is not a stream.
     */
    fun stream(host: ModuleHost, arl: String, trackId: String, quality: String): Found? {
        lastReason = ""
        lastRefusal = ""
        lastTiming = ""
        val open = open(host, arl) ?: run {
            if (lastReason.isBlank() && lastRefusal.isNotBlank()) {
                lastReason = "Deezer's session was refused: $lastRefusal"
            }
            return null
        }
        if (!open.canStream) {
            lastReason = "this Deezer account has no streaming (Free plan)"
            return null
        }
        if (open.licenseToken.isBlank()) {
            lastReason = "Deezer gave no licence token (the ARL's session is dead)"
            return null
        }

        // a song Deezer has replaced answers with the newer copy under FALLBACK; that copy's token is
        // the one its file is served under, so a replaced track would otherwise come back empty
        var info = gateway(host, arl, "song.getData", mapOf("SNG_ID" to trackId), open.apiToken)
        Json.objOf(info?.get("FALLBACK"))?.takeIf { it.isNotEmpty() }?.let { info = it }
        // user uploads (a negative id) only exist as MP3_MISC; asking for anything else is refused
        val personal = trackId.trim().startsWith("-")
        val trackToken = Json.text(info, "TRACK_TOKEN")
        if (trackToken == null) {
            lastReason = "Deezer has no file for this track (region or rights)" +
                if (lastRefusal.isNotBlank()) " — $lastRefusal" else ""
            return null
        }

        val steps = if (personal) listOf("MP3_MISC") else Ladder.steps(quality, RUNGS)
        var qualitiesRefused = ""
        for (format in steps) {
            if (format == "FLAC" && !open.canLossless) continue
            val url = licenceUrl(host, open.licenseToken, trackToken, format)
            if (url == null) {
                if (lastRefusal.isNotBlank()) qualitiesRefused = lastRefusal
                // a dead connection isn't a format Deezer turned down: another format would only wait again
                if (lastRefusal.startsWith("media api HTTP") || lastRefusal == "refused") {
                    lastReason = "Deezer's licence call failed (${lastRefusal})"
                    return null
                }
                continue
            }
            val startedAt = System.currentTimeMillis()
            val raw = download(host, url)
            if (raw == null) {
                lastReason = "Deezer's file didn't arrive (the link answered nothing)"
                continue
            }
            val took = System.currentTimeMillis() - startedAt
            // the file is decrypted whole here, so a big FLAC over a slow line must not look like a refusal
            lastTiming = "the ${bytesLabel(format)} file took ${took / 1000} s (${raw.size / 1024} KB)"
            // every /media/ and /mobile/ path is striped; decrypted in place, one buffer
            val bytes = if (url.contains("/media/") || url.contains("/mobile/")) {
                Blowfish.decryptStripe(raw, blowfishKey(trackId))
            } else raw
            if (bytes.isEmpty()) continue
            return found(bytes, format)
        }

        lastReason = when {
            quality == "lossless" && !open.canLossless ->
                "Deezer refused a full file: FLAC needs a HiFi plan, and MP3 was refused too"
            qualitiesRefused.isNotBlank() -> "Deezer refused a full file: $qualitiesRefused"
            else -> "Deezer refused every format this chip allows"
        }
        return null
    }

    private fun bytesLabel(format: String) = when (format) {
        "FLAC" -> "FLAC"
        "MP3_128" -> "MP3 128"
        "MP3_MISC" -> "upload"
        else -> "MP3 320"
    }

    /** the file as it actually came back: the mime, label and codec follow the format that answered */
    private fun found(bytes: ByteArray, format: String): Found = when (format) {
        "FLAC" -> Found(bytes, "audio/flac", "CD · FLAC", "FLAC", flacRate(bytes))
        "MP3_128" -> Found(bytes, "audio/mpeg", "MP3 128", "MP3", 44100)
        "MP3_MISC" -> Found(bytes, "audio/mpeg", "Upload · MP3", "MP3", 44100)
        else -> Found(bytes, "audio/mpeg", "MP3 320", "MP3", 44100)
    }

    /** the sample rate out of a FLAC's STREAMINFO, so the player's badge isn't a guess */
    private fun flacRate(bytes: ByteArray): Int {
        if (bytes.size < 26 || bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte()
        ) return 44100
        // STREAMINFO starts at byte 8 and its 20-bit rate at 18: block size 16 bits, then 16, then
        // min 24 + max 24, then rate 20 + channels 3 + bits 5 + samples 36
        val a = bytes[18].toInt() and 0xff
        val b = bytes[19].toInt() and 0xff
        val c = bytes[20].toInt() and 0xff
        val rate = (a shl 12) or (b shl 4) or (c shr 4)
        return rate.takeIf { it in 8000..384000 } ?: 44100
    }

    private fun licenceUrl(host: ModuleHost, licenseToken: String, trackToken: String, format: String): String? {
        val response = host.http(
            HttpRequest(
                url = "https://media.deezer.com/v1/get_url",
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to UA,
                    "Origin" to "https://www.deezer.com",
                    "Referer" to "https://www.deezer.com/",
                ),
                body = """{"license_token":"$licenseToken","media":[{"type":"FULL","formats":[{"cipher":"BF_CBC_STRIPE","format":"$format"}]}],"track_tokens":["$trackToken"]}""".toByteArray(),
            ),
        )
        if (!response.ok) {
            lastRefusal = "media api HTTP ${response.code}"
            return null
        }
        val body = Json.obj(response.text) ?: return null
        Json.list(body, "errors")?.firstOrNull()?.let(Json::objOf)?.let { error ->
            val text = listOfNotNull(Json.text(error, "message"), Json.text(error, "code"))
                .joinToString(" ")
                .ifBlank { "refused" }
            lastRefusal = text
        }
        val data = Json.list(body, "data")?.firstOrNull()?.let(Json::objOf) ?: return null
        val media = Json.list(data, "media")?.firstOrNull()?.let(Json::objOf) ?: return null
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
