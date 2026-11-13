package com.sailer.browser.vault

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.ExifInterface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.format.Formatter
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sailer.browser.R
import com.sailer.browser.lock.showSecure
import java.io.ByteArrayInputStream
import java.util.Date
import kotlin.concurrent.thread

/**
 * «ⓘ Datos»: enseña los metadatos de una foto de la carpeta segura (EXIF,
 * ubicación GPS, XMP y textos de PNG) sin cambiar nada.
 */
object MetaViewer {

    /** Etiquetas EXIF importantes, con su nombre legible, en este orden. */
    private val MAIN_TAGS: List<Pair<String, Int>> = listOf(
        "DateTimeOriginal" to R.string.meta_taken,
        "DateTime" to R.string.meta_modified,
        "Make" to R.string.meta_make,
        "Model" to R.string.meta_model,
        "LensModel" to R.string.meta_lens,
        "Software" to R.string.meta_software,
        "Artist" to R.string.meta_artist,
        "Copyright" to R.string.meta_copyright,
        "ImageDescription" to R.string.meta_description,
        "UserComment" to R.string.meta_comment,
        "CameraOwnerName" to R.string.meta_owner,
        "BodySerialNumber" to R.string.meta_serial,
    )

    fun show(activity: Activity, vault: Vault, entry: Vault.Entry) {
        thread {
            val text = runCatching { describe(activity, vault, entry) }.getOrElse { activity.getString(R.string.meta_error) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val view = TextView(activity).apply {
                    setText(text)
                    setTextIsSelectable(true)
                    setTextColor(ContextCompat.getColor(activity, R.color.text))
                    textSize = 14f
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, 0)
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.meta_title)
                    .setView(ScrollView(activity).apply { addView(view) })
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.meta_copy) { _, _ ->
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(entry.name, text.toString()))
                        Toast.makeText(activity, R.string.meta_copied, Toast.LENGTH_SHORT).show()
                    }
                    .showSecure()
            }
        }
    }

    private fun describe(context: Context, vault: Vault, entry: Vault.Entry): CharSequence {
        val bytes = vault.openInput(entry).use { it.readBytes() }
        val out = SpannableStringBuilder()
        fun title(res: Int) {
            if (out.isNotEmpty()) out.append("\n")
            val start = out.length
            out.append(context.getString(res)).append("\n")
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun line(label: String, value: String) {
            val start = out.length
            out.append(label).append(": ")
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(value).append("\n")
        }

        // Archivo
        title(R.string.meta_section_file)
        line(context.getString(R.string.meta_name), entry.name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0) line(context.getString(R.string.meta_resolution), "${bounds.outWidth} × ${bounds.outHeight}")
        line(context.getString(R.string.meta_size), Formatter.formatFileSize(context, entry.size))
        entry.mime?.let { line(context.getString(R.string.meta_type), it) }
        line(context.getString(R.string.meta_saved), DateFormat.getMediumDateFormat(context).format(Date(entry.created)) + " " +
            DateFormat.getTimeFormat(context).format(Date(entry.created)))
        if (entry.source.isNotEmpty()) line(context.getString(R.string.meta_source), entry.source)

        // EXIF
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        var anything = false
        if (exif != null) {
            val latLong = FloatArray(2)
            @Suppress("DEPRECATION")
            if (exif.getLatLong(latLong)) {
                title(R.string.meta_section_location)
                line(context.getString(R.string.meta_coordinates), "%.6f, %.6f".format(java.util.Locale.US, latLong[0], latLong[1]))
                exif.getAttribute("GPSAltitude")?.let { line(context.getString(R.string.meta_altitude), it) }
                exif.getAttribute("GPSDateStamp")?.let { line(context.getString(R.string.meta_gps_date), it) }
                anything = true
            }
            val main = MAIN_TAGS.mapNotNull { (tag, res) -> exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { res to it } }
            if (main.isNotEmpty()) {
                title(R.string.meta_section_camera)
                main.forEach { (res, value) -> line(context.getString(res), value) }
                anything = true
            }
            val shown = MAIN_TAGS.map { it.first }.toSet()
            val all = ExifInterface::class.java.fields
                .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
                .mapNotNull { runCatching { it.get(null) as String }.getOrNull() }
                .distinct()
                .filter { it !in shown }
                .mapNotNull { tag -> exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }?.let { tag to it.take(300) } }
                .sortedBy { it.first }
            if (all.isNotEmpty()) {
                title(R.string.meta_section_exif)
                all.forEach { (tag, value) -> line(tag, value) }
                anything = true
            }
        }

        // XMP
        MetaStrip.readXmp(bytes)?.let { xmp ->
            val fields = MetaStrip.xmpFields(xmp)
            if (fields.isNotEmpty()) {
                title(R.string.meta_section_xmp)
                fields.forEach { (k, v) -> line(k, v.take(500)) }
                anything = true
            }
        }

        // Textos de PNG
        val texts = MetaStrip.pngTexts(bytes)
        if (texts.isNotEmpty()) {
            title(R.string.meta_section_png)
            texts.forEach { (k, v) -> line(k, v.take(500)) }
            anything = true
        }

        if (!anything) {
            out.append("\n").append(context.getString(R.string.meta_nothing)).append("\n")
        }
        return out
    }
}
