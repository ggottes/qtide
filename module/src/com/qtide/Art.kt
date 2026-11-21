package com.qtide

/**
 * Where mochy finds each module's pictures.
 *
 * A code module can't hand one over as a file: mochy asks for `icon`/`banner` once, in
 * `BridgedModule.open`, *before* `attach` — so there is no `host.folder` to point at yet, and a class
 * that runs inside mochy's process has no code source to read its own apk from. A **link** needs
 * neither: the app loads it with Coil, which caches it after the first fetch. The pictures live in
 * this repo, served through jsDelivr so a phone in Turkey doesn't depend on GitHub's raw host.
 */
internal object Art {

    private const val BASE = "https://cdn.jsdelivr.net/gh/ggottes/qtide@main/assets"

    /** the wide banner over every Qtide page: the wordmark, right of centre */
    const val BANNER = "$BASE/banner/banner.png"

    /** a square icon with the wordmark over the service's own mark */
    fun icon(service: String): String = "$BASE/icons/icon-$service.png"
}
