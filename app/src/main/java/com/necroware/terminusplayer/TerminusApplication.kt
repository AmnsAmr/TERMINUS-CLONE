package com.necroware.terminusplayer

import android.app.Application
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class TerminusApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var okHttpClient: OkHttpClient

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .components {
                add(com.necroware.terminusplayer.coil.LocalAudioArtFetcher.Factory(this@TerminusApplication))
            }
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        
        val periodicRequest = androidx.work.PeriodicWorkRequestBuilder<com.necroware.terminusplayer.sync.LibrarySyncWorker>(
            12, java.util.concurrent.TimeUnit.HOURS
        ).build()
        
        val workManager = androidx.work.WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "LibrarySyncWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
        
        // Force an immediate sync so database metadata (like fixed durations) updates instantly
        val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<com.necroware.terminusplayer.sync.LibrarySyncWorker>().build()
        workManager.enqueueUniqueWork("ImmediateSync", androidx.work.ExistingWorkPolicy.REPLACE, oneTimeRequest)
    }
}
