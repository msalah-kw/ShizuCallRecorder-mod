package com.kitsumed.shizucallrecorder.utils

import android.content.Context
import java.io.File
import java.io.IOException

/** Bounded persistent files; each append and export is serialized on the same monitor. */
class DiagnosticFiles(val directory: File) {
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        fun directory(context: Context) = File(context.noBackupFilesDir, "diagnostics")
    }

    @Synchronized
    fun append(name: String, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val current = File(directory, name)
        val bytes = text.take(128 * 1024).toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > MAX_BYTES) rotate(name)
        current.appendBytes(bytes)
    }

    private fun rotate(name: String) {
        val oldest = File(directory, "$name.2")
        if (oldest.exists() && !oldest.delete()) throw IOException("Cannot remove $oldest")
        for (index in 1 downTo 0) {
            val source = File(directory, if (index == 0) name else "$name.1")
            if (source.exists() && !source.renameTo(File(directory, "$name.${index + 1}"))) {
                throw IOException("Cannot rotate $source")
            }
        }
    }

    @Synchronized
    fun writeTo(output: java.io.OutputStream) {
        for (name in listOf("app.log", "shizuku.log")) {
            for (suffix in listOf(".2", ".1", "")) {
                val file = File(directory, name + suffix)
                if (file.exists()) {
                    output.write("\n=== ${file.name} ===\n".toByteArray())
                    file.inputStream().use { it.copyTo(output) }
                }
            }
        }
    }
}
