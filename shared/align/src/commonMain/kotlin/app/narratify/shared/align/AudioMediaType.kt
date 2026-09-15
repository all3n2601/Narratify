package app.narratify.shared.align

/**
 * The EPUB3 core media types for audio.
 *
 * An `.m4b` is refused despite usually containing exactly the AAC-in-MP4 that `audio/mp4` names,
 * because the extension is not one a reading system is required to accept. The fix is a stream
 * copy, not a re-encode, so the refusal says so rather than leaving the caller to guess.
 */
object AudioMediaType {
    private val byExtension = mapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "mp4" to "audio/mp4",
        "aac" to "audio/mp4",
    )

    fun forHref(href: String): String? =
        byExtension[href.substringAfterLast('.', missingDelimiterValue = "").lowercase()]

    fun explainRefusal(href: String): String {
        val extension = href.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (extension == "m4b") {
            "$href is an M4B. Its audio is usually already acceptable, so remux rather than " +
                "re-encode it: ffmpeg -i $href -c copy -map 0:a ${href.dropLast(1)}a"
        } else {
            "$href is not an EPUB3 core audio media type. Convert it to MP3 or AAC in MP4."
        }
    }
}
