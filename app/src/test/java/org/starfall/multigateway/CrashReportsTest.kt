package org.starfall.multigateway

import android.app.Application
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.service.CrashRecordingHandler
import org.starfall.multigateway.data.service.CrashReportStore
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CrashReportsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun uncaughtErrorIsSavedBeforeDelegatingAndSurvivesRestartUntilDismissed() {
        val file = File(temporary.root, "crash.json")
        val store = CrashReportStore(file)
        val error = IllegalStateException("UI failure", IllegalArgumentException("Invalid position & index"))
        var delegated = false
        val handler = CrashRecordingHandler(store) { thread, thrown ->
            assertSame(Thread.currentThread(), thread)
            assertSame(error, thrown)
            assertNotNull(CrashReportStore(file).pending())
            delegated = true
        }
        handler.uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
        val restarted = CrashReportStore(file)
        val report = restarted.pending()!!
        assertEquals("java.lang.IllegalArgumentException: Invalid position & index", report.cause)
        assertTrue(report.stackTrace.contains("Caused by: java.lang.IllegalArgumentException"))
        assertEquals(report, CrashReportStore(file).pending())
        restarted.dismiss(report.id)
        assertNull(CrashReportStore(file).pending())
    }

    @Test fun dismissingOldReportDoesNotEraseNewCrash() {
        val store = CrashReportStore(File(temporary.root, "crash.json"))
        store.record(Thread.currentThread(), IllegalStateException("First"))
        val old = store.pending()!!
        store.record(Thread.currentThread(), IllegalStateException("Second"))
        store.dismiss(old.id)
        assertTrue(store.pending()!!.cause.contains("Second"))
    }

    @Test fun missingOrDamagedReportDoesNotPreventStartup() {
        val file = File(temporary.root, "crash.json")
        val store = CrashReportStore(file)
        assertNull(store.pending())
        file.writeText("broken JSON")
        assertNull(store.pending())
    }

    @Test fun savingFailureStillDelegatesOriginalError() {
        val parent = temporary.newFile("not-a-directory")
        val store = CrashReportStore(File(parent, "crash.json"))
        val error = IllegalStateException("Original error")
        var delegated: Throwable? = null
        CrashRecordingHandler(store) { _, thrown -> delegated = thrown }
            .uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated)
    }

    @Test fun issueLinkTargetsRepoAndPreservesEncodedCauseAndStackTrace() {
        val store = CrashReportStore(File(temporary.root, "crash.json"))
        store.record(Thread.currentThread(), IllegalStateException("Invalid # & ? / vị trí"))
        val report = store.pending()!!
        val uri = report.issueUri()
        assertEquals("github.com", uri.host)
        assertEquals("/starfall-org/multigateway/issues/new", uri.path)
        assertTrue(uri.getQueryParameter("title")!!.contains("Invalid # & ? / vị trí"))
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains(report.cause))
        assertTrue(body.contains(report.appVersion))
        assertTrue(body.contains("at org.starfall.multigateway.CrashReportsTest"))
    }
}
