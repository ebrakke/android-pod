package dev.reclaimed.player.jellyfin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.io.IOException
import java.util.concurrent.TimeUnit

class JellyfinSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val config = JellyfinSettingsStore(applicationContext).load()
        val libraryId = config.libraryId ?: return Result.success()
        if (!config.isConfigured) return Result.success()

        return try {
            val artists = JellyfinClient(config).getMusicLibrary(libraryId)
            JellyfinMetadataCache(applicationContext).save(
                JellyfinMetadataSnapshot(
                    serverUrl = config.serverUrl,
                    libraryId = libraryId,
                    refreshedAtMs = System.currentTimeMillis(),
                    artists = artists,
                ),
            )
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

object JellyfinSyncScheduler {
    private const val UNIQUE_WORK_NAME = "jellyfin-metadata-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<JellyfinSyncWorker>(
            6,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
