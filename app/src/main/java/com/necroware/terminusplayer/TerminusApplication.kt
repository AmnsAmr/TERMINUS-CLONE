package com.necroware.terminusplayer

import android.app.Application
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TerminusApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        
        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<com.necroware.terminusplayer.sync.LibrarySyncWorker>(
            12, java.util.concurrent.TimeUnit.HOURS
        ).build()
        
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "LibrarySyncWorker",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
