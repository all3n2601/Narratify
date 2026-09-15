package app.narratify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small, bounded image loader for catalog thumbnails. The library itself stays dependency-free. */
internal class CatalogCoverLoader {
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val cache = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(address: String?, deliver: (Bitmap) -> Unit) {
        val normalized = address?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return
        cache.get(normalized)?.takeIf { !it.isRecycled }?.let { bitmap ->
            // Posting also lets a newly-created result card attach before its cover is delivered.
            main.post { if (!closed.get()) deliver(bitmap) }
        } ?: executor.execute {
            val bitmap = runCatching { download(normalized) }.getOrNull() ?: return@execute
            cache.put(normalized, bitmap)
            if (!closed.get()) main.post { if (!closed.get()) deliver(bitmap) }
        }
    }

    fun close() {
        closed.set(true)
        executor.shutdownNow()
    }

    private fun download(address: String): Bitmap? {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,image/*")
            setRequestProperty("User-Agent", "Narratify/1.0 (Android cover preview)")
        }
        return try {
            if (connection.responseCode !in 200..299 || !connection.url.protocol.equals("https", true)) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            decodeSampled(bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > TARGET_WIDTH * 2 || bounds.outHeight / sample > TARGET_HEIGHT * 2) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024L * 1024L
        const val MEMORY_CACHE_KB = 16 * 1024
        const val TARGET_WIDTH = 480
        const val TARGET_HEIGHT = 720
    }
}
