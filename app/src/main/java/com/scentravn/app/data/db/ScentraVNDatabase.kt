package com.scentravn.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, SampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ScentraVNDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
}
