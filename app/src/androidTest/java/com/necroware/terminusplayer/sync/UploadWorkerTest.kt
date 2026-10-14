package com.necroware.terminusplayer.sync

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import com.necroware.terminusplayer.data.repository.DownloadTransferRepository
import com.necroware.terminusplayer.data.repository.DuplicateUploadException
import com.necroware.terminusplayer.data.repository.UploadQuotaExceededException
import com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService
import com.necroware.terminusplayer.util.TransferProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationManager
import android.app.Notification
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class UploadWorkerTest {

    private class FakeDao : UploadQueueDao {
        val items = mutableListOf<UploadQueueEntity>()
        override suspend fun insertAll(items: List<UploadQueueEntity>) {}
        override suspend fun insert(item: UploadQueueEntity) {}
        override suspend fun insertIfAbsent(items: List<UploadQueueEntity>) {}
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<UploadQueueEntity>())
        override suspend fun getAll() = items
        override suspend fun countPending() = items.count { it.state == "WAITING" || it.state == "FAILED" }
        override suspend fun getNext() = items.firstOrNull { it.state == "WAITING" || (it.state == "FAILED" && it.attemptCount < 3) }
        override suspend fun updateState(songId: String, state: String, attemptCount: Int) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = state, attemptCount = attemptCount)
        }
        override suspend fun resetForRetry(songId: String) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = "WAITING", attemptCount = 0, forceConvert = false)
        }
        override suspend fun updateStateWithError(songId: String, state: String, attemptCount: Int, errorMessage: String?) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = state, attemptCount = attemptCount, errorMessage = errorMessage)
        }
        override suspend fun markTransferStarted(songId: String, state: String, attemptCount: Int, totalBytes: Long?, now: Long): Int {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = state, attemptCount = attemptCount, totalBytes = totalBytes, startedAtEpochMs = now)
            return if (i >= 0) 1 else 0
        }
        override suspend fun updateProgress(songId: String, bytesTransferred: Long, totalBytes: Long?, partialUri: String?, now: Long) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(bytesTransferred = bytesTransferred, totalBytes = totalBytes, partialUri = partialUri ?: items[i].partialUri, lastProgressAtEpochMs = now)
        }
        override suspend fun clearPartialUri(songId: String) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(partialUri = null)
        }
        override suspend fun updatePhase(songId: String, phase: String) {}
        override suspend fun markInterrupted(songId: String, errorMessage: String) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = "WAITING", phase = "INTERRUPTED", errorMessage = errorMessage)
        }
        override suspend fun markPendingUploadsQuotaExceeded(errorMessage: String): Int {
            var count = 0
            items.indices.forEach { i ->
                if (items[i].transferType == "UPLOAD" && items[i].state in setOf("WAITING", "FAILED", "UPLOADING")) {
                    items[i] = items[i].copy(state = "QUOTA_EXCEEDED", phase = "BLOCKED_QUOTA", errorMessage = errorMessage)
                    count++
                }
            }
            return count
        }
        override suspend fun resetUploadingToWaiting() {
            items.indices.forEach { i ->
                if (items[i].state == "UPLOADING" || items[i].state == "DOWNLOADING" || items[i].state == "CONVERTING") {
                    items[i] = items[i].copy(state = "WAITING", phase = "INTERRUPTED", errorMessage = "Transfer interrupted; queued to resume")
                }
            }
        }
        override suspend fun trimHistory() {}
        override suspend fun clearUnfinished() {}
        override suspend fun removeIfNotUploading(songId: String) {}
    }

    private class FakeRepo : NavidromeUploadRepository(InstrumentationRegistry.getInstrumentation().targetContext, unusedUploadApi()) {
        var uploadCalled = 0
        var uploadedUri: Uri? = null
        var failureMessage: String? = null
        var cancelUpload = false
        var quotaExceeded = false
        override suspend fun upload(uri: Uri, onProgress: ((TransferProgress) -> Unit)?): String {
            uploadCalled++
            uploadedUri = uri
            if (cancelUpload) throw kotlinx.coroutines.CancellationException("test cancellation")
            if (quotaExceeded) throw UploadQuotaExceededException("Server upload quota is full")
            failureMessage?.let {
                if (it.contains("already exists", ignoreCase = true)) throw DuplicateUploadException(it)
                throw IOException(it)
            }
            onProgress?.invoke(TransferProgress(20L, 46L))
            return "success"
        }
        override suspend fun checkBulkUploads(uris: List<Uri>): List<String> = emptyList()
        override suspend fun getFileName(uri: Uri): String? = "test.opus"
    }

    private companion object {
        fun unusedUploadApi(): NavidromeNativeApiService = java.lang.reflect.Proxy.newProxyInstance(
            NavidromeNativeApiService::class.java.classLoader,
            arrayOf(NavidromeNativeApiService::class.java)
        ) { _, _, _ -> throw UnsupportedOperationException("This test calls only fake repository methods") } as NavidromeNativeApiService
    }

    private class FakeDownloader : DownloadTransferRepository {
        var downloadedSongId: String? = null
        var cleanedPartialUri: String? = null
        override suspend fun downloadSong(songId: String, force: Boolean, onProgress: ((TransferProgress) -> Unit)?): String {
            downloadedSongId = songId
            onProgress?.invoke(TransferProgress(10, 20))
            return "content://download/$songId"
        }
        override suspend fun cleanupInterruptedDownload(partialUri: String) { cleanedPartialUri = partialUri }
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun opusFileUploadsWithoutConversion() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo()

        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, FakeDownloader())
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, repo.uploadCalled)
        assertEquals("content://fake/test.opus", repo.uploadedUri.toString())
        assertEquals("SUCCESS", dao.items[0].state)
        assertEquals(20L, dao.items[0].bytesTransferred)
        assertEquals(46L, dao.items[0].totalBytes)
    }

    @Test
    fun retryAfterPartialBatchOnlyProcessesWaitingSongs() = runBlocking {
        val dao = FakeDao().apply {
            items.add(UploadQueueEntity("already-uploaded", "content://fake/done.opus", "SUCCESS", 1, 0, "Done"))
            items.add(UploadQueueEntity("remaining", "content://fake/remaining.opus", "WAITING", 0, 1, "Remaining"))
        }
        val repo = FakeRepo()
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, FakeDownloader())
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, repo.uploadCalled)
        assertEquals("SUCCESS", dao.items.first { it.songId == "already-uploaded" }.state)
        assertEquals("SUCCESS", dao.items.first { it.songId == "remaining" }.state)
    }

    @Test
    fun quotaRejectionBlocksRemainingUploadsButAllowsDownloadsToContinue() = runBlocking {
        val dao = FakeDao().apply {
            items.add(UploadQueueEntity("upload-1", "content://fake/one.opus", "WAITING", 0, 0, "Upload one"))
            items.add(UploadQueueEntity("upload-2", "content://fake/two.opus", "WAITING", 0, 1, "Upload two"))
            items.add(UploadQueueEntity("download:navidrome:42", "", "WAITING", 0, 2, "Download", transferType = "DOWNLOAD", remoteSongId = "navidrome:42"))
        }
        val repo = FakeRepo().apply { quotaExceeded = true }
        val downloader = FakeDownloader()
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, downloader)
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, repo.uploadCalled)
        assertEquals("QUOTA_EXCEEDED", dao.items.first { it.songId == "upload-1" }.state)
        assertEquals("QUOTA_EXCEEDED", dao.items.first { it.songId == "upload-2" }.state)
        assertEquals("SUCCESS", dao.items.first { it.transferType == "DOWNLOAD" }.state)
    }

    @Test
    fun opusUploadRejectionDoesNotAttemptConversion() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo().apply { failureMessage = "test.opus already exists or is pending" }

        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, FakeDownloader())
            })
            .build()

        worker.doWork()

        assertEquals(1, repo.uploadCalled)
        assertEquals("ALREADY_EXISTS", dao.items[0].state)
        assertEquals("test.opus already exists or is pending", dao.items[0].errorMessage)
    }

    @Test
    fun downloadUsesSharedWorkerAndPersistsReceivedBytes() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity(
            songId = "download:navidrome:42",
            uriString = "",
            state = "WAITING",
            attemptCount = 0,
            addedAtEpochMs = 0,
            title = "Artist - Track",
            transferType = "DOWNLOAD",
            remoteSongId = "navidrome:42"
        ))
        val downloader = FakeDownloader()
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, FakeRepo(), dao, downloader)
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("navidrome:42", downloader.downloadedSongId)
        assertEquals("SUCCESS", dao.items[0].state)
        assertEquals(10L, dao.items[0].bytesTransferred)
        assertEquals(20L, dao.items[0].totalBytes)
    }

    @Test
    fun interruptedActiveTransferReturnsToResumableQueueState() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "UPLOADING", 0, 0, "Test Opus"))

        dao.resetUploadingToWaiting()

        assertEquals("WAITING", dao.items[0].state)
        assertEquals("INTERRUPTED", dao.items[0].phase)
        assertEquals("Transfer interrupted; queued to resume", dao.items[0].errorMessage)
    }

    @Test
    fun userCancellationLeavesTransferQueuedForResume() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo().apply { cancelUpload = true }
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, FakeDownloader())
            })
            .build()

        try {
            worker.doWork()
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals("WAITING", dao.items[0].state)
            assertEquals("INTERRUPTED", dao.items[0].phase)
        }
    }

    @Test
    fun interruptedDownloadDeletesItsPartialDestinationBeforeRetry() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity(
            songId = "download:navidrome:42",
            uriString = "",
            state = "DOWNLOADING",
            attemptCount = 1,
            addedAtEpochMs = 0,
            title = "Artist - Track",
            transferType = "DOWNLOAD",
            remoteSongId = "navidrome:42",
            partialUri = "content://partial/track"
        ))
        val downloader = FakeDownloader()
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, FakeRepo(), dao, downloader)
            })
            .build()

        worker.doWork()

        assertEquals("content://partial/track", downloader.cleanedPartialUri)
        assertEquals("SUCCESS", dao.items[0].state)
        assertEquals(null, dao.items[0].partialUri)
    }

    @Test
    fun transientUploadFailureRetriesThreeTimesAndRecordsReason() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo().apply { failureMessage = "Connection reset" }
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, FakeDownloader())
            })
            .build()

        worker.doWork()

        assertEquals(3, repo.uploadCalled)
        assertEquals("PERMANENT_FAILURE", dao.items[0].state)
        assertEquals(3, dao.items[0].attemptCount)
        assertEquals("Connection reset", dao.items[0].errorMessage)
    }

    @Test
    fun completionNotificationReplacesProgressNotification() = runBlocking {
        assumeTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, FakeRepo(), dao, FakeDownloader())
            })
            .build()

        worker.doWork()

        val active = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications
        val notification = active.firstOrNull { it.id == com.necroware.terminusplayer.sync.TransferNotifications.QUEUE_NOTIFICATION_ID }?.notification
        assertEquals("Transfers complete", notification?.extras?.getString(Notification.EXTRA_TITLE))
    }
}
