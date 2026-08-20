package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

sealed interface LoadedImage {
    val width: Int
    val height: Int

    data class Static(val bitmap: ImageBitmap) : LoadedImage {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    data class Animated(val animation: GifAnimation) : LoadedImage {
        override val width: Int get() = animation.width
        override val height: Int get() = animation.height
    }
}

object ImageLoader {
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val semaphore = Semaphore(4)

    private val thumbnailCache =
        object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > 200
        }

    fun invalidateCache() {
        synchronized(thumbnailCache) { thumbnailCache.clear() }
    }

    suspend fun loadThumbnail(source: ImageSource, targetDim: Int): ImageBitmap? = semaphore.withPermit {
        val key = "${source.cacheKey}?$targetDim"
        synchronized(thumbnailCache) { thumbnailCache[key] }?.let { return@withPermit it }
        withContext(decodeDispatcher) {
            runCatching { decodeThumbnail(source.openBytes(), targetDim) }
                .getOrNull()
                ?.also { synchronized(thumbnailCache) { thumbnailCache[key] = it } }
        }
    }

    suspend fun loadFull(source: ImageSource, maxDim: Int = 8192): LoadedImage? = semaphore.withPermit {
        withContext(decodeDispatcher) {
            runCatching { decodeFull(source.openBytes(), maxDim) }.getOrNull()
        }
    }

    private fun decodeFull(bytes: ByteArray, maxDim: Int): LoadedImage {
        val isGif = bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

        if (isGif) {
            GifDecoder.decode(bytes)?.let { return LoadedImage.Animated(it) }
        }

        return LoadedImage.Static(decodeScaled(bytes, maxDim))
    }

    private fun decodeThumbnail(bytes: ByteArray, targetDim: Int): ImageBitmap {
        val isGif = bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

        if (isGif) {
            runCatching {
                GifDecoder.decode(bytes)?.frames?.firstOrNull()
            }.getOrNull()?.let { return it }
        }

        return decodeScaled(bytes, targetDim)
    }

    private fun decodeScaled(bytes: ByteArray, maxDim: Int): ImageBitmap {
        val src = Image.makeFromEncoded(bytes)
        val iw = src.width
        val ih = src.height
        if (iw == 0 || ih == 0) {
            src.close()
            throw IllegalStateException("Empty image")
        }
        val scale = minOf(1f, maxDim.toFloat() / maxOf(iw, ih).toFloat())
        val w = maxOf(1, (iw * scale).toInt())
        val h = maxOf(1, (ih * scale).toInt())

        if (w == iw && h == ih) {
            return src.toComposeImageBitmap()
        }

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
        val canvas = Canvas(bitmap)
        canvas.drawImageRect(
            src,
            Rect.makeWH(iw.toFloat(), ih.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        val pixels = bitmap.readPixels() ?: throw IllegalStateException("readPixels failed")
        val image = Image.makeRaster(bitmap.imageInfo, pixels, bitmap.rowBytes)
        canvas.close()
        bitmap.close()
        src.close()
        return image.toComposeImageBitmap()
    }
}
