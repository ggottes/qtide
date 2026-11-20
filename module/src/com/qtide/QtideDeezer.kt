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
 * Deezer in Mochy, on your own account.
 *
 * The `arl` cookie is the sign-in. Deezer answers with a file whose every third 2048-byte slice is
 * Blowfish-encrypted, so the module decrypts it and saves one plain flac/mp3 in its own folder; a
 * free account only gets the 30-second preview, which is played as it comes.
 */
class QtideDeezer : SourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = "qtide-deezer",
        name = "Qtide — Deezer",
        version = "1.2",
        description = "Deezer: your own account, FLAC on a HiFi plan",
        author = "geisselgottes",
    )

    private lateinit var host: ModuleHost

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override val fields = listOf(
        ModuleField(
            id = "arl",
            label = "Deezer ARL",
            kind = FieldKind.Text,
            hint = "The arl cookie of a signed-in deezer.com",
            description = "Kept on this phone only.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        host.storage.set("arl", values["arl"]?.trim()?.ifEmpty { null })
    }

    override val choices = listOf(
        ModuleChoice(
            id = "quality",
            label = "Stream",
            options = listOf(
                StreamOption("lossless", "Lossless", "FLAC, on a HiFi plan"),
                StreamOption("high", "High", "MP3 320"),
                StreamOption("low", "Data saver", "MP3 128"),
            ),
        ),
    )

    private fun arl() = host.storage.get("arl")

    override fun accountState(): AccountState =
        if (arl().isNullOrBlank()) AccountState.SignedOut else AccountState.SignedIn("Deezer")

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

        val source = SourceRunner(host, Source.Deezer(arl), host::folder)
        val found = source.find(track, picks["quality"] ?: "lossless") { step ->
            report(DiagnosticStep(step.name, step.ok, step.detail))
        } ?: return ResolveResult.NotFound(source.problem())

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
