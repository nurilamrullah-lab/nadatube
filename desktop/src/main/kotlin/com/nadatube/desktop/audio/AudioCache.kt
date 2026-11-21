package com.nadatube.desktop.audio

import com.nadatube.app.extractor.StreamRejectedException
import com.nadatube.app.model.AudioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * On-disk audio cache: whole tracks, least-recently-used eviction. Files double as the offline
 * copy, so a song that has been played once plays again without the network.
 */
class AudioCache(private val dir: File, private val maxBytes: Long = 512L * 1024 * 1024, private val http: OkHttpClient) {
    init { dir.mkdirs() }

    private fun file(videoId: String, itag: Int) = File(dir, "${videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")}_$itag.m4a")

    /** A previously completed download of [videoId], touched so it counts as recently used. */
    fun cached(videoId: String): File? {
        val prefix = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_"
        val hit = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".m4a") && f.length() > 0 }?.firstOrNull()
        hit?.setLastModified(System.currentTimeMillis())
        return hit
    }

    suspend fun fetch(
        videoId: String,
        stream: AudioStream,
        userAgent: String,
        clientName: String = "",
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = file(videoId, stream.itag)
        val part = File(dir, target.name + ".part")
        part.delete()
        val request = Request.Builder().url(stream.url).header("User-Agent", userAgent).header("Range", "bytes=0-").build()
        http.newCall(request).execute().use { r ->
            if (r.code == 403 || r.code == 410) throw StreamRejectedException(clientName, r.code)
            if (!r.isSuccessful) throw IOException("Unduhan gagal: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Respons kosong")
            val total = stream.contentLength ?: body.contentLength().takeIf { it > 0 }
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val expected = stream.contentLength
        if (expected != null && part.length() != expected) {
            val got = part.length()
            part.delete()
            throw IOException("Unduhan tidak lengkap ($got dari $expected byte)")
        }
        target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        evict(keep = target)
        target
    }

    fun sizeBytes(): Long = dir.listFiles { f -> f.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0L

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun evict(keep: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".m4a") }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }
}
