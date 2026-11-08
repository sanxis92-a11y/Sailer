package com.sailer.browser.vault

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.sailer.browser.R
import com.sailer.browser.databinding.ActivityViewerBinding
import com.sailer.browser.lock.SecureActivity
import com.sailer.browser.media.MediaKind
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Visor de la carpeta segura: reproduce vídeo/audio y muestra imágenes sin descifrarlos a disco. */
class ViewerActivity : SecureActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val vault = Vault.get(this)
        val entry = intent.getStringExtra(EXTRA_ID)?.let { vault.find(it) }
        if (entry == null) {
            finish()
            return
        }
        if (entry.kind == MediaKind.IMAGE) showImage(vault, entry) else play(vault, entry)
    }

    @OptIn(UnstableApi::class)
    private fun play(vault: Vault, entry: Vault.Entry) {
        binding.playerView.visibility = View.VISIBLE
        val factory = DataSource.Factory { EncryptedDataSource(vault.crypto) }
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(vault.fileOf(entry))))
        player = ExoPlayer.Builder(this).build().also {
            binding.playerView.player = it
            it.setMediaSource(source)
            it.prepare()
            it.playWhenReady = true
        }
    }

    private fun showImage(vault: Vault, entry: Vault.Entry) {
        binding.imageView.visibility = View.VISIBLE
        thread {
            val drawable: Drawable? = runCatching {
                val bytes = vault.openInput(entry).use { it.readBytes() }
                // Las fotos enormes se reducen: dibujarlas a tamaño real cierra la app.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (drawable == null) {
                    Toast.makeText(this, R.string.viewer_error, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    binding.imageView.setImageDrawable(drawable)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
                        drawable.start()
                    }
                }
            }
        }
    }

    private fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_IMAGE_SIDE) sample *= 2
        return sample
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_IMAGE_SIDE = 4096
    }
}
