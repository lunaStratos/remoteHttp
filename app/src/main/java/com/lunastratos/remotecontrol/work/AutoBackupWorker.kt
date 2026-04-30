package com.lunastratos.remotecontrol.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lunastratos.remotecontrol.data.DeviceRepository
import com.lunastratos.remotecontrol.data.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Periodic full export to the app's internal `backups/` dir. No storage permissions
 * required since it stays inside `getFilesDir`. The user can pull a backup off-device
 * via the existing share-export menu.
 *
 * Schedule cadence comes from [Settings.autoBackupHours]. 0 = unscheduled. WorkManager's
 * minimum interval is 15 min, which we accept as a floor for the "1 hour" choice and
 * smaller.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val repo = DeviceRepository.get(ctx)
        val settings = Settings.get(ctx)
        return try {
            val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "devices-$ts.json")
            out.writeText(repo.exportJson())
            // Cap retention at 10 to keep disk use bounded.
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
            settings.lastBackupPath = out.absolutePath
            settings.lastBackupAt = System.currentTimeMillis()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "remotecontrol.auto_backup"

        /** Cancels and re-creates the scheduled job to match [Settings.autoBackupHours]. */
        fun reschedule(context: Context) {
            val hours = Settings.get(context).autoBackupHours
            val wm = WorkManager.getInstance(context)
            if (hours <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val interval = hours.toLong().coerceAtLeast(1L)
            val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().build())
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
