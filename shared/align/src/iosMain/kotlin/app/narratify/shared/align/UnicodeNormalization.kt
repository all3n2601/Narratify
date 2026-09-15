package app.narratify.shared.align

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

internal actual fun String.canonicallyComposed(): String =
    (this as NSString).precomposedStringWithCanonicalMapping
