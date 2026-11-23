package com.nadatube.app.download

import com.nadatube.app.data.db.DownloadStatus
import com.nadatube.app.data.db.DownloadWithTrack
import com.nadatube.app.model.Track

/** A downloaded (or downloading) track as shown in the Library "Diunduh" tab. */
data class OfflineTrack(
    val track: Track,
    val status: DownloadStatus,
    val progress: Float,
    val filePath: String?,
    val coverPath: String?,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val formatLabel: String,
) {
    val isPlayable: Boolean get() = status == DownloadStatus.COMPLETED && filePath != null

    companion object {
        fun from(row: DownloadWithTrack): OfflineTrack? {
            val t = row.track ?: return null
            val d = row.download
            val codec = when {
                d.mimeType == null -> ""
                d.mimeType.startsWith("audio/mp4") -> "AAC"
                else -> "Opus"
            }
            return OfflineTrack(
                track = t.toTrack().let { if (d.coverPath != null) it.copy(thumbnailUrl = "file://${d.coverPath}") else it },
                status = d.downloadStatus,
                progress = d.progress.coerceIn(0f, 1f),
                filePath = d.filePath,
                coverPath = d.coverPath,
                sizeBytes = d.sizeBytes,
                bitrateKbps = d.bitrateKbps,
                formatLabel = listOf(codec, if (d.bitrateKbps > 0) "${d.bitrateKbps} kbps" else "").filter { it.isNotEmpty() }.joinToString(" · "),
            )
        }
    }
}

object OfflineFiles {
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private const val MAX_BASE = 80

    /** "Artist - Title [videoId].ext" with filesystem-unsafe characters removed. */
    fun fileName(track: Track, extension: String): String {
        val base = "${track.artist} - ${track.title}".replace(ILLEGAL, "_").replace(Regex("\\s+"), " ").trim().trim('.')
        val safe = base.take(MAX_BASE).trimEnd().ifEmpty { "track" }
        return "$safe [${track.videoId}].$extension"
    }
}

data class StorageSummary(
    val downloadsBytes: Long,
    val downloadCount: Int,
    val cacheBytes: Long,
    val cacheLimitBytes: Long,
    val freeBytes: Long,
) {
    val cacheFraction: Float get() = if (cacheLimitBytes <= 0) 0f else (cacheBytes.toFloat() / cacheLimitBytes).coerceIn(0f, 1f)

    /** Share of device-available space (free + ours) that NadaTube uses. */
    val usedFraction: Float
        get() {
            val ours = downloadsBytes + cacheBytes
            val total = ours + freeBytes
            return if (total <= 0) 0f else (ours.toFloat() / total).coerceIn(0f, 1f)
        }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }
    }
}
