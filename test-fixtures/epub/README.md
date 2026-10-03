# EPUB smoke fixture

`narratify-smoke.epub` is a small, unencrypted EPUB 3 book authored for Narratify import, rendering, and system-narration tests. It contains no third-party book content. Its text and EPUB packaging are dedicated to the public domain under CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/.

The iOS test target bundles this directory so tests do not depend on a package checkout or developer-specific build path. You can also import the EPUB manually on either platform.
