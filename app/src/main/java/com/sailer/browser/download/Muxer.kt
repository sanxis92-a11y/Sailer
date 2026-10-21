package com.sailer.browser.download

import com.sailer.browser.Lang
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.sailer.browser.vault.EncryptedMediaSource
import com.sailer.browser.vault.VaultCrypto
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Une una pista de vídeo y otra de audio (cifradas) en un solo archivo MP4 o WebM. */
object Muxer {

    fun mux(crypto: VaultCrypto, video: File, audio: File, output: File, webm: Boolean) {
        val videoSource = EncryptedMediaSource(crypto, video)
        val audioSource = EncryptedMediaSource(crypto, audio)
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoEx.setDataSource(videoSource)
            audioEx.setDataSource(audioSource)
            val videoTrack = findTrack(videoEx, "video/") ?: throw IOException(Lang.t("No hay pista de vídeo", "No video track"))
            val audioTrack = findTrack(audioEx, "audio/") ?: throw IOException(Lang.t("No hay pista de audio", "No audio track"))
            videoEx.selectTrack(videoTrack)
            audioEx.selectTrack(audioTrack)
            val videoFormat = videoEx.getTrackFormat(videoTrack)
            val audioFormat = audioEx.getTrackFormat(audioTrack)

            val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val m = MediaMuxer(output.path, format)
            muxer = m
            val outVideo = m.addTrack(videoFormat)
            val outAudio = m.addTrack(audioFormat)
            m.start()

            val bufferSize = maxOf(maxInputSize(videoFormat), maxInputSize(audioFormat), 4 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            while (!videoDone || !audioDone) {
                // Intercalar por tiempo para que el archivo se reproduzca bien.
                val useVideo = !videoDone && (audioDone || videoEx.sampleTime <= audioEx.sampleTime)
                val extractor = if (useVideo) videoEx else audioEx
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, extractor.sampleTime, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(if (useVideo) outVideo else outAudio, buffer, info)
                extractor.advance()
            }
            m.stop()
        } finally {
            runCatching { muxer?.release() }
            videoEx.release()
            audioEx.release()
            videoSource.close()
            audioSource.close()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        }

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
