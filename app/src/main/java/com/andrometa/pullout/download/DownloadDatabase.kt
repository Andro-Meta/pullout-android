package com.andrometa.pullout.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
@TypeConverters(StatusConverters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile private var INSTANCE: DownloadDatabase? = null
        fun getInstance(context: Context): DownloadDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, DownloadDatabase::class.java, "pullout_downloads.db"
                ).build().also { INSTANCE = it }
            }
    }
}
