package com.qtide

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleArt
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackQuery

/**
 * Deezer in Mochy, on your own account.
 *
 * The `arl` cookie is the sign-in. The browser above opens deezer.com so it can be copied from
 * there, but the cookie is HttpOnly and Mochy never forwards it, so the field stays.
 *
 * Deezer answers with a file whose every third 2048-byte slice is Blowfish-encrypted, so the module
 * decrypts it and saves one plain flac/mp3 in its own folder. Only a full file is ever played: an
 * account that can't be given one gets a sentence saying which call refused, never the 30-second
 * preview.
 */
class QtideDeezer : SourceModule, ModuleFields, ModuleArt {

    override val info = ModuleInfo(
        id = "qtide-deezer",
        name = "Qtide — Deezer",
        version = "1.3",
        description = "Deezer: your own account, FLAC on a HiFi plan",
        sourceUrl = "https://github.com/ggottes/qtide",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    // a link, not a path: there is no host yet when mochy asks, and no code source to read the apk from
    override val icon: String? get() = Art.icon("deezer")
    override val banner: String? get() = Art.BANNER

    // the page opens the site; the cookie itself can't come through, so the field does the signing in
    override val signIn = SignInSpec(
        startUrl = "https://www.deezer.com/login",
        origins = listOf(
            "https://www.deezer.com",
            "https://deezer.com",
            "https://www.google.com",
            "https://accounts.google.com",
            "https://www.facebook.com",
            "https://www.apple.com",
        ),
        sessionOrigins = setOf("https://www.deezer.com"),
        desktopSite = false,
        title = "Open Deezer",
        subtitle = "Then copy the arl cookie into the field on the module's page",
    )

    override val fields = listOf(
        ModuleField(
            id = "arl",
            label = "Deezer ARL",
            kind = FieldKind.Text,
            hint = "The arl cookie of a signed-in deezer.com",
            description = "Mochy can't read cookies out of its browser, so the page above is a shortcut only: " +
                "devtools → Application → Cookies → deezer.com → copy arl into here.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        val fresh = values["arl"]?.trim().orEmpty()
        // a new cookie means the old session (and its licence) is for another account
        if (fresh.isNotEmpty() && fresh != host.storage.get("arl")) Deezer.reset()
        values["arl"]?.trim()?.takeIf { it.isNotEmpty() }?.let { host.storage.set("arl", it) }
    }

    /** the ARL is a cookie Mochy never hands over: the page is a shortcut, the field is the sign-in */
    override fun onSignInHeader(name: String, value: String, url: String): Boolean = false

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("lossless", "Lossless", "FLAC on a HiFi plan; MP3 where Deezer has no FLAC"),
                StreamOption("high", "High", "MP3 320"),
                StreamOption("low", "Data saver", "MP3 128"),
            ),
        ),
    )

    private fun arl() = host.storage.get("arl")

    override fun accountState(): AccountState =
        if (arl().isNullOrBlank()) AccountState.SignedOut
        else AccountState.SignedIn(accountLabel("Deezer", host.storage.get("who"), host.storage.get("plan")))

    /** asks Deezer which account the cookie is for; the anonymous answer counts as signed out */
    override suspend fun verifyAccount(): AccountState {
        val arl = arl()
        if (arl.isNullOrBlank()) return AccountState.SignedOut
        // the plan is the useful part: Free answers user 0, which is the whole Deezer problem in one line
        val plan = Deezer.check(host, arl)
            ?: return AccountState.Expired(Deezer.lastReason.ifBlank { "Deezer refused the ARL" })
        host.storage.set("plan", plan)
        return AccountState.SignedIn(accountLabel("Deezer", host.storage.get("who"), plan))
    }

    override fun signOut() {
        listOf("arl", "who", "plan").forEach { host.storage.set(it, null) }
        Deezer.reset()
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val arl = arl()
        if (arl.isNullOrBlank()) {
            report(DiagnosticStep("Account", false, "No ARL on the module yet"))
            return ResolveResult.Unavailable("Not signed in")
        }

        val deezer = Source.Deezer(arl)
        val source = SourceRunner(host, deezer, host::folder)
        val found = source.find(track, picks["quality"] ?: "lossless") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return if (deezer.signedOut()) {
            ResolveResult.Unavailable(source.problem())
        } else {
            ResolveResult.NotFound(source.problem())
        }

        val stream = found.stream
        return ResolveResult.Found(
            SourceStream(
                url = stream.url.orEmpty(),
                format = stream.format,
                sourceLabel = "Deezer",
                qualityLabel = stream.label,
                codec = stream.codec,
                sampleRateHz = stream.sampleRate,
                matched = MatchedTrack(
                    id = found.matched.track.id,
                    title = found.matched.track.title,
                    artist = found.matched.track.artist,
                    album = found.matched.track.album,
                    durationMs = found.matched.track.durationMs,
                    confidence = found.matched.score,
                ),
            ),
        )
    }
}
