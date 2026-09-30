package io.github.dorumrr.privacyflip

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.Executor

// A real PrivacyActionWorker runs privilege detection (the host's su) and holds static
// in-progress flags past the end of a test, which silently skips the next test's enqueue.
fun initWorkManagerWithoutRealWork(
    context: Context,
    onRun: (WorkerParameters) -> Unit = {},
    workExecutor: Executor = SynchronousExecutor(),
    // Last, so the existing trailing-lambda callers keep binding here.
    taskExecutor: Executor = SynchronousExecutor()
) {
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
            .setExecutor(workExecutor)
            .setTaskExecutor(taskExecutor)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    onRun(workerParameters)
                    return object : Worker(appContext, workerParameters) {
                        override fun doWork() = Result.success()
                    }
                }
            })
            .build(),
        WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS
    )
}
