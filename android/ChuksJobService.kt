// Background tasks on Android: the JobService the platform starts, and the scheduling
// that puts jobs in front of it.
//
// The hard part here is not JobScheduler, it is that a job can start with NO ACTIVITY.
// Android may launch the process purely to run the job, so there is no MainActivity, no
// views, and nothing has mounted. That is fine for us in a way it is not for a framework
// with a separate background context: `object N` loads the engine library on first touch
// from anywhere in the process, so this service can drive the same engine the UI drives.
//
// Mounting with no UI is the trick that makes a cold wake work. Mount runs the app's
// createRoot(), which registers its task handlers, which is how a token exists at all.
// The mutation stream that comes back describes views nobody will draw, so it is read for
// the one thing it carries that matters here and otherwise discarded.
package com.chuks.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log

/// Process-wide task state, shared by the Activity and the JobService because either may
/// be the one that learns a token or the one that has to finish a job.
object ChuksBg {
    val tokens = HashMap<String, String>()          // task name -> engine token
    val running = HashMap<String, JobParameters>()  // task name -> the job awaiting jobFinished
    var service: ChuksJobService? = null

    /// A stable job id per task name. JobScheduler keys on an int, and it has to survive
    /// reinstalls and reboots, so it must be derived from the name rather than counted.
    fun jobId(name: String): Int {
        var h = 0
        for (c in name) { h = h * 31 + c.code }
        return h and 0x7fffffff
    }

    /// Pull the `bg.define` tokens out of a mutation stream. Lines look like
    /// `X|<token>|bg.define|<name>`.
    fun harvestTokens(stream: String) {
        for (line in stream.split("\n")) {
            if (!line.startsWith("X|")) continue
            val f = line.split("|")
            if (f.size >= 4 && f[2] == "bg.define") tokens[f[3]] = f[1]
        }
    }

    /// The engine reported a result for a task. Tell the platform, which is what stops it
    /// from counting the job as failed and burning the app's future scheduling budget.
    fun finish(name: String, success: Boolean) {
        val p = running.remove(name) ?: return
        // needsReschedule=false: a periodic job repeats on its own, and a failed one-off
        // is retried through the backoff we set at schedule time.
        service?.jobFinished(p, !success)
    }
}

class ChuksJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pump: Runnable? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val name = params.extras.getString("chuks_task") ?: return false
        ChuksBg.service = this
        ChuksBg.running[name] = params

        // Warm process: the Activity already mounted and harvested the tokens. Cold
        // process: mount here, with no UI, purely so the app's task definitions run.
        var token = ChuksBg.tokens[name]
        if (token == null) {
            N.mount()
            ChuksBg.harvestTokens(N.drain())
            token = ChuksBg.tokens[name]
        }
        if (token == null) {
            Log.w("chuks-bg", "no handler defined for \"$name\"; define it from createRoot()")
            ChuksBg.running.remove(name)
            return false
        }

        N.resolve(token, name)
        startPump(name)
        return true      // the work continues past this call
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // The OS is taking the time back. Tell the engine so a handler can checkpoint
        // rather than lose a half-finished batch, then ask to be rescheduled.
        val name = params.extras.getString("chuks_task") ?: ""
        N.event("__bgexpire__")
        ChuksBg.running.remove(name)
        stopPump()
        return true      // reschedule per the job's backoff policy
    }

    /// Turn the engine while a handler runs. An awaiting handler produces its result on a
    /// later turn, and the frame callback that normally drives those turns is not running
    /// with no UI on screen.
    private fun startPump(name: String) {
        if (pump != null) return
        val r = object : Runnable {
            override fun run() {
                if (ChuksBg.running.isEmpty()) { stopPump(); return }
                N.tick()
                for (line in N.drain().split("\n")) {
                    if (!line.startsWith("X|")) continue
                    val f = line.split("|")
                    if (f.size >= 4 && f[2] == "bg.result") {
                        val parts = f[3].split("|")
                        val rn = parts.getOrNull(0) ?: ""
                        ChuksBg.finish(rn, parts.getOrNull(1) == "1")
                    }
                }
                handler.postDelayed(this, 50)
            }
        }
        pump = r
        handler.postDelayed(r, 50)
    }
    private fun stopPump() { pump?.let { handler.removeCallbacks(it) }; pump = null }
}

/// Schedule one task. Called from MainActivity's command dispatch.
///
/// Every constraint WorkManager offers is here, because WorkManager is itself built on
/// JobScheduler: nothing is lost by not having Gradle. iOS can express two of these, so
/// the ones it cannot are honoured here and reported there rather than silently dropped
/// on both.
fun scheduleChuksJob(ctx: Context, name: String, seconds: Int, periodic: Boolean, cons: Map<String, String>) {
    val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val extras = PersistableBundle()
    extras.putString("chuks_task", name)
    val b = JobInfo.Builder(ChuksBg.jobId(name), ComponentName(ctx, ChuksJobService::class.java))
        .setExtras(extras)
    // Surviving a reboot is what "periodic" implies, but JobScheduler THROWS rather than
    // declining when the app has not declared RECEIVE_BOOT_COMPLETED. The build adds that
    // permission for an app with backgroundTasks; this check means an app that somehow
    // lacks it loses reboot persistence instead of crashing on launch.
    val canPersist = ctx.checkSelfPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (canPersist) b.setPersisted(true)
    else Log.w("chuks-bg", "\"$name\" will not survive a reboot: RECEIVE_BOOT_COMPLETED is not declared")

    when (cons["network"] ?: "none") {
        "any" -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        "unmetered" -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        else -> b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
    }
    if (cons["charging"] == "1") b.setRequiresCharging(true)
    if (cons["deviceIdle"] == "1") b.setRequiresDeviceIdle(true)
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        if (cons["batteryNotLow"] == "1") b.setRequiresBatteryNotLow(true)
        if (cons["storageNotLow"] == "1") b.setRequiresStorageNotLow(true)
    }

    if (periodic) {
        // The platform floors this at 15 minutes and treats it as a window, not a clock.
        b.setPeriodic(maxOf(seconds, 900) * 1000L)
    } else {
        b.setMinimumLatency(maxOf(seconds, 0) * 1000L)
        b.setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
    }
    // Any rejection is a log, never a crash: an app should not die because the platform
    // disliked a job's shape, and the message has to say which task and why.
    try {
        val res = js.schedule(b.build())
        if (res != JobScheduler.RESULT_SUCCESS) Log.w("chuks-bg", "could not schedule \"$name\"")
    } catch (e: Exception) {
        Log.w("chuks-bg", "could not schedule \"$name\": ${e.message}")
    }
}

fun cancelChuksJob(ctx: Context, name: String) {
    (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(ChuksBg.jobId(name))
}
fun cancelAllChuksJobs(ctx: Context) {
    (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancelAll()
}
/// "scheduled" / "notScheduled". Android has no per-app background switch to report, so
/// unlike iOS there is no "restricted" answer here.
fun chuksJobStatus(ctx: Context, name: String): String {
    val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val id = ChuksBg.jobId(name)
    return if (js.allPendingJobs.any { it.id == id }) "scheduled" else "notScheduled"
}
