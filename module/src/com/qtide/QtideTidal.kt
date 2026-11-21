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
 * Tidal in Mochy, on your own account.
 *
 * Sign in opens link.tidal.com in Mochy's own browser; the first play asks Tidal for a device code
 * and shows it in the step line, and the play after the approval finishes the sign-in. A refresh
 * token can also be typed instead.
 *
 * The module finds the recording by title and artist and hands over a stream. CD is one FLAC link;
 * hi-res is a DASH manifest that goes over as a `data:` uri, so the player fetches its segments
 * itself. A chip is a ceiling: Hi-Res falls to CD and High falls to AAC 96 when Tidal has no master
 * for the track, and the label always names what actually answered.
 */
class QtideTidal : SourceModule, ModuleFields, ModuleArt {

    override val info = ModuleInfo(
        id = "qtide-tidal",
        name = "Qtide — Tidal",
        version = "1.3",
        description = "Tidal: your own account, lossless and hi-res where it has it",
        sourceUrl = "https://github.com/ggottes/qtide",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost
    private var runner: SourceRunner? = null
    override fun attach(host: ModuleHost) {
        this.host = host
    }

    // a link, not a path: there is no host yet when mochy asks, and no code source to read the apk from
    override val icon: String? get() = Art.icon("tidal")
    override val banner: String? get() = Art.BANNER

    override val signIn = SignInSpec(
        startUrl = "https://link.tidal.com/",
        origins = listOf("https://link.tidal.com", "https://tidal.com", "https://login.tidal.com"),
        sessionOrigins = setOf("https://link.tidal.com"),
        desktopSite = false,
        title = "Sign in to Tidal",
        subtitle = "Log in, then approve the code Qtide shows when you press play",
    )

    override val fields = listOf(
        ModuleField(
            id = "refreshToken",
            label = "Tidal refresh token",
            kind = FieldKind.Text,
            hint = "Optional: a session's refresh token",
            description = "Sign in above, or paste a refresh token here.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        // a blank box means "nothing typed", not "forget what the browser found"
        values["refreshToken"]?.trim()?.takeIf { it.isNotEmpty() }?.let { host.storage.set("refreshToken", it) }
        runner = null
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("hires", "Hi-Res", "Up to 24-bit where Tidal has a master; CD where it doesn't"),
                StreamOption("lossless", "Lossless", "CD quality FLAC, 16-bit 44.1 kHz"),
                StreamOption("high", "High", "AAC 320, for a slow connection"),
            ),
        ),
    )

    private fun token() = host.storage.get("refreshToken")
    private fun stored(key: String) = host.storage.get(key)

    /** what the last stream really was, in the module's own words, for the player's stream info */
    @Volatile private var lastLabel: String? = null

    override fun accountState(): AccountState = when {
        token().isNullOrBlank() -> AccountState.SignedOut
        // the country is known once the module has signed in; the last played tier until then
        else -> AccountState.SignedIn(accountLabel("Tidal", stored("country"), lastLabel))
    }

    /** the one line that says what a play was: the chip picked and the tier that actually answered */
    private fun describe(picked: String, label: String): String {
        host.log("Tidal: chip=$picked stream=$label")
        return label
    }

    /**
     * Finishes a device login the user approved at link.tidal.com: one poll stores the refresh
     * token, which is the same slot the field writes.
     */
    override suspend fun verifyAccount(): AccountState {
        Tidal.pollDevice(host)?.let { host.storage.set("refreshToken", it) }
        val token = token()
        if (token.isNullOrBlank()) return AccountState.SignedOut
        val session = Tidal.login(host, token)
            ?: return AccountState.Expired("Tidal refused the saved sign-in")
        host.storage.set("country", session.country)
        return AccountState.SignedIn(accountLabel("Tidal", session.country, stored("account")))
    }

    override fun signOut() {
        host.storage.set("refreshToken", null)
        Tidal.forget(host)
        runner = null
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val tidal = Source.Tidal(token().orEmpty()) { host.storage.set("refreshToken", it) }
        val source = SourceRunner(host, tidal, host::folder)
        val found = source.find(track, picks["quality"] ?: "lossless") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return if (tidal.signedOut()) {
            ResolveResult.Unavailable(source.problem())
        } else {
            ResolveResult.NotFound(source.problem())
        }

        val stream = found.stream
        val picked = picks["quality"] ?: "lossless"
        lastLabel = describe(picked, stream.label)
        return ResolveResult.Found(
            SourceStream(
                url = stream.url.orEmpty(),
                format = stream.format,
                sourceLabel = "Tidal",
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
