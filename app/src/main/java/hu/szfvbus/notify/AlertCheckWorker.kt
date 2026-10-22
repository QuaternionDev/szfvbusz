package hu.szfvbus.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import hu.szfvbus.SzfvBusApp
import hu.szfvbus.data.DevTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** A háttérben új forgalmi változásokat keres, és értesít róluk. */
class AlertCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SzfvBusApp).container
        val alerts = try {
            container.transit.alerts(forceRefresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }.filter { it.id != DevTools.TEST_ALERT_ID }

        val enabled = container.prefs.prefs.first().notifyAlerts
        // Engedély nélkül a hír nem számít látottnak, így az engedély megadása után értesítés jön róla.
        if (enabled && !AlertNotifier.canNotify(applicationContext)) return Result.success()

        val seen = container.prefs.seenAlerts()
        val ids = alerts.map { it.id }.toSet()
        // A megjegyzett azonosítók egy ideig megmaradnak, hogy egy egyszer kimaradó hír ne legyen újra új.
        val remembered = (seen.orEmpty() + ids).let { if (it.size > MAX_REMEMBERED) ids else it }
        container.prefs.setSeenAlerts(remembered)

        if (!enabled) return Result.success()
        if (seen == null) {
            // Első ellenőrzés telepítés után: egy összesítő az érvényes változásokról, nem értesítésáradat.
            AlertNotifier.notifySummary(applicationContext, alerts)
        } else {
            AlertNotifier.notifyNew(applicationContext, alerts.filter { it.id !in seen })
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "traffic-changes"
        private const val ONCE = "traffic-changes-now"
        private const val MAX_REMEMBERED = 300

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AlertCheckWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
