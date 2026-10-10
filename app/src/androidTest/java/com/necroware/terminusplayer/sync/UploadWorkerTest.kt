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
import com.necroware.terminusplayer.util.AudioConverter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
        override suspend fun updateStateWithForceConvert(songId: String, state: String, attemptCount: Int, forceConvert: Boolean) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = state, attemptCount = attemptCount, forceConvert = forceConvert)
        }
        override suspend fun updateStateWithError(songId: String, state: String, attemptCount: Int, errorMessage: String?) {
            val i = items.indexOfFirst { it.songId == songId }
            if (i >= 0) items[i] = items[i].copy(state = state, attemptCount = attemptCount, errorMessage = errorMessage)
        }
        override suspend fun resetUploadingToWaiting() {}
        override suspend fun trimHistory() {}
        override suspend fun clearAll() {}
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

    private class FakeConverter : AudioConverter {
        var convertCalled = 0
        var shouldFailConversion = false
        override fun isOpus(context: Context, uri: Uri): Boolean = true
        override suspend fun convertOpusToAac(context: Context, inputUri: Uri, originalName: String): File {
            convertCalled++
            if (shouldFailConversion) throw IOException("FFmpeg conversion failed: error")
            return File.createTempFile("fake", ".m4a")
        }
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun testSuccessfulConversionAndUpload() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo()
        val converter = FakeConverter()

        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, converter)
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, converter.convertCalled)
        assertEquals(1, repo.uploadCalled)
        assertTrue(repo.uploadedUri.toString().endsWith(".m4a"))
        assertEquals("SUCCESS", dao.items[0].state)
    }

    @Test
    fun testConversionFailureMarksAsConversionFailed() = runBlocking {
        val dao = FakeDao()
        dao.items.add(UploadQueueEntity("1", "content://fake/test.opus", "WAITING", 0, 0, "Test Opus"))
        val repo = FakeRepo()
        val converter = FakeConverter().apply { shouldFailConversion = true }

        val worker = TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                    UploadWorker(appContext, workerParameters, repo, dao, converter)
            })
            .build()

        worker.doWork()

        assertEquals(1, converter.convertCalled)
        assertEquals(0, repo.uploadCalled)
        assertEquals("CONVERSION_FAILED", dao.items[0].state)
        assertTrue(dao.items[0].errorMessage!!.contains("FFmpeg conversion failed"))
    }
}
