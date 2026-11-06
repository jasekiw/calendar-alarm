package com.jason.calendaralarm.alarm

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import kotlin.concurrent.thread

/** Reschedules the next alarm whenever anything in the calendar provider changes. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            AlarmScheduler.scheduleNext(this)
            jobFinished(params, false)
            // Content-trigger jobs fire once, so re-arm for the next change.
            register(this)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 1001

        fun register(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                // Sync adapters write in bursts; wait for them to settle before re-querying.
                .setTriggerContentUpdateDelay(5_000)
                .setTriggerContentMaxDelay(60_000)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
