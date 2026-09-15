package app.narratify

import android.graphics.Color

/**
 * One palette for the whole app. The reading theme used to repaint only the book page, which left
 * a dark reader sitting inside a cream library, so every screen resolves its colours from here.
 *
 * The palette is deliberately narrow and warm: paper stocks rather than greys, a single printed-ink
 * accent rather than a UI purple, and a hairline [rule] that carries the separation work drop
 * shadows used to do badly. [feature] is the one inverted block a screen is allowed — the
 * "continue reading" slot — and it follows the theme instead of being hard-coded dark.
 */
data class AppPalette(
    val canvas: Int,
    val surface: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val outline: Int,
    val subtle: Int,
    val highlight: Int,
    val danger: Int,
    val rule: Int,
    val feature: Int,
    val onFeature: Int,
    val featureMuted: Int,
    val dark: Boolean,
) {
    /** Page background for the text reader; the surrounding chrome uses [surface]. */
    val readerBackground: Int get() = canvas
}

/** Printed-ink red. Used at full strength on light stock and warmed up for night reading. */
private const val INK_RED = 0xFF8A3324.toInt()
private const val INK_RED_NIGHT = 0xFFE3866B.toInt()

fun appPalette(theme: ReaderTheme): AppPalette = when (theme) {
    // Bright paper stock: near-white, slightly warm, so covers and ink both sit on it cleanly.
    ReaderTheme.LIGHT -> AppPalette(
        canvas = 0xFFFBF8F2.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        ink = 0xFF17140F.toInt(),
        muted = 0xFF6B6156.toInt(),
        accent = INK_RED,
        onAccent = Color.WHITE,
        outline = 0x2617140F,
        subtle = 0x0F8A3324,
        highlight = 0x59E8C97A,
        danger = 0xFFA31D1D.toInt(),
        rule = 0x1F17140F,
        feature = 0xFF201B15.toInt(),
        onFeature = 0xFFF7F1E6.toInt(),
        featureMuted = 0xFFB9AE9C.toInt(),
        dark = false,
    )

    // Uncoated cream stock. Warmer than LIGHT without tipping into the old muddy beige.
    ReaderTheme.SEPIA -> AppPalette(
        canvas = 0xFFF4EADA.toInt(),
        surface = 0xFFFBF4E8.toInt(),
        ink = 0xFF231B12.toInt(),
        muted = 0xFF6A5B47.toInt(),
        accent = INK_RED,
        onAccent = Color.WHITE,
        outline = 0x26231B12,
        subtle = 0x148A3324,
        highlight = 0x59E0BE6C,
        danger = 0xFFA31D1D.toInt(),
        rule = 0x24231B12,
        feature = 0xFF2A2118.toInt(),
        onFeature = 0xFFF6EEDF.toInt(),
        featureMuted = 0xFFBBAE99.toInt(),
        dark = false,
    )

    // Night reading: warm near-black rather than the blue-grey the app used to fall back to.
    ReaderTheme.DARK -> AppPalette(
        canvas = 0xFF16130F.toInt(),
        surface = 0xFF211C17.toInt(),
        ink = 0xFFF0E9DE.toInt(),
        muted = 0xFFA89C8C.toInt(),
        accent = INK_RED_NIGHT,
        onAccent = 0xFF2A1009.toInt(),
        outline = 0x33F0E9DE,
        subtle = 0x1FE3866B,
        highlight = 0x597A5A2E,
        danger = 0xFFF0A9A0.toInt(),
        rule = 0x24F0E9DE,
        feature = 0xFF2C251D.toInt(),
        onFeature = 0xFFF3ECE1.toInt(),
        featureMuted = 0xFFAFA294.toInt(),
        dark = true,
    )

    // OLED. Pure black canvas, with the surface lifted just enough to stay a readable card.
    ReaderTheme.BLACK -> AppPalette(
        canvas = Color.BLACK,
        surface = 0xFF0D0B09.toInt(),
        ink = 0xFFF5EFE5.toInt(),
        muted = 0xFFA79B8B.toInt(),
        accent = INK_RED_NIGHT,
        onAccent = 0xFF2A1009.toInt(),
        outline = 0x33F5EFE5,
        subtle = 0x1FE3866B,
        highlight = 0x596B4E27,
        danger = 0xFFF0A9A0.toInt(),
        rule = 0x26F5EFE5,
        feature = 0xFF17130F.toInt(),
        onFeature = 0xFFF5EFE5.toInt(),
        featureMuted = 0xFFA79B8B.toInt(),
        dark = true,
    )
}

fun AppPreferences.palette(): AppPalette = appPalette(readerTheme)

/**
 * The library is an immersive threshold into the reader, so its chrome stays nocturnal while the
 * actual reading page continues to honour Light, Sepia, Dark, and True Black independently.
 */
fun enchantedLibraryPalette(): AppPalette = AppPalette(
    canvas = 0xFF031410.toInt(),
    surface = 0xD9102D28.toInt(),
    ink = 0xFFFFF7E6.toInt(),
    muted = 0xFFC0CDC5.toInt(),
    accent = 0xFFA8DCB0.toInt(),
    onAccent = 0xFF0C271F.toInt(),
    outline = 0x4D9ED2B1,
    subtle = 0x267FD19A,
    highlight = 0x66FFE299,
    danger = 0xFFFFAAA2.toInt(),
    rule = 0x339ED2B1,
    feature = 0xE00A2823.toInt(),
    onFeature = 0xFFFFF7E6.toInt(),
    featureMuted = 0xFFBDD0C6.toInt(),
    dark = true,
)

/**
 * Paints the window itself, so the status bar, navigation bar, and any gap during a screen swap
 * match the chosen theme instead of flashing the default background.
 */
fun android.app.Activity.applyWindowPalette(palette: AppPalette) {
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.canvas))
    window.statusBarColor = palette.canvas
    window.navigationBarColor = palette.canvas
    // The decor view only exists once content is set; asking for it earlier crashes the launch.
    if (window.peekDecorView() == null) return
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.dark) 0 else bars, bars)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (palette.dark) {
            0
        } else {
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}

/**
 * Gives a reader the whole display while keeping Android's transient edge-swipe bars available.
 * Restoring also reapplies light/dark system-bar icons because legacy immersive flags replace them.
 */
fun android.app.Activity.setReaderFullscreen(enabled: Boolean, palette: AppPalette) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(!enabled)
        window.insetsController?.let { controller ->
            if (enabled) {
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars())
            }
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) {
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            0
        }
    }
    if (!enabled) applyWindowPalette(palette)
}
