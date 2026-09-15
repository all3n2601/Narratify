package app.narratify

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class AppPaletteTest {
    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xff) +
            0.7152 * channel((color shr 8) and 0xff) +
            0.0722 * channel(color and 0xff)

    private fun contrast(foreground: Int, background: Int): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `body text clears the 4_5 to 1 contrast floor on every theme`() {
        ReaderTheme.entries.forEach { theme ->
            val palette = appPalette(theme)
            listOf(
                "ink on canvas" to contrast(palette.ink, palette.canvas),
                "ink on surface" to contrast(palette.ink, palette.surface),
            ).forEach { (what, ratio) ->
                assertTrue(ratio >= 4.5, "$theme $what is only ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test
    fun `secondary text and accents stay legible on both surfaces`() {
        ReaderTheme.entries.forEach { theme ->
            val palette = appPalette(theme)
            listOf(
                "muted on canvas" to contrast(palette.muted, palette.canvas),
                "muted on surface" to contrast(palette.muted, palette.surface),
                "accent on surface" to contrast(palette.accent, palette.surface),
                "onAccent on accent" to contrast(palette.onAccent, palette.accent),
            ).forEach { (what, ratio) ->
                assertTrue(ratio >= 3.0, "$theme $what is only ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test
    fun `the inverted feature block stays readable on every theme`() {
        ReaderTheme.entries.forEach { theme ->
            val palette = appPalette(theme)
            assertTrue(
                contrast(palette.onFeature, palette.feature) >= 4.5,
                "$theme onFeature on feature is only ${"%.2f".format(contrast(palette.onFeature, palette.feature))}:1",
            )
            assertTrue(
                contrast(palette.featureMuted, palette.feature) >= 3.0,
                "$theme featureMuted on feature is only ${"%.2f".format(contrast(palette.featureMuted, palette.feature))}:1",
            )
        }
    }

    /**
     * The rule is the app's main separator now, so a theme that renders it invisible would flatten
     * every screen. It is deliberately translucent, so this checks the alpha rather than contrast.
     */
    @Test
    fun `the hairline rule is visible but never a hard border on any theme`() {
        ReaderTheme.entries.forEach { theme ->
            val alpha = (appPalette(theme).rule ushr 24) and 0xff
            assertTrue(alpha in 20..80, "$theme rule alpha is $alpha, outside the hairline range")
        }
    }

    @Test
    fun `dark themes are marked dark so charts and system bars can adapt`() {
        assertEquals(setOf(ReaderTheme.DARK, ReaderTheme.BLACK), ReaderTheme.entries.filter { appPalette(it).dark }.toSet())
    }

    @Test
    fun `each theme is visually distinct from the others`() {
        val canvases = ReaderTheme.entries.map { appPalette(it).canvas }

        assertEquals(ReaderTheme.entries.size, canvases.toSet().size)
    }
}
