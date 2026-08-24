package com.simpletuner.juken

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menangkap semua crash (uncaught exception) dan simpan stack trace lengkap
 * ke file lokal, supaya bisa dilihat lagi setelah aplikasi di-restart —
 * tanpa perlu USB debugging/adb/PC. Dilihat dari tab More > "Lihat Log Crash".
 */
class JukenApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "=== CRASH $timestamp ===\nThread: ${thread.name}\n$sw\n"
                File(filesDir, CRASH_LOG_FILE).appendText(text)
            } catch (_: Exception) {
                // kalau logging pun gagal, tetap lanjut ke default handler di bawah
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG_FILE = "crash_log.txt"

        fun readCrashLog(app: Application): String {
            val f = File(app.filesDir, CRASH_LOG_FILE)
            return if (f.exists()) f.readText() else "Belum ada crash tercatat."
        }

        fun clearCrashLog(app: Application) {
            File(app.filesDir, CRASH_LOG_FILE).delete()
        }
    }
}
