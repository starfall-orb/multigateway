package org.starfall.multigateway.data.service

import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.starfall.multigateway.BuildConfig
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

@Serializable
internal data class CrashReport(
    val id: String,
    val timestamp: Long,
    val appVersion: String,
    val device: String,
    val androidVersion: String,
    val thread: String,
    val cause: String,
    val stackTrace: String
) {
    fun issueUri(): Uri {
        val body = """
            ## Crash report
            - App: $appVersion
            - Device: $device
            - Android: $androidVersion
            - Time: ${java.util.Date(timestamp)}
            - Thread: $thread

            ## Steps to reproduce
            Please describe what you were doing before the crash.

            ## Cause
            $cause

            ## Stack trace
            ```text
            ${stackTrace.take(6000)}
            ```
        """.trimIndent()
        return Uri.parse("https://github.com/starfall-org/multigateway/issues/new").buildUpon()
            .appendQueryParameter("title", "Crash: ${cause.lineSequence().first().take(140)}")
            .appendQueryParameter("body", body)
            .build()
    }
}

/** Synchronous, atomic storage: the process may be terminated immediately after the handler returns. */
internal class CrashReportStore(file: File) {
    private val file = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun record(thread: Thread, error: Throwable) {
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        val rootCause = causes.last()
        val report = CrashReport(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            thread = thread.name,
            cause = "${rootCause.javaClass.name}: ${rootCause.message.orEmpty()}".take(2000),
            stackTrace = error.stackTraceToString().take(32000)
        )
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(report).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    fun pending(): CrashReport? = runCatching {
        json.decodeFromString<CrashReport>(file.openRead().use { input ->
            input.reader(Charsets.UTF_8).readText()
        })
    }.getOrNull()

    @Synchronized
    fun dismiss(id: String) {
        // Dismissing an older dialog must not erase a newer background crash.
        if (pending()?.id == id) file.delete()
    }
}

internal class CrashRecordingHandler(
    private val store: CrashReportStore,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            runCatching { store.record(thread, error) }
        } finally {
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
