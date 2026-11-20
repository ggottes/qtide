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

/**
 * Qobuz in Mochy, on your own account.
 *
 * A user token is all it needs: the app id and secret Qobuz's own web player signs with are read
 * from its script, or typed in when someone has their own pair.
 */
class QtideQobuz : SourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = "qtide-qobuz",
        name = "Qtide — Qobuz",
        version = "1.2",
        description = "Qobuz: your own account, FLAC up to 24-bit on a Studio plan",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val fields = listOf(
        ModuleField(
            id = "userToken",
            label = "Qobuz user token",
            kind = FieldKind.Text,
            hint = "The X-User-Auth-Token header of the Qobuz web player",
            description = "Kept on this phone only.",
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
        host.storage.set("userToken", values["userToken"]?.trim()?.ifEmpty { null })
        host.storage.set("appPair", values["appPair"]?.trim()?.ifEmpty { null })
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("hires", "Hi-Res", "Up to 24-bit, on a Studio plan"),
                StreamOption("lossless", "Lossless", "CD quality FLAC, 16-bit 44.1 kHz"),
            ),
        ),
    )

    private fun token() = host.storage.get("userToken")

    override fun accountState(): AccountState =
        if (token().isNullOrBlank()) AccountState.SignedOut else AccountState.SignedIn("Qobuz")

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
