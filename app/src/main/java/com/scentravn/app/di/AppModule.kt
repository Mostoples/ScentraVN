package com.scentravn.app.di

import android.content.Context
import androidx.room.Room
import com.scentravn.app.ble.Esp32WatchManager
import com.scentravn.app.ble.MuseSManager
import com.scentravn.app.data.db.ScentraVNDatabase
import com.scentravn.app.data.db.SampleDao
import com.scentravn.app.data.db.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module: object graph wiring for things Hilt cannot construct itself
 * (Room database, third-party BleManager subclasses that need a Context).
 *
 * Note: BleManager subclasses (Esp32WatchManager, MuseSManager) cannot use
 * @Inject constructor because Nordic's BleManager requires a constructor
 * call inside a specific lifecycle, and we want one instance per device per
 * app process -- @Singleton @Provides is the right shape.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ScentraVNDatabase {
        return Room.databaseBuilder(
            context,
            ScentraVNDatabase::class.java,
            "scentravn.db",
        )
            // Demo project: simple destructive migration is fine.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideSessionDao(db: ScentraVNDatabase): SessionDao = db.sessionDao()
    @Provides fun provideSampleDao(db: ScentraVNDatabase): SampleDao = db.sampleDao()

    @Provides
    @Singleton
    fun provideEsp32Manager(@ApplicationContext context: Context): Esp32WatchManager =
        Esp32WatchManager(context)

    @Provides
    @Singleton
    fun provideMuseManager(@ApplicationContext context: Context): MuseSManager =
        MuseSManager(context)
}
