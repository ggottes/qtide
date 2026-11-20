package com.qtide

import java.io.File
import java.util.zip.ZipFile

/**
 * A module's own pictures, put where mochy reads them from.
 *
 * Two things about a zip module make this less obvious than it looks:
 *
 * 1. **When.** mochy builds its one description of a module (its `about` json, which is where
 *    `icon`/`banner` come from) in `BridgedModule.open`, which runs *before* `attach`, and it never
 *    asks again. So the path has to be known before the host exists: it is worked out from where the
 *    code was loaded from, which mochy settles at load time.
 * 2. **Where.** A zip module is copied to `code_modules/<id>/<time>/module.apk`, and `host.folder`
 *    is a different folder (`module_data/<id>`). Neither is the other, so the picture is read out of
 *    the apk the class was loaded from and written next to it, which needs no host at all.
 *
 * The answer is an absolute path with a leading "/": mochy turns that into a `file://` uri and reads
 * it with Coil, whereas anything without the slash is taken as a link.
 */
internal class Art {

    private val done = mutableMapOf<String, String?>()

    /** the path of [name] in the module's own folder, copied from the apk on first ask */
    fun of(name: String): String? = done.getOrPut(name) { copy(name) }

    private fun copy(name: String): String? = runCatching {
        val apk = runCatching { javaClass.protectionDomain?.codeSource?.location?.toURI() }
            .getOrNull()?.let { File(it) }?.takeIf { it.isFile }
            // a device's DexClassLoader may say the folder the apk was loaded from, not the apk
            ?: runCatching { javaClass.protectionDomain?.codeSource?.location?.toURI() }
                .getOrNull()?.let { File(it) }?.let { if (it.isDirectory) File(it, "module.apk") else null }
                ?.takeIf { it.isFile }
            ?: return@runCatching null
        val folder = apk.parentFile ?: return@runCatching null
        val out = File(folder, name)
        if (!out.isFile || out.length() == 0L) {
            val bytes = ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/$name") ?: return@runCatching null
                zip.getInputStream(entry)?.use { it.readBytes() }
            } ?: return@runCatching null
            val temp = File(folder, "$name.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(out)) {
                out.writeBytes(bytes)
                temp.delete()
            }
        }
        if (!out.isFile) return@runCatching null
        "/" + out.absolutePath.replace('\\', '/')
    }.getOrNull()
}
