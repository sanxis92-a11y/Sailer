package com.sailer.browser.download

import com.sailer.browser.Lang
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import kotlin.math.ceil

/**
 * Lee manifiestos DASH (.mpd) sin DRM: calidades de vídeo y audio y la
 * lista de trozos de cada una (SegmentTemplate, SegmentTimeline,
 * SegmentList o archivo único).
 */
class DashManifest private constructor(
    val durationSec: Double,
    val representations: List<Representation>,
    val hasDrm: Boolean,
    val isLive: Boolean,
) {

    data class Representation(
        val id: String,
        val isAudio: Boolean,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val mimeType: String,
        val pieces: List<Piece>,
    ) {
        val extension: String
            get() = when {
                mimeType.contains("webm") -> if (isAudio) "weba" else "webm"
                isAudio -> "m4a"
                else -> "mp4"
            }
    }

    val videos get() = representations.filter { !it.isAudio }.sortedByDescending { it.bandwidth }
    val audios get() = representations.filter { it.isAudio }.sortedByDescending { it.bandwidth }

    /** Plan para una calidad de vídeo (o la mejor) más el mejor audio compatible. */
    fun plan(videoId: String?): Plan {
        if (hasDrm) throw HlsDownloader.DrmException()
        if (isLive) throw IOException(Lang.t("Las emisiones en directo no se pueden descargar", "Live streams can't be downloaded"))
        val video = videos.firstOrNull { it.id == videoId } ?: videos.firstOrNull()
        val sameFamily = audios.filter { (it.extension == "weba") == (video?.extension == "webm") }
        val audio = sameFamily.firstOrNull() ?: audios.firstOrNull()
        return when {
            video != null -> Plan(
                TrackPlan(video.pieces, video.extension),
                audio?.let { TrackPlan(it.pieces, it.extension) },
            )
            audio != null -> Plan(TrackPlan(audio.pieces, audio.extension))
            else -> throw IOException(Lang.t("El manifiesto no tiene vídeo ni audio", "The manifest has no video or audio"))
        }
    }

    companion object {

        private class Template(
            val media: String?,
            val initialization: String?,
            val startNumber: Long,
            val timescale: Long,
            val duration: Long,
            val timeline: List<Triple<Long, Long, Int>>?, // t, d, r
        )

        private class SegList(val init: String?, val media: List<String>)

        fun parse(manifestUrl: String, xml: String): DashManifest {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var duration = 0.0
            var live = false
            var drm = false
            val reps = mutableListOf<Representation>()

            var baseMpd = manifestUrl
            var basePeriod: String? = null
            var baseSet: String? = null
            var baseRep: String? = null
            var periodCount = 0
            var inFirstPeriod = false

            var setMime = ""
            var setIsAudio = false
            var setTemplate: Template? = null
            var setTemplateAttrs: Map<String, String>? = null

            var repAttrs: Map<String, String>? = null
            var repTemplate: Template? = null
            var repList: SegList? = null
            var listInit: String? = null
            var listMedia: MutableList<String>? = null
            var timeline: MutableList<Triple<Long, Long, Int>>? = null
            var templateAttrsPending: Map<String, String>? = null
            var depthOwner = "" // "set" o "rep" para la plantilla en curso
            var readingBase = ""

            fun attrs(): Map<String, String> =
                (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }

            fun currentBase(): String {
                var base = baseMpd
                for (b in listOf(basePeriod, baseSet, baseRep)) if (b != null) base = HlsPlaylist.resolve(base, b)
                return base
            }

            fun buildTemplate(a: Map<String, String>, tl: List<Triple<Long, Long, Int>>?, parent: Template?): Template =
                Template(
                    media = a["media"] ?: parent?.media,
                    initialization = a["initialization"] ?: parent?.initialization,
                    startNumber = a["startNumber"]?.toLongOrNull() ?: parent?.startNumber ?: 1,
                    timescale = a["timescale"]?.toLongOrNull() ?: parent?.timescale ?: 1,
                    duration = a["duration"]?.toLongOrNull() ?: parent?.duration ?: 0,
                    timeline = tl ?: parent?.timeline,
                )

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val name = parser.name?.substringAfter(':')
                when (event) {
                    XmlPullParser.START_TAG -> when (name) {
                        "MPD" -> {
                            val a = attrs()
                            live = a["type"] == "dynamic"
                            duration = parseIsoDuration(a["mediaPresentationDuration"])
                        }
                        "Period" -> {
                            periodCount++
                            inFirstPeriod = periodCount == 1
                            if (duration <= 0) duration = parseIsoDuration(attrs()["duration"])
                        }
                        "BaseURL" -> readingBase = when {
                            repAttrs != null -> "rep"
                            setTemplateAttrs != null || setMime.isNotEmpty() -> "set"
                            periodCount > 0 -> "period"
                            else -> "mpd"
                        }
                        "AdaptationSet" -> if (inFirstPeriod) {
                            val a = attrs()
                            setMime = a["mimeType"] ?: ""
                            setIsAudio = a["contentType"] == "audio" || setMime.startsWith("audio")
                            setTemplate = null
                            setTemplateAttrs = emptyMap()
                            baseSet = null
                        }
                        "ContentProtection" -> drm = true
                        "Representation" -> if (inFirstPeriod) {
                            repAttrs = attrs()
                            repTemplate = null
                            repList = null
                            baseRep = null
                        }
                        "SegmentTemplate" -> {
                            templateAttrsPending = attrs()
                            depthOwner = if (repAttrs != null) "rep" else "set"
                            timeline = null
                        }
                        "SegmentTimeline" -> timeline = mutableListOf()
                        "S" -> timeline?.let { list ->
                            val a = attrs()
                            val t = a["t"]?.toLongOrNull() ?: list.lastOrNull()?.let { it.first + it.second * (it.third + 1) } ?: 0
                            list.add(Triple(t, a["d"]?.toLongOrNull() ?: 0, a["r"]?.toIntOrNull() ?: 0))
                        }
                        "SegmentList" -> { listInit = null; listMedia = mutableListOf() }
                        "Initialization" -> if (listMedia != null) listInit = attrs()["sourceURL"]
                        "SegmentURL" -> attrs()["media"]?.let { listMedia?.add(it) }
                    }
                    XmlPullParser.TEXT -> if (readingBase.isNotEmpty()) {
                        val text = parser.text.trim()
                        when (readingBase) {
                            "mpd" -> baseMpd = HlsPlaylist.resolve(manifestUrl, text)
                            "period" -> basePeriod = text
                            "set" -> baseSet = text
                            "rep" -> baseRep = text
                        }
                    }
                    XmlPullParser.END_TAG -> when (name) {
                        "BaseURL" -> readingBase = ""
                        "SegmentTemplate" -> {
                            val a = templateAttrsPending ?: emptyMap()
                            if (depthOwner == "rep") repTemplate = buildTemplate(a, timeline, setTemplate)
                            else setTemplate = buildTemplate(a, timeline, null)
                            templateAttrsPending = null
                            timeline = null
                        }
                        "SegmentList" -> {
                            repList = SegList(listInit, listMedia.orEmpty())
                            listMedia = null
                        }
                        "Representation" -> {
                            val a = repAttrs
                            if (a != null && inFirstPeriod) {
                                val mime = a["mimeType"] ?: setMime
                                val isAudio = setIsAudio || mime.startsWith("audio")
                                val base = currentBase()
                                val hasOwnFile = baseRep != null || baseSet != null
                                val pieces = piecesFor(a, repTemplate ?: setTemplate, repList, base, duration, hasOwnFile)
                                if (pieces.isNotEmpty()) {
                                    reps.add(
                                        Representation(
                                            id = a["id"] ?: reps.size.toString(),
                                            isAudio = isAudio,
                                            bandwidth = a["bandwidth"]?.toLongOrNull() ?: 0,
                                            width = a["width"]?.toIntOrNull() ?: 0,
                                            height = a["height"]?.toIntOrNull() ?: 0,
                                            mimeType = mime,
                                            pieces = pieces,
                                        )
                                    )
                                }
                            }
                            repAttrs = null
                            baseRep = null
                        }
                        "AdaptationSet" -> {
                            setMime = ""
                            setIsAudio = false
                            setTemplate = null
                            setTemplateAttrs = null
                            baseSet = null
                        }
                        "Period" -> inFirstPeriod = false
                    }
                }
                event = parser.next()
            }
            return DashManifest(duration, reps, drm, live)
        }

        private fun piecesFor(
            rep: Map<String, String>,
            template: Template?,
            list: SegList?,
            base: String,
            totalDuration: Double,
            hasOwnFile: Boolean,
        ): List<Piece> {
            val id = rep["id"] ?: ""
            val bandwidth = rep["bandwidth"] ?: "0"
            fun fill(t: String, number: Long?, time: Long?): String =
                Regex("""\$(RepresentationID|Number|Bandwidth|Time)(%0(\d+)d)?\$""").replace(t.replace("$$", "\u0000")) { m ->
                    val width = m.groupValues[3].toIntOrNull() ?: 0
                    val value = when (m.groupValues[1]) {
                        "RepresentationID" -> return@replace id
                        "Number" -> number ?: 0
                        "Bandwidth" -> bandwidth.toLongOrNull() ?: 0
                        else -> time ?: 0
                    }
                    value.toString().padStart(width, '0')
                }.replace("\u0000", "$")

            if (template?.media != null) {
                val pieces = mutableListOf<Piece>()
                template.initialization?.let { pieces.add(Piece(HlsPlaylist.resolve(base, fill(it, null, null)))) }
                val timeline = template.timeline
                if (!timeline.isNullOrEmpty()) {
                    var number = template.startNumber
                    timeline.forEach { (start, d, r) ->
                        var time = start
                        repeat(r + 1) {
                            pieces.add(Piece(HlsPlaylist.resolve(base, fill(template.media, number, time))))
                            number++
                            time += d
                        }
                    }
                } else if (template.duration > 0 && totalDuration > 0) {
                    val count = ceil(totalDuration * template.timescale / template.duration).toLong()
                    for (i in 0 until count) {
                        val number = template.startNumber + i
                        pieces.add(Piece(HlsPlaylist.resolve(base, fill(template.media, number, i * template.duration))))
                    }
                }
                return pieces
            }
            if (list != null && list.media.isNotEmpty()) {
                return listOfNotNull(list.init?.let { Piece(HlsPlaylist.resolve(base, it)) }) +
                    list.media.map { Piece(HlsPlaylist.resolve(base, it)) }
            }
            // SegmentBase o archivo único: la propia BaseURL es el archivo entero.
            return if (hasOwnFile) listOf(Piece(base)) else emptyList()
        }

        /** Convierte "PT1H2M3.5S" en segundos. */
        fun parseIsoDuration(value: String?): Double {
            if (value.isNullOrEmpty()) return 0.0
            val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""").find(value) ?: return 0.0
            val (d, h, min, s) = m.destructured
            return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
                (min.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
        }
    }
}
