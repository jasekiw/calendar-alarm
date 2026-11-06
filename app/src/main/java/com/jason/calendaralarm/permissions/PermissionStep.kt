package com.jason.calendaralarm.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

enum class PermissionStep(val title: String, val description: String, val guidance: String) {
    Calendar(
        title = "Read your calendar",
        description = "Needed to find the upcoming events that alarms are set for.",
        guidance = "Tap Grant, then Allow.",
    ),
    Notifications(
        title = "Show notifications",
        description = "Lets the app alert you when an event is about to start.",
        guidance = "Tap Grant, then Allow.",
    ),
    ExactAlarms(
        title = "Schedule exact alarms",
        description = "Lets alarms fire at the exact event time instead of being delayed by the system.",
        guidance = "Tap Open settings, turn on \"Allow setting alarms and reminders\", then come back.",
    ),
    FullScreen(
        title = "Full-screen alarms",
        description = "Lets an alarm take over the screen while the phone is locked, like the Clock app.",
        guidance = "Tap Open settings, turn on \"Allow full screen notifications\", then come back.",
    ),
    Battery(
        title = "Unrestricted battery",
        description = "Stops battery saving from delaying or blocking the app's background checks for calendar changes.",
        guidance = "Tap Open settings, then Allow on \"Let app always run in background?\". " +
            "If App info opens instead, tap App battery usage and choose Unrestricted.",
    ),
    KeepActive(
        title = "Keep active when unused",
        description = "Stops Android from pausing the app and removing its permissions if you don't open it for a few months.",
        guidance = "Tap Open settings, scroll down, and turn off \"Pause app activity if unused\" " +
            "(may be called \"Remove permissions if app isn't used\"), then come back.",
    );

    /** Runtime permission requested with a system dialog, or null when granted via a Settings screen. */
    val runtimePermission: String?
        get() = when (this) {
            Calendar -> Manifest.permission.READ_CALENDAR
            Notifications ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
            else -> null
        }

    fun isGranted(context: Context): Boolean = when (this) {
        Calendar -> context.hasPermission(Manifest.permission.READ_CALENDAR)
        Notifications ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ExactAlarms ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        FullScreen ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Battery -> context.isIgnoringBatteryOptimizations() && !context.isBackgroundRestricted()
        // Before Android 11 the platform has no unused-app restrictions this app can check.
        KeepActive ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                context.packageManager.isAutoRevokeWhitelisted
    }

    /** Settings screen where the user can grant this step by hand. */
    @SuppressLint("BatteryLife")
    fun settingsIntent(context: Context): Intent {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        return when {
            this == ExactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            this == FullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
            // The one-tap dialog only lifts optimization; a Restricted setting has to be changed in App info.
            this == Battery && !context.isIgnoringBatteryOptimizations() ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            this == KeepActive ->
                IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
            else -> appDetailsIntent(context)
        }
    }

    companion object {
        fun appDetailsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

private fun Context.isBackgroundRestricted(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        getSystemService(ActivityManager::class.java).isBackgroundRestricted
