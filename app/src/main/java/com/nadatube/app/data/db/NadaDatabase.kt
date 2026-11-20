package com.nadatube.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, HistoryEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NadaDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun history(): HistoryDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): NadaDatabase =
            Room.databaseBuilder(context, NadaDatabase::class.java, "nadatube.db").build()
    }
}
