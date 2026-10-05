package org.starfall.multigateway.ui.components

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.compose.material3.AlertDialogDefaults
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
    val amoled = LocalAmoledMode.current
    // The composition can live inside a sheet/dialog window. Its root overlay would
    // tint that foreground surface too; always put the tint on the activity behind it.
    val host = amoledDialogBackdropHost(LocalView.current)
    DisposableEffect(host, amoled) {
        val release = if (amoled) acquireAmoledDialogBackdrop(host) else ({})
        onDispose { release() }
    }
}

@Composable
private fun ConfigureAmoledDialogWindow() {
    val amoled = LocalAmoledMode.current
    var view: View? = LocalView.current
    var window: android.view.Window? = null
    while (view != null && window == null) {
        if (view is DialogWindowProvider) window = view.window else view = view.parent as? View
    }
    DisposableEffect(window, amoled) {
        val target = window
        val oldDimAmount = target?.attributes?.dimAmount
        if (amoled) target?.setDimAmount(0f)
        onDispose { if (amoled && oldDimAmount != null) target?.setDimAmount(oldDimAmount) }
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
        content()
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
