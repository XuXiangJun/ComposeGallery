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
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
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

/**
 * 按字节上限维护的 LRU 缓存。
 *
 * 图片占的是 native 内存，按条数限制时一旦用户把缩略图尺寸调大（最大 640px）就容易失控，
 * 所以这里按像素字节数计量。
 */
private class ByteLruCache<V>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<String, V>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(key: String, value: V) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val eldest = it.next()
            bytes -= sizeOf(eldest.value)
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }
}

object ImageLoader {
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val semaphore = Semaphore(4)

    /** 缩略图像素缓存上限（约 96MB）。 */
    private const val THUMBNAIL_CACHE_BYTES = 96L * 1024 * 1024

    /** 全尺寸图缓存张数：够前后翻页与预取命中，又不至于常驻太多大图。 */
    private const val FULL_CACHE_ENTRIES = 4

    private val thumbnailCache = ByteLruCache<ImageBitmap>(THUMBNAIL_CACHE_BYTES) { bitmapBytes(it) }

    private val fullCache = object : LinkedHashMap<String, LoadedImage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedImage>?): Boolean =
            size > FULL_CACHE_ENTRIES
    }

    private fun bitmapBytes(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4

    fun invalidateCache() {
        thumbnailCache.clear()
        synchronized(fullCache) { fullCache.clear() }
    }

    suspend fun loadThumbnail(source: ImageSource, targetDim: Int): ImageBitmap? {
        val key = "${source.cacheKey}?$targetDim"
        thumbnailCache.get(key)?.let { return it }
        // 命中缓存的路径不进信号量，避免无谓排队。
        return semaphore.withPermit {
            thumbnailCache.get(key)?.let { return@withPermit it }
            withContext(decodeDispatcher) {
                runCatching { decodeThumbnail(source.openBytes(), targetDim) }
                    .getOrNull()
                    ?.also { thumbnailCache.put(key, it) }
            }
        }
    }

    suspend fun loadFull(source: ImageSource, maxDim: Int = 8192): LoadedImage? {
        val key = "${source.cacheKey}?$maxDim"
        synchronized(fullCache) { fullCache[key] }?.let { return it }
        return semaphore.withPermit {
            synchronized(fullCache) { fullCache[key] }?.let { return@withPermit it }
            withContext(decodeDispatcher) {
                runCatching { decodeFull(source.openBytes(), maxDim) }
                    .getOrNull()
                    ?.also { synchronized(fullCache) { fullCache[key] = it } }
            }
        }
    }

    /** 预取相邻图片：结果直接进全尺寸缓存，翻页时命中；失败静默忽略。 */
    suspend fun prefetch(source: ImageSource, maxDim: Int = 8192) {
        val key = "${source.cacheKey}?$maxDim"
        if (synchronized(fullCache) { fullCache[key] } != null) return
        semaphore.withPermit {
            if (synchronized(fullCache) { fullCache[key] } != null) return@withPermit
            withContext(decodeDispatcher) {
                runCatching { decodeFull(source.openBytes(), maxDim) }
                    .getOrNull()
                    ?.also { synchronized(fullCache) { fullCache[key] = it } }
            }
        }
    }

    private fun decodeFull(bytes: ByteArray, maxDim: Int): LoadedImage {
        val isGif = bytes.size >= 6 &&
            String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")

        if (isGif) {
            runCatching { GifDecoder.decode(bytes, maxDim) }.getOrNull()
                ?.takeIf { it.frames.isNotEmpty() }
                ?.let { return LoadedImage.Animated(it) }
        }

        return LoadedImage.Static(decodeScaled(bytes, maxDim))
    }

    private fun decodeThumbnail(bytes: ByteArray, targetDim: Int): ImageBitmap {
        // 缩略图用首帧即可：Skia 解码 GIF 取第一帧，再缩放到目标尺寸。
        return decodeScaled(bytes, targetDim)
    }

    private fun decodeScaled(bytes: ByteArray, maxDim: Int): ImageBitmap {
        // 优先走 Skia Codec 的「按目标尺寸解码」：JPEG 这类格式支持 1/2、1/4、1/8 缩放解码，
        // 避免「先把上亿像素铺进内存再缩小」的峰值。带 EXIF 旋转的图仍走旧路径，行为不变。
        runCatching { decodeScaledViaCodec(bytes, maxDim) }.getOrNull()?.let { return it }
        return decodeScaledViaFullImage(bytes, maxDim)
    }

    private fun decodeScaledViaCodec(bytes: ByteArray, maxDim: Int): ImageBitmap? {
        val data = Data.makeFromBytes(bytes)
        try {
            // 无法识别的字节（不是图片 / 被截断）会返回 null，而不是抛异常；
            // 显式判空，别让 finally 里的 close() 抛 NPE 去冒充解码失败。
            val codec = Codec.makeFromData(data) ?: return null
            try {
                val info = codec.imageInfo
                val iw = info.width
                val ih = info.height
                if (iw <= 0 || ih <= 0) return null
                // Codec.readPixels 不做 EXIF 旋转，有方向信息时交回旧路径处理。
                if (codec.encodedOrigin != EncodedOrigin.TOP_LEFT) return null
                val scale = minOf(1f, maxDim.toFloat() / maxOf(iw, ih).toFloat())
                val w = maxOf(1, (iw * scale).toInt())
                val h = maxOf(1, (ih * scale).toInt())
                val bitmap = Bitmap()
                bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
                try {
                    codec.readPixels(bitmap)
                    val pixels = bitmap.readPixels() ?: return null
                    val image = Image.makeRaster(bitmap.imageInfo, pixels, bitmap.rowBytes)
                    return image.toComposeImageBitmap()
                } finally {
                    bitmap.close()
                }
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }

    /** 回退路径：整图解码后再缩放（PNG 等没有缩放解码支持、或带 EXIF 方向的图）。 */
    private fun decodeScaledViaFullImage(bytes: ByteArray, maxDim: Int): ImageBitmap {
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
