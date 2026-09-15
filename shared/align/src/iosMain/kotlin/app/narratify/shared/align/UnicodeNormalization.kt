package app.narratify.shared.align

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

internal actual fun String.canonicallyComposed(): String =
    // Kotlin/Native's type checker does not model the toll-free bridge between `kotlin.String` and
    // `NSString`; the cast succeeds at runtime because they are the same object.
    @Suppress("CAST_NEVER_SUCCEEDS")
    (this as NSString).precomposedStringWithCanonicalMapping
