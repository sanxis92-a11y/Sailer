package com.sailer.browser.diag

import android.content.Context
import android.os.Build
import com.sailer.browser.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Guarda el error que cerró la app (solo en el móvil, nunca se envía) para
 * poder mostrarlo la próxima vez y que el usuario lo copie si quiere.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, FILE).writeText(
                    "Nauvel ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) · Android ${Build.VERSION.RELEASE} · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n" + trace.take(20_000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Devuelve el último informe (y lo borra), o null si no hubo ningún cierre. */
    fun takeLast(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}
