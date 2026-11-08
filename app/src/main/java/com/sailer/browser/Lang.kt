package com.sailer.browser

import java.util.Locale

/**
 * Idioma de la app: español si el móvil está en español, si no inglés.
 * Para los textos de la interfaz se usan los recursos (values/ y values-es/);
 * esto es solo para mensajes de error que se crean lejos de una pantalla
 * y para elegir los archivos de assets traducidos.
 */
object Lang {

    val isSpanish: Boolean get() = Locale.getDefault().language == "es"

    fun t(es: String, en: String): String = if (isSpanish) es else en

    /** "privacy.txt" → "privacy-en.txt" cuando el móvil no está en español. */
    fun asset(name: String): String =
        if (isSpanish) name else name.substringBeforeLast('.') + "-en." + name.substringAfterLast('.')
}
