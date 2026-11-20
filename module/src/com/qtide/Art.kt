package com.qtide

import com.mochyy.mochymusic.module.ModuleHost
import java.io.File
import java.util.zip.ZipFile

/**
 * A module's own pictures, put where mochy reads them from.
 *
 * A code module can't hand the app a zip path: an absolute path, answered with a leading "/", is
 * what mochy turns into a `file://` uri. So the pictures are copied out of the module's own apk
 * (`assets/`) into the module's folder once, and that path is what [icon] and [banner] answer with.
 *
 * The apk is found through the class's own code source — a code module is loaded from it, so it is
 * always the file the pictures are in — and read as an ordinary zip. A picture that can't be written
 * just leaves the module with mochy's own art.
 */
internal class Art(private val host: ModuleHost) {

    private val done = mutableMapOf<String, String?>()

    /** the path of [name] in the module's folder, copied from the apk on first ask */
    fun of(name: String): String? = done.getOrPut(name) { copy(name) }

    private fun copy(name: String): String? = runCatching {
        val folder = host.folder.apply { mkdirs() }
        val out = File(folder, name)
        if (!out.isFile || out.length() == 0L) {
            val bytes = fromApk(name) ?: return@runCatching null
            val temp = File(folder, "$name.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(out)) {
                out.writeBytes(bytes)
                temp.delete()
            }
        }
        // the leading slash is how mochy knows this is a path and not a link
        "/" + out.absolutePath.replace('\\', '/')
    }.getOrNull()

    private fun fromApk(name: String): ByteArray? {
        val apk = runCatching { javaClass.protectionDomain?.codeSource?.location?.toURI() }.getOrNull()
            ?: return null
        val file = runCatching { File(apk) }.getOrNull()?.takeIf { it.isFile } ?: return null
        return ZipFile(file).use { zip ->
            val entry = zip.getEntry("assets/$name") ?: return null
            zip.getInputStream(entry)?.use { it.readBytes() }
        }
    }
}
