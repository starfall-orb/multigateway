package org.starfall.multigateway.ui.components

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.roundToInt

/** Keep selection in Compose while letting Android render and populate its floating toolbar. */
@Composable
fun PlatformTextSelection(content: @Composable () -> Unit) {
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val selectionClipboard = remember(clipboard) { SelectionClipboard(clipboard) }
    val toolbar = remember(view, selectionClipboard) { PlatformSelectionToolbar(view, selectionClipboard) }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }
    CompositionLocalProvider(LocalClipboardManager provides selectionClipboard, LocalTextToolbar provides toolbar,
        content = content)
}

internal class SelectionClipboard(private val delegate: ClipboardManager) : ClipboardManager by delegate {
    private var capturing = false
    private var selection: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        if (capturing) selection = annotatedString else delegate.setText(annotatedString)
    }

    fun selectedText(copy: () -> Unit): String? {
        selection = null
        capturing = true
        try { copy() } finally { capturing = false }
        return selection?.text
    }
}

internal class PlatformSelectionToolbar(
    private val view: View,
    private val clipboard: SelectionClipboard
) : TextToolbar {
    private var mode: ActionMode? = null
    private var rect = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var cut: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null
    override val status: TextToolbarStatus
        get() = if (mode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect
        copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; selectAll = onSelectAllRequested
        if (mode == null) mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        else { mode?.invalidate(); mode?.invalidateContentRect() }
    }

    override fun hide() { mode?.finish(); mode = null }

    private val callback = object : ActionMode.Callback2() {
        private val processActions = mutableMapOf<Int, ComponentName>()
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = populate(menu)
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = populate(menu)
        private fun populate(menu: Menu): Boolean {
            menu.clear()
            processActions.clear()
            fun action(id: Int, title: String, enabled: Boolean) {
                if (enabled) menu.add(Menu.NONE, id, Menu.NONE, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            action(android.R.id.cut, view.context.getString(android.R.string.cut), cut != null)
            action(android.R.id.copy, view.context.getString(android.R.string.copy), copy != null)
            action(android.R.id.paste, view.context.getString(android.R.string.paste), paste != null)
            action(android.R.id.selectAll, view.context.getString(android.R.string.selectAll), selectAll != null)
            action(SHARE, "Share", copy != null)
            if (copy != null) {
                val manager = view.context.packageManager
                manager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
                    .filter { resolve ->
                        val activity = resolve.activityInfo
                        activity.packageName == view.context.packageName ||
                            (activity.exported && (activity.permission == null ||
                                view.context.checkSelfPermission(activity.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED))
                    }.forEachIndexed { index, resolve ->
                        val id = PROCESS_BASE + index
                        processActions[id] = ComponentName(resolve.activityInfo.packageName, resolve.activityInfo.name)
                        menu.add(Menu.NONE, id, Menu.NONE, resolve.loadLabel(manager)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    }
            }
            return menu.size() > 0
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = when (item.itemId) {
                android.R.id.copy -> copy
                android.R.id.cut -> cut
                android.R.id.paste -> paste
                android.R.id.selectAll -> selectAll
                else -> null
            }
            if (action != null) {
                action()
                if (item.itemId != android.R.id.selectAll) mode.finish()
                return true
            }
            val component = processActions[item.itemId]
            if (item.itemId == SHARE || component != null) {
                val text = copy?.let(clipboard::selectedText) ?: return false
                val intent = if (component != null) {
                    Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                } else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "Share text")
                runCatching { view.context.startActivity(intent) }
                mode.finish()
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) { if (this@PlatformSelectionToolbar.mode === mode) this@PlatformSelectionToolbar.mode = null }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        }
    }

    companion object { private const val SHARE = 1001; private const val PROCESS_BASE = 2000 }
}
