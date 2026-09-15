package app.narratify.shared.align

import java.text.Normalizer

internal actual fun String.canonicallyComposed(): String =
    if (Normalizer.isNormalized(this, Normalizer.Form.NFC)) this
    else Normalizer.normalize(this, Normalizer.Form.NFC)
