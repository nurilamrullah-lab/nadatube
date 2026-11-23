package com.nadatube.app.download

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nadatube.app.NadaTubeApp
import com.nadatube.app.data.db.DownloadEntity
import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.extractor.AudioStreamSelector
import com.nadatube.app.extractor.InnerTubeClient
import com.nadatube.app.extractor.InnerTubeParser
import com.nadatube.app.model.Track
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val container = (applicationContext as NadaTubeApp).container
        val dao = container.database.downloads()
        val existing = dao.get(videoId) ?: DownloadEntity(videoId, createdAt = System.currentTimeMillis())
        return try {
            dao.upsert(existing.copy(status = DownloadStatus.DOWNLOADING.name, progress = 0f, error = null))
            val resolved = container.innerTube.player(videoId)
            val stream = AudioStreamSelector.selectForDownload(resolved.streams) ?: throw IOException("Tidak ada stream audio")
            val stored = container.database.tracks().get(videoId)?.toTrack() ?: resolved.track
            container.database.tracks().save(com.nadatube.app.data.db.TrackEntity.from(stored))

            val dir = downloadDir(applicationContext)
            val target = File(dir, OfflineFiles.fileName(stored, stream.extension))
            val part = File(dir, target.name + ".part")
            downloadChunked(stream.url, stream.contentLength, part) { p ->
                dao.updateProgress(videoId, DownloadStatus.DOWNLOADING.name, p * 0.95f)
            }

            val coverUrl = stored.thumbnailUrl ?: resolved.track.thumbnailUrl
            val cover = coverUrl?.let { runCatching { fetchBytes(InnerTubeParser.upscaleThumbnail(it)) }.getOrNull() }
            val coverFile = cover?.let { File(dir, target.nameWithoutExtension + ".jpg").apply { writeBytes(it) } }
            if (stream.extension == "m4a") {
                runCatching { Mp4TagWriter.write(part, Mp4TagWriter.Tags(stored.title, stored.artist, stored.album, cover)) }
            }
            if (!part.renameTo(target)) throw IOException("Gagal menyimpan file")

            dao.upsert(
                existing.copy(
                    status = DownloadStatus.COMPLETED.name,
                    progress = 1f,
                    filePath = target.absolutePath,
                    coverPath = coverFile?.absolutePath,
                    mimeType = stream.mimeType,
                    bitrateKbps = stream.nominalKbps,
                    sizeBytes = target.length(),
                    completedAt = System.currentTimeMillis(),
                    error = null,
                ),
            )
            Result.success()
        } catch (e: Exception) {
            dao.upsert(existing.copy(status = DownloadStatus.FAILED.name, error = e.message))
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    /** googlevideo throttles un-ranged requests, so fetch in fixed-size Range chunks. */
    private suspend fun downloadChunked(url: String, knownLength: Long?, out: File, onProgress: suspend (Float) -> Unit) {
        val http = (applicationContext as NadaTubeApp).container.httpClient
        var offset = 0L
        var total = knownLength ?: -1L
        out.delete()
        RandomAccessFile(out, "rw").use { raf ->
            while (total < 0 || offset < total) {
                val end = offset + CHUNK - 1
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=$offset-$end")
                    .header("User-Agent", InnerTubeClient.STREAM_USER_AGENT)
                    .build()
                http.newCall(request).execute().use { r ->
                    if (r.code == 416) return
                    if (!r.isSuccessful) throw IOException("Unduhan HTTP ${r.code}")
                    if (total < 0) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.body!!.contentLength()
                    }
                    val bytes = r.body!!.bytes()
                    if (bytes.isEmpty()) return
                    raf.seek(offset)
                    raf.write(bytes)
                    offset += bytes.size
                }
                if (total > 0) onProgress(offset.toFloat() / total)
                if (isStopped) throw IOException("Dibatalkan")
            }
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val http = (applicationContext as NadaTubeApp).container.httpClient
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.bytes()
        }
    }

    companion object {
        private const val KEY_VIDEO_ID = "videoId"
        private const val CHUNK = 1L shl 20

        fun downloadDir(context: Context): File =
            File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "NadaTube").apply { mkdirs() }

        fun enqueue(context: Context, track: Track) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_VIDEO_ID to track.videoId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("download")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("download-${track.videoId}", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, videoId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("download-$videoId")
        }
    }
}
