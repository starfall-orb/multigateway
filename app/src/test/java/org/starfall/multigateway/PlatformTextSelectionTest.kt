package org.starfall.multigateway

import android.app.Activity
import android.content.Intent
import android.view.*
import android.widget.PopupMenu
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.starfall.multigateway.ui.components.PlatformSelectionToolbar
import org.starfall.multigateway.ui.components.SelectionClipboard

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlatformTextSelectionTest {
    private class Clipboard : ClipboardManager {
        var content = AnnotatedString("Existing clipboard")
        override fun getText() = content
        override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
    }

    private class SelectionView(activity: Activity) : View(activity) {
        var requestedType = -1
        lateinit var callback: ActionMode.Callback
        lateinit var actionMode: ActionMode
        override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode {
            this.callback = callback
            requestedType = type
            val menu = PopupMenu(context, this).menu
            actionMode = object : ActionMode() {
                private var titleValue: CharSequence? = null
                private var subtitleValue: CharSequence? = null
                private var custom: View? = null
                override fun setTitle(title: CharSequence?) { titleValue = title }
                override fun setTitle(resId: Int) { titleValue = context.getString(resId) }
                override fun setSubtitle(subtitle: CharSequence?) { subtitleValue = subtitle }
                override fun setSubtitle(resId: Int) { subtitleValue = context.getString(resId) }
                override fun getTitle() = titleValue
                override fun getSubtitle() = subtitleValue
                override fun setCustomView(view: View?) { custom = view }
                override fun getCustomView() = custom
                override fun getMenu() = menu
                override fun getMenuInflater() = MenuInflater(context)
                override fun invalidate() { callback.onPrepareActionMode(this, menu) }
                override fun finish() { callback.onDestroyActionMode(this) }
            }
            callback.onCreateActionMode(actionMode, menu)
            return actionMode
        }
    }

    @Test fun usesAndroidFloatingActionModeAndCopyActuallyCopiesSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = SelectionView(activity)
        val clipboard = Clipboard()
        val selection = SelectionClipboard(clipboard)
        val toolbar = PlatformSelectionToolbar(view, selection)
        toolbar.showMenu(Rect(10f, 20f, 100f, 50f), { selection.setText(AnnotatedString("Selected text")) }, null, null, {})
        assertEquals(ActionMode.TYPE_FLOATING, view.requestedType)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        assertNotNull(view.actionMode.menu.findItem(android.R.id.selectAll))
        val copy = view.actionMode.menu.findItem(android.R.id.copy)
        assertTrue(view.callback.onActionItemClicked(view.actionMode, copy))
        assertEquals("Selected text", clipboard.content.text)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        activity.finish()
    }

    @Test fun sharingUsesSelectedTextWithoutOverwritingClipboard() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = SelectionView(activity)
        val clipboard = Clipboard()
        val selection = SelectionClipboard(clipboard)
        val toolbar = PlatformSelectionToolbar(view, selection)
        toolbar.showMenu(Rect.Zero, {
            selection.setText(AnnotatedString("Only the selected words"))
            toolbar.hide()
        }, null, null, null)
        val share = (0 until view.actionMode.menu.size()).map { view.actionMode.menu.getItem(it) }
            .single { it.title == "Share" }
        assertTrue(view.callback.onActionItemClicked(view.actionMode, share))
        val chooser = shadowOf(activity).nextStartedActivity
        val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("Only the selected words", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Existing clipboard", clipboard.content.text)
        activity.finish()
    }

    @Test fun selectionRemainsDismissibleWhenAndroidClosesItsToolbar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = SelectionView(activity)
        val toolbar = PlatformSelectionToolbar(view, SelectionClipboard(Clipboard()))
        toolbar.showMenu(Rect.Zero, {}, null, null, {})
        assertTrue(toolbar.hasReadOnlySelection)

        view.actionMode.finish()
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
        assertTrue(toolbar.hasReadOnlySelection)

        toolbar.hide()
        assertFalse(toolbar.hasReadOnlySelection)

        toolbar.showMenu(Rect.Zero, {}, {}, {}, {})
        assertFalse(toolbar.hasReadOnlySelection)
        toolbar.hide()
        activity.finish()
    }
}
