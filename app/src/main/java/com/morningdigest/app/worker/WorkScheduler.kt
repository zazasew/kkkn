package com.morningdigest.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.morningdigest.app.data.prefs.AppSettings
import com.morningdigest.app.data.prefs.ScheduleMode
import java.util.Calendar
import java.util.concurrent.TimeUnit

object WorkScheduler {

    /**
     * Applies whichever schedule the user picked in Settings: a fixed daily
     * time, or a repeating "every N hours" cadence. Call this any time the
     * schedule-related settings change, on boot, and on app start.
     */
    fun applySchedule(context: Context, settings: AppSettings) {
        if (!settings.autoSendEnabled) {
            cancelDaily(context)
        } else {
            when (settings.scheduleMode) {
                ScheduleMode.DAILY -> scheduleDaily(context, settings.wakeHour, settings.wakeMinute)
                ScheduleMode.INTERVAL -> scheduleInterval(context, settings.intervalHours)
            }
        }
        applyWeatherAlertCheckSchedule(context, settings.customAlertRules.enabled)
    }

    /**
     * Turns the hourly custom-weather-alert-rule check on or off - independent
     * of the main digest schedule/auto-send switch above, since the whole
     * point is a fresh heads-up notification ahead of the daily brief.
     */
    fun applyWeatherAlertCheckSchedule(context: Context, enabled: Boolean) {
        if (enabled) scheduleWeatherAlertChecks(context) else cancelWeatherAlertChecks(context)
    }

    /** Checks the user's custom weather alert rules against the forecast every hour. */
    fun scheduleWeatherAlertChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WeatherAlertCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeatherAlertCheckWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelWeatherAlertChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WeatherAlertCheckWorker.UNIQUE_PERIODIC_NAME)
    }

    /**
     * Schedules (or reschedules) a daily job targeting [hour]:[minute]. WorkManager
     * periodic work doesn't support an exact time-of-day directly, so we compute
     * the initial delay until the next occurrence of that time and use a 24h
     * period from there - this survives app restarts, reboots, and Doze mode.
     */
    fun scheduleDaily(context: Context, hour: Int, minute: Int) {
        val initialDelay = millisUntilNext(hour, minute)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MorningDigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MorningDigestWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Schedules (or reschedules) a repeating job that fires every [hours] hours,
     * starting [hours] from now. Used for the "every N hours" wake-up option
     * (e.g. every 3h, 4h, 6h, 8h or 12h) instead of one fixed daily time.
     */
    fun scheduleInterval(context: Context, hours: Int) {
        val safeHours = hours.coerceIn(1, 24)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MorningDigestWorker>(safeHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(safeHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MorningDigestWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MorningDigestWorker.UNIQUE_PERIODIC_NAME)
    }

    /** "Refresh Now" / "Notify Now" - runs immediately, once. */
    fun runNow(context: Context, sendNotification: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MorningDigestWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, sendNotification).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MorningDigestWorker.UNIQUE_ONE_TIME_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun nextScheduledMillis(hour: Int, minute: Int): Long =
        System.currentTimeMillis() + millisUntilNext(hour, minute)

    /** Best-effort "next send" estimate for either schedule mode, for display in the UI. */
    fun nextScheduledMillis(settings: AppSettings, lastSentMillis: Long?): Long = when (settings.scheduleMode) {
        ScheduleMode.DAILY -> nextScheduledMillis(settings.wakeHour, settings.wakeMinute)
        ScheduleMode.INTERVAL -> {
            val intervalMillis = settings.intervalHours.coerceIn(1, 24) * 3_600_000L
            val base = lastSentMillis ?: System.currentTimeMillis()
            var next = base + intervalMillis
            while (next < System.currentTimeMillis()) next += intervalMillis
            next
        }
    }

    private fun millisUntilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }
}
