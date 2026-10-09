package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.starfall.multigateway.ui.chat.LatexCacheKey
import org.starfall.multigateway.ui.chat.latexCacheKey
import org.starfall.multigateway.ui.chat.latexFallbackSource

class LatexFormulaRendererTest {
    @Test fun cacheKeyRoundsPixelSizeAndIncludesThemeInputs() {
        val light = latexCacheKey("x^2", 18.4f, 0xff112233.toInt(), false)
        val same = latexCacheKey("x^2", 18.49f, 0xff112233.toInt(), false)
        val dark = latexCacheKey("x^2", 18.4f, 0xff112233.toInt(), true)

        assertEquals(LatexCacheKey("x^2", 18, 0xff112233.toInt(), false), light)
        assertEquals(light, same)
        assertNotEquals(light, dark)
    }

    @Test fun rendererFallbackPreservesRawSource() {
        val unsupported = "\\unsupported{formula}"

        assertEquals(unsupported, latexFallbackSource(unsupported))
    }
}
