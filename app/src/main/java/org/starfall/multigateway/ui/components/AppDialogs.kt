package org.starfall.multigateway.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.starfall.multigateway.ui.theme.AmoledModalScrim
import org.starfall.multigateway.ui.theme.LocalAmoledMode
import org.starfall.multigateway.ui.theme.LocalDarkMode
import java.util.IdentityHashMap

/** One tint on the activity background, even when a dialog is opened from a sheet. */
private class DialogBackdrop(val drawable: ColorDrawable, val listener: View.OnLayoutChangeListener, var users: Int = 0)
private val dialogBackdrops = IdentityHashMap<View, DialogBackdrop>()

internal fun amoledDialogBackdropHost(view: View): View {
    var context = view.context
    while (true) {
        if (context is Activity) return context.window.decorView
        if (context !is ContextWrapper || context.baseContext === context) return view.rootView
        context = context.baseContext
    }
}

internal fun acquireAmoledDialogBackdrop(host: View): () -> Unit {
    val backdrop = dialogBackdrops[host] ?: run {
        val drawable = ColorDrawable(AmoledModalScrim.copy(alpha = 0.32f).toArgb())
        drawable.setBounds(0, 0, host.width, host.height)
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, view.width, view.height)
        }
        host.addOnLayoutChangeListener(listener)
        host.overlay.add(drawable)
        DialogBackdrop(drawable, listener).also { dialogBackdrops[host] = it }
    }
    backdrop.users++
    var released = false
    return {
        if (!released) {
            released = true
            if (--backdrop.users == 0) {
                host.overlay.remove(backdrop.drawable)
                host.removeOnLayoutChangeListener(backdrop.listener)
                dialogBackdrops.remove(host)
            }
        }
    }
}

@Composable
private fun AmoledDialogBackdrop() {
    val dark = LocalDarkMode.current
    val amoled = LocalAmoledMode.current
    // The composition can live inside a sheet/dialog window. Its root overlay would
    // tint that foreground surface too; always put the tint on the activity behind it.
    val host = amoledDialogBackdropHost(LocalView.current)
    DisposableEffect(host, dark, amoled) {
        // AMOLED uses the platform's black dim layer: empty areas stay #000000
        // while content already drawn behind the dialog is dimmed naturally.
        val release = if (dark && !amoled) acquireAmoledDialogBackdrop(host) else ({})
        onDispose { release() }
    }
}

@Composable
private fun ConfigureAmoledDialogWindow() {
    val dark = LocalDarkMode.current
    val amoled = LocalAmoledMode.current
    var view: View? = LocalView.current
    var window: android.view.Window? = null
    while (view != null && window == null) {
        if (view is DialogWindowProvider) window = view.window else view = view.parent as? View
    }
    DisposableEffect(window, dark, amoled) {
        val target = window
        val oldDimAmount = target?.attributes?.dimAmount
        // The activity backdrop supplies the theme-aware dim color. Disable the
        // platform black dim layer so dark mode does not turn the background pure black.
        if (dark && !amoled) target?.setDimAmount(0f)
        onDispose { if (dark && !amoled && oldDimAmount != null) target.setDimAmount(oldDimAmount) }
    }
}

@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    AmoledDialogBackdrop()
    androidx.compose.ui.window.Dialog(onDismissRequest, properties = properties) {
        ConfigureAmoledDialogWindow()
        Box(Modifier.safeDrawingPadding().imePadding()) {
            content()
        }
    }
}

@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    AmoledDialogBackdrop()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ConfigureAmoledDialogWindow(); confirmButton() },
        modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties
    )
}
