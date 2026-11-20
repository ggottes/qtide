package com.qtide

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackQuery
import java.io.File

/**
 * Tidal in Mochy, on your own account.
 *
 * The refresh token is typed on the module's page; the module signs in with it, finds the recording
 * by title and artist, and hands over a stream. CD is one FLAC link; hi-res is a DASH manifest that
 * goes over as a `data:` uri, so the player fetches its segments itself.
 */
class QtideTidal : SourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = "qtide-tidal",
        name = "Qtide — Tidal",
        version = "1.2",
        description = "Tidal: your own account, lossless and hi-res where it has it",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost
    private var runner: SourceRunner? = null

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val fields = listOf(
        ModuleField(
            id = "refreshToken",
            label = "Tidal refresh token",
            kind = FieldKind.Text,
            hint = "The refresh token of a Tidal session",
            description = "Kept on this phone only.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        host.storage.set("refreshToken", values["refreshToken"]?.trim()?.ifEmpty { null })
        runner = null
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("hires", "Hi-Res", "Up to 24-bit, where Tidal has a hi-res master"),
                StreamOption("lossless", "Lossless", "CD quality FLAC, 16-bit 44.1 kHz"),
                StreamOption("high", "High", "AAC 320, for a slow connection"),
            ),
        ),
    )

    private fun token() = host.storage.get("refreshToken")

    override fun accountState(): AccountState =
        if (token().isNullOrBlank()) AccountState.SignedOut else AccountState.SignedIn("Tidal")

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit,
    ): ResolveResult {
        val token = token()
        if (token.isNullOrBlank()) {
            report(DiagnosticStep("Account", false, "No refresh token on the module yet"))
            return ResolveResult.Unavailable("Not signed in")
        }

        val source = SourceRunner(host, Source.Tidal(token), host::folder)
        val found = source.find(track, picks["quality"] ?: "lossless") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return ResolveResult.NotFound(source.problem())

        val stream = found.stream
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
