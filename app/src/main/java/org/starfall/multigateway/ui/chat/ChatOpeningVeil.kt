package org.starfall.multigateway.ui.chat

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect

internal const val OPENING_VEIL_TAG = "chat-opening-veil"

private const val VEIL_FADE_OUT_MS = 520
private const val BLUR_RADIUS_DP = 14f
private const val RING_COUNT = 3

/**
 * Hides [content] behind a blurred, rippling "water" layer while [active] is true and fades it away
 * when [active] turns false. The content is still composed, measured and scrolled underneath, so the
 * chat can settle at its newest message without the person watching it jump around.
 *
 * Android 13+ distorts and blurs the content with an AGSL shader, Android 12 only blurs it, and older
 * versions (which have no blur) get a stronger frosted scrim. Concentric rings are drawn on all of them.
 */
@Composable
internal fun ChatOpeningVeil(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // The veil must be fully on in the very frame it is requested (an animated rise would show the
    // unsettled chat for a frame or two); only the fade-out is animated.
    val fadeState = animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = if (active) snap() else tween(VEIL_FADE_OUT_MS, easing = FastOutSlowInEasing),
        label = "chatOpeningVeil"
    )
    val fading by remember { derivedStateOf { fadeState.value > 0.001f } }
    val visible = active || fading
    val amount = { if (active) 1f else fadeState.value }
    val secondsState = remember { mutableFloatStateOf(0f) }
    // The clock only runs while the veil is on screen, so an idle chat has no per-frame work.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            secondsState.floatValue = (androidx.compose.runtime.withFrameNanos { it } - start) / 1_000_000_000f
        }
    }

    val scrim = MaterialTheme.colorScheme.background
    val ring = MaterialTheme.colorScheme.primary
    val scrimAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.30f else 0.82f
    val effect = if (visible) {
        rippleBlurLayer(amount = amount, seconds = { secondsState.floatValue })
    } else {
        Modifier
    }

    Box(modifier) {
        Box(Modifier.fillMaxSize().then(effect), content = content)
        if (visible) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .testTag(OPENING_VEIL_TAG)
                    .then(
                        // Touches are swallowed only while waiting, so a fade-out never feels stuck.
                        if (active) Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        } else Modifier
                    )
            ) {
                drawVeil(amount(), secondsState.floatValue, scrim, ring, scrimAlpha)
            }
        }
    }
}

private fun DrawScope.drawVeil(amount: Float, seconds: Float, scrim: Color, ring: Color, scrimAlpha: Float) {
    drawRect(scrim.copy(alpha = (scrimAlpha * amount).coerceIn(0f, 1f)))
    val center = Offset(size.width * RIPPLE_CENTER_X, size.height * RIPPLE_CENTER_Y)
    val reach = hypot(size.width, size.height) * 0.55f
    val baseStroke = 1.5f.dp.toPx()
    val growth = 4.dp.toPx()
    repeat(RING_COUNT) { index ->
        val phase = (seconds * 0.42f + index / RING_COUNT.toFloat()) % 1f
        val eased = 1f - (1f - phase) * (1f - phase)
        drawCircle(
            color = ring.copy(alpha = ((1f - phase) * 0.34f * amount).coerceIn(0f, 1f)),
            radius = eased * reach,
            center = center,
            style = Stroke(width = baseStroke + growth * phase)
        )
    }
}

private const val RIPPLE_CENTER_X = 0.5f
private const val RIPPLE_CENTER_Y = 0.62f

@Composable
private fun rippleBlurLayer(amount: () -> Float, seconds: () -> Float): Modifier {
    val density = LocalDensity.current.density
    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RippleShaderApi33.create() else null }
    return Modifier.graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && size.width > 0f && size.height > 0f) {
            renderEffect = RippleEffectApi31.build(shader, size.width, size.height, density, amount(), seconds())
        }
        clip = true
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object RippleEffectApi31 {
    fun build(
        shader: Any?,
        width: Float,
        height: Float,
        density: Float,
        amount: Float,
        seconds: Float
    ): ComposeRenderEffect {
        val radius = (BLUR_RADIUS_DP * density * amount).coerceAtLeast(0.5f)
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            RippleShaderApi33.chain(shader, blur, width, height, density, amount, seconds)
        } else {
            blur
        }
        return effect.asComposeRenderEffect()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object RippleShaderApi33 {
    // The content is blurred first (the inner effect) and then distorted by waves travelling out
    // from the point where the newest messages appear, plus a slow shimmer over the whole surface.
    private const val AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float time;
        uniform float amount;
        uniform float density;

        half4 main(float2 coord) {
            float2 center = size * float2($RIPPLE_CENTER_X, $RIPPLE_CENTER_Y);
            float2 delta = coord - center;
            float dist = length(delta);
            float2 dir = delta / max(dist, 1.0);
            float reach = length(size);
            float wave = sin(dist / reach * 62.0 - time * 3.4);
            float falloff = exp(-dist / reach * 2.4);
            float2 shimmer = float2(
                sin(coord.y / size.y * 17.0 + time * 1.7),
                cos(coord.x / size.x * 13.0 + time * 1.3)
            );
            float2 offset = (dir * wave * falloff * 9.0 + shimmer * 3.0) * density * amount;
            return content.eval(coord + offset);
        }
    """

    fun create(): Any = RuntimeShader(AGSL)

    fun chain(
        shader: Any,
        inner: RenderEffect,
        width: Float,
        height: Float,
        density: Float,
        amount: Float,
        seconds: Float
    ): RenderEffect {
        val runtime = shader as RuntimeShader
        runtime.setFloatUniform("size", width, height)
        runtime.setFloatUniform("time", seconds)
        runtime.setFloatUniform("amount", amount)
        runtime.setFloatUniform("density", density)
        val distortion = RenderEffect.createRuntimeShaderEffect(runtime, "content")
        return RenderEffect.createChainEffect(distortion, inner)
    }
}
