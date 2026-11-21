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
 * Qobuz in Mochy, on your own account.
 *
 * Signing in happens on play.qobuz.com in Mochy's own browser, which hands the module the same
 * `X-User-Auth-Token` header the web player sends; pasting that token works too. The app id and
 * secret Qobuz's own player signs with are read from its script.
 *
 * Hi-Res asks for 24/192, then 24/96, then CD, and the label names the bit depth Qobuz actually
 * gave, so a 16/44.1 master under the Hi-Res chip reads as 16-bit rather than pretending.
 */
class QtideQobuz : SourceModule, ModuleFields, ModuleArt {

    override val info = ModuleInfo(
        id = "qtide-qobuz",
        name = "Qtide — Qobuz",
        version = "1.3",
        description = "Qobuz: your own account, FLAC up to 24-bit on a Studio plan",
        sourceUrl = "https://github.com/ggottes/qtide",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    // mochy only shows icon/banner it reads from a manifest; a code module's own ModuleArt values are
    // read before attach and this class has no code source on a phone, so they are left out on purpose
    override val icon: String? get() = null
    override val banner: String? get() = null

    // the web player refuses to render under 1024px wide, so the sign-in page has to be the desktop one
    override val signIn = SignInSpec(
        startUrl = "https://play.qobuz.com/login",
        origins = listOf(
            "https://play.qobuz.com",
            "https://www.qobuz.com",
            "https://accounts.qobuz.com",
            "https://www.google.com",
            "https://accounts.google.com",
            "https://www.facebook.com",
        ),
        sessionOrigins = setOf("https://www.qobuz.com"),
        desktopSite = true,
        title = "Sign in to Qobuz",
        subtitle = "Your Studio account plays in Mochy",
    )

    override val fields = listOf(
        ModuleField(
            id = "userToken",
            label = "Qobuz user token",
            kind = FieldKind.Text,
            hint = "The X-User-Auth-Token header of the Qobuz web player",
            description = "Sign in above and this fills itself, or paste the token here.",
        ),
        ModuleField(
            id = "appPair",
            label = "Qobuz app id and secret (optional)",
            kind = FieldKind.Text,
            hint = "appId:secret",
            description = "Left empty, they are read from Qobuz's own web player.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        values["userToken"]?.trim()?.takeIf { it.isNotEmpty() }?.let { host.storage.set("userToken", it) }
        values["appPair"]?.trim()?.takeIf { it.isNotEmpty() }?.let { host.storage.set("appPair", it) }
    }

    /** the browser's own header: the same slot the field writes, so the page shows what it found */
    override fun onSignInHeader(name: String, value: String, url: String): Boolean =
        SignIn.qobuz(host, name, value)

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("hires", "Hi-Res", "Up to 24-bit on a Studio plan; CD where the track has no master"),
                StreamOption("lossless", "Lossless", "CD quality FLAC, 16-bit 44.1 kHz"),
            ),
        ),
    )

    private fun token() = host.storage.get("userToken")

    override fun accountState(): AccountState = when {
        token().isNullOrBlank() -> AccountState.SignedOut
        // whoever and whatever user/get named last, until the next check
        else -> AccountState.SignedIn(accountLabel("Qobuz", who(), plan(), country()))
    }

    private fun who() = host.storage.get("who")
    private fun plan() = host.storage.get("plan")
    private fun country() = host.storage.get("country")

    override suspend fun verifyAccount(): AccountState {
        val token = token()
        if (token.isNullOrBlank()) return AccountState.SignedOut
        // a fresh session, so a plan that changed since the last check shows
        signedIn = null
        val session = session()
            ?: return AccountState.Expired("Qobuz refused the saved sign-in")
        return AccountState.SignedIn(accountLabel("Qobuz", session.who, session.plan, session.country))
    }

    private var signedIn: Qobuz.Session? = null

    private fun session(): Qobuz.Session? {
        signedIn?.let { return it }
        val token = token() ?: return null
        val pair = host.storage.get("appPair").orEmpty().split(":", limit = 2)
        return Qobuz.login(host, token, pair.getOrNull(0).orEmpty(), pair.getOrNull(1).orEmpty())
            ?.also {
                signedIn = it
                host.storage.set("who", it.who)
                host.storage.set("plan", it.plan)
                host.storage.set("country", it.country)
            }
    }

    override fun signOut() {
        listOf("userToken", "appPair", "who", "plan", "country").forEach { host.storage.set(it, null) }
        signedIn = null
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val token = token()
        if (token.isNullOrBlank()) {
            report(DiagnosticStep("Account", false, "No user token on the module yet"))
            return ResolveResult.Unavailable("Not signed in")
        }

        val source = SourceRunner(
            host,
            Source.Qobuz(token, host.storage.get("appPair").orEmpty()),
            host::folder,
        )
        val found = source.find(track, picks["quality"] ?: "lossless") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return ResolveResult.NotFound(source.problem())

        val stream = found.stream
        return ResolveResult.Found(
            SourceStream(
                url = stream.url.orEmpty(),
                format = stream.format,
                sourceLabel = "Qobuz",
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
