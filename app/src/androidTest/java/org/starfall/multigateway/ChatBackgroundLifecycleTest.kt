package org.starfall.multigateway

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.ui.chat.ChatViewModel

class ChatBackgroundLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Expected service notification state", predicate())
    }

    private fun hasGenerationNotification() = notifications.activeNotifications.any {
        it.id == 4556 && it.notification.category == Notification.CATEGORY_SERVICE &&
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }

    @Test fun generationNotificationSurvivesBackgroundAndScreenOffAndStopsWhenIdle() {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        var busy: MutableStateFlow<Boolean>? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[ChatViewModel::class.java]
                    // Drive the real generation signal without calling a paid API or
                    // modifying conversations on the connected device.
                    val generation = ChatViewModel::class.java.getDeclaredField("generation")
                        .apply { isAccessible = true }.get(model)
                    @Suppress("UNCHECKED_CAST")
                    val state = generation.javaClass.getDeclaredField("_busy")
                        .apply { isAccessible = true }.get(generation) as MutableStateFlow<Boolean>
                    busy = state
                    state.value = true
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                waitFor { hasGenerationNotification() }
                busy!!.value = false
                waitFor { !hasGenerationNotification() }

                // Leaving an idle app must not create any service notification.
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertFalse(hasGenerationNotification())

                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { busy!!.value = true }
                waitFor { hasGenerationNotification() }
                shell("input keyevent 223")
                waitFor { scenario.state == Lifecycle.State.CREATED }
                assertTrue(hasGenerationNotification())
                busy!!.value = false
                waitFor { !hasGenerationNotification() }
            } finally {
                busy?.value = false
                shell("input keyevent 224")
                shell("wm dismiss-keyguard")
            }
        }
    }
}
