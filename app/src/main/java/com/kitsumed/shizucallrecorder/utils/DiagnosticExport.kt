package com.kitsumed.shizucallrecorder.utils

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExport {
    const val DOWNLOAD_PATH = "Download/ShizuCallRecorder/Logs"

    fun save(context: Context): String {
        val name = "diagnostic-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.log"
        val resolver = context.contentResolver
        val metadata = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata)
            ?: throw IOException("Cannot create report in Downloads")
        try {
            AppLogger.exportReport(context, uri)
            metadata.clear()
            metadata.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, metadata, null, null) != 1) throw IOException("Cannot publish report")
            return "$DOWNLOAD_PATH/$name"
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
