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
        version = "1.4",
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

    // the page mochy opens is the one with the code in the address, like tiddl prints: pressing Sign
    // in before the code exists lands on a bare link.tidal.com that asks for one
    override val signIn get() = SignInSpec(
        // the helper page makes the code and does the whole device login; it ends with the refresh
        // token in a copy box, which is pasted into the field. mochy's sheet shows a URL it can load,
        // so a page is what the sign-in pill needs — the module never gets to run code first.
        startUrl = Tidal.helperPage(),
        // the helper page and Tidal's own pages: a hop outside these closes the sheet
        origins = listOf(
            "https://ggottes.github.io",
            "https://link.tidal.com",
            "https://tidal.com",
            "https://login.tidal.com",
        ),
        sessionOrigins = setOf("https://ggottes.github.io"),
        desktopSite = false,
        title = "Sign in to Tidal",
        subtitle = "Approve the code, copy the key it gives you, paste it on the module's page",
    )

    override val fields: List<ModuleField>
        get() = listOf(
            ModuleField(
                id = "refreshToken",
                label = "Tidal refresh token",
                kind = FieldKind.Text,
                hint = "Optional: a session's refresh token",
                description = "Press Sign in: that page gets a Tidal code, and copies the key into " +
                    "this field when you approve it. A refresh token from tiddl works here too.",
            ),
        )

    override fun onFields(values: Map<String, String>) {
        // a blank box means "nothing typed", not "forget what the browser found"
        values["refreshToken"]?.trim()?.takeIf { it.isNotEmpty() }?.let { host.storage.set("refreshToken", it) }
        // the switch is a request for a fresh code, and the module hears it on its own thread
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
    /**
     * The helper page reports the refresh token as `authorization: Bearer …` to its own address, and
     * that is one of the few headers Mochy's sign-in browser forwards to a module. Taking it here is
     * what closes the sheet by itself and fills the field: no copying, no second press.
     */
    override fun onSignInHeader(name: String, value: String, url: String): Boolean {
        if (!name.equals("authorization", ignoreCase = true)) return false
        val token = value.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (token.length < 40) return false
        host.storage.set("refreshToken", token)
        host.storage.set("deviceUserCode", null)
        Tidal.forget(host)
        runner = null
        host.log("Tidal: signed in from the helper page")
        return true
    }

    override suspend fun verifyAccount(): AccountState {
        Tidal.pollDevice(host)?.let { host.storage.set("refreshToken", it) }
        val token = token()
        if (token.isNullOrBlank()) {
            Tidal.beginDevice(host) ?: return AccountState.Expired("Tidal wouldn't hand out a code")
            return AccountState.SignedOut
        }
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
        val signedOut = token().isNullOrBlank()
        if (signedOut) {
            // the test row is the one call a signed-out page can make, and it runs off the main
            // thread, so this is where the device code is asked for
            val code = Tidal.beginDevice(host)
            report(
                DiagnosticStep(
                    "Sign in", code != null,
                    if (code != null) "Enter code $code at link.tidal.com, then press Test again"
                    else "Tidal wouldn't hand out a sign-in code",
                ),
            )
            if (code != null) return ResolveResult.Unavailable("Waiting for the Tidal sign-in")
        }
        val tidal = Source.Tidal(token().orEmpty()) { host.storage.set("refreshToken", it) }
        val source = SourceRunner(host, tidal, host::folder)
        val found = source.find(track, picks["quality"] ?: "hires") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return if (tidal.signedOut()) {
            ResolveResult.Unavailable(source.problem())
        } else {
            ResolveResult.NotFound(source.problem())
        }

        val stream = found.stream
        val picked = picks["quality"] ?: "hires"
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
