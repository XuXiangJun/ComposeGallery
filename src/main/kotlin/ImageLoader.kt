package gallery

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
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

/**
 * 缩略图磁盘缓存：`<数据目录>/thumbs/<sha1(key)>.webp`。
 *
 * 内存缓存只活在本次运行里，重开一个上万张图的文件夹就得全部重新解码原图（大 JPEG / 压缩包
 * 条目尤其慢）。缩略图编码成 WebP（保留透明度，比 PNG 小得多）落盘，下次直接解这个小文件。
 * key 里含路径 + 修改时间 + 大小 + 目标尺寸，原图改动后自然失效。总量超过上限时按最后访问
 * 时间淘汰最旧的。所有 IO 失败都静默降级为「没有磁盘缓存」。
 */
internal object DiskThumbnailCache {
    private const val MAX_BYTES = 256L * 1024 * 1024

    /** 每写入这么多次检查一次总量（遍历目录不便宜，不必每次都做）。 */
    private const val PRUNE_EVERY = 200

    private val writes = AtomicInteger()

    val dir: File get() = File(JsonStore.dir, "thumbs")

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".webp")
    }

    fun read(key: String): ByteArray? = try {
        val f = fileFor(key)
        if (f.isFile) {
            f.setLastModified(System.currentTimeMillis()) // 充当「最近访问时间」，淘汰时用
            f.readBytes()
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    fun write(key: String, bitmap: ImageBitmap) {
        try {
            val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
            val data = image.use { it.encodeToData(EncodedImageFormat.WEBP, 85) } ?: return
            val bytes = data.use { it.bytes }
            dir.mkdirs()
            val target = fileFor(key)
            // 先写临时文件再 move：并发写同一个 key、或写到一半退出，都不会留下半截文件。
            val tmp = File.createTempFile(target.name, ".tmp", dir)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.delete()
            if (writes.incrementAndGet() % PRUNE_EVERY == 1) prune()
        } catch (t: Throwable) {
            AppLog.warn("写缩略图缓存失败（${t.message}）")
        }
    }

    fun prune() {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".webp") }?.toList() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES * 3 / 4) break // 一次多删一些，免得每次都刚好卡在上限
                total -= f.length()
                f.delete()
            }
        } catch (_: Throwable) {
        }
    }
}

object ImageLoader {
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(6)

    /** 缩略图解码并发数。 */
    private val semaphore = Semaphore(4)

    /**
     * 全尺寸解码（含翻页预取）单独限流：和缩略图共用一个信号量时，前后两页的预取会占掉
     * 一半名额，翻页那一刻网格缩略图明显变慢。
     */
    private val fullSemaphore = Semaphore(2)

    /** 缩略图像素缓存上限（约 96MB）。 */
    private const val THUMBNAIL_CACHE_BYTES = 96L * 1024 * 1024

    /**
     * 全尺寸图缓存上限（约 384MB）。按字节而不是按张数：一张 8192² 的静态图就有 256MB、
     * 一个 GIF 最多 384MB（见 GifDecoder.MAX_TOTAL_PIXELS），按「4 张」限制最坏能到 1GB 以上。
     * 常见的漫画页（2000×3000 左右 ≈ 24MB）足够放下当前页和前后预取。
     */
    private const val FULL_CACHE_BYTES = 384L * 1024 * 1024

    private val thumbnailCache = ByteLruCache<ImageBitmap>(THUMBNAIL_CACHE_BYTES) { bitmapBytes(it) }

    private val fullCache = ByteLruCache<LoadedImage>(FULL_CACHE_BYTES) { loadedBytes(it) }

    private fun bitmapBytes(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height * 4

    private fun loadedBytes(image: LoadedImage): Long = when (image) {
        is LoadedImage.Static -> bitmapBytes(image.bitmap)
        is LoadedImage.Animated -> image.animation.frames.sumOf { bitmapBytes(it) }
    }

    /** 已在全尺寸缓存里就直接返回（不挂起）；查看器用它避免切图时闪一帧「加载中」。 */
    fun peekFull(source: ImageSource, maxDim: Int = 8192): LoadedImage? = fullCache.get("${source.cacheKey}?$maxDim")

    fun invalidateCache() {
        thumbnailCache.clear()
        fullCache.clear()
    }

    suspend fun loadThumbnail(source: ImageSource, targetDim: Int): ImageBitmap? {
        val key = "${source.cacheKey}?$targetDim"
        thumbnailCache.get(key)?.let { return it }
        // 命中缓存的路径不进信号量，避免无谓排队。
        return semaphore.withPermit {
            thumbnailCache.get(key)?.let { return@withPermit it }
            withContext(decodeDispatcher) {
                val cached = DiskThumbnailCache.read(key)
                    ?.let { bytes -> runCatching { decodeScaled(bytes, targetDim) }.getOrNull() }
                val bitmap = cached
                    ?: runCatching { decodeThumbnail(source.openBytes(), targetDim) }
                        .onFailure { AppLog.warn("缩略图解码失败：${source.cacheKey}（${it.message}）") }
                        .getOrNull()
                        ?.also { DiskThumbnailCache.write(key, it) }
                bitmap?.also { thumbnailCache.put(key, it) }
            }
        }
    }

    suspend fun loadFull(source: ImageSource, maxDim: Int = 8192): LoadedImage? {
        val key = "${source.cacheKey}?$maxDim"
        fullCache.get(key)?.let { return it }
        return fullSemaphore.withPermit {
            fullCache.get(key)?.let { return@withPermit it }
            withContext(decodeDispatcher) {
                runCatching { decodeFull(source.openBytes(), maxDim) }
                    .onFailure { AppLog.warn("图片解码失败：${source.cacheKey}", it) }
                    .getOrNull()
                    ?.also { fullCache.put(key, it) }
            }
        }
    }

    /** 预取相邻图片：结果直接进全尺寸缓存，翻页时命中；失败静默忽略。 */
    suspend fun prefetch(source: ImageSource, maxDim: Int = 8192) {
        loadFull(source, maxDim)
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
            val codec = Codec.makeFromData(data)
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
