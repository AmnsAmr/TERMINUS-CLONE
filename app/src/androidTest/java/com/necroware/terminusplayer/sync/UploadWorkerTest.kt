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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class UploadWorkerTest {

    private class FakeDao : UploadQueueDao {
        val items = mutableListOf<UploadQueueEntity>()
        override suspend fun insertAll(items: List<UploadQueueEntity>) {}
        override suspend fun insert(item: UploadQueueEntity) {}
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<UploadQueueEntity>())
        override suspend fun getAll() = items
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
        override suspend fun resetUploadingToWaiting() {}
        override suspend fun trimHistory() {}
        override suspend fun clearAll() {}
        override suspend fun removeIfNotUploading(songId: String) {}
    }

    private class FakeRepo : NavidromeUploadRepository(InstrumentationRegistry.getInstrumentation().targetContext, null as? com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService ?: throw RuntimeException()) {
        var uploadCalled = 0
        var uploadedUri: Uri? = null
        var shouldFailUpload = false
        override suspend fun upload(uri: Uri, onProgress: ((Int) -> Unit)?): String {
            uploadCalled++
            uploadedUri = uri
            if (shouldFailUpload) throw IOException("Server did not accept")
            return "success"
        }
        override suspend fun checkBulkUploads(uris: List<Uri>): List<String> = emptyList()
        override suspend fun getFileName(uri: Uri): String? = "test.opus"
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
                    UploadWorker(appContext, workerParameters, repo, dao)
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, repo.uploadCalled)
        assertEquals("content://fake/test.opus", repo.uploadedUri.toString())
        assertEquals("SUCCESS", dao.items[0].state)
    }

    @Test
    fun opusUploadRejectionDoesNotAttemptConversion() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo().apply { shouldFailUpload = true }

        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao)
            })
            .build()

        worker.doWork()

        assertEquals(1, repo.uploadCalled)
        assertEquals("ALREADY_EXISTS", dao.items[0].state)
        assertEquals("Server did not accept", dao.items[0].errorMessage)
    }
}
