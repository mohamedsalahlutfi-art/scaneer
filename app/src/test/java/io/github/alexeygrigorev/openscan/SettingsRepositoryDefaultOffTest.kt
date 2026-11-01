package io.github.alexeygrigorev.openscan

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.alexeygrigorev.openscan.data.DataStoreSettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Default-off logic against a real (file-backed) DataStore in a temp
 * directory: an empty DataStore must expose the opt-in as disabled.
 */
class SettingsRepositoryDefaultOffTest {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private fun repositoryInTempDir(): DataStoreSettingsRepository {
        val dir = Files.createTempDirectory("openscan-settings-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "settings.preferences_pb") },
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun `default constant is off`() {
        assertEquals(false, DataStoreSettingsRepository.DEFAULT_FEEDBACK_UPLOAD_ENABLED)
    }

    @Test
    fun `empty datastore exposes feedback upload as disabled`() = runBlocking {
        val repository = repositoryInTempDir()
        assertFalse(repository.feedbackUploadEnabled.first())
    }

    @Test
    fun `offline build never lets the upload opt-in turn on`() = runBlocking {
        // SmartScan Pro is strictly offline: no setting may authorize uploads.
        val repository = repositoryInTempDir()
        assertFalse(repository.feedbackUploadEnabled.first())
        repository.setFeedbackUploadEnabled(true)
        assertFalse(repository.feedbackUploadEnabled.first())
    }

    @Test
    fun `capture speed defaults to balanced and persists`() = runBlocking {
        val repository = repositoryInTempDir()
        assertEquals("balanced", repository.captureSpeed.first())
        repository.setCaptureSpeed("turbo")
        assertEquals("turbo", repository.captureSpeed.first())
    }
}
