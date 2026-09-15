package app.narratify

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LicenseNoticesTest {
    private val bundled = mapOf(
        "third_party/KOKORO_NOTICE.txt" to "kokoro notice",
        "third_party/KOKORO_APACHE_2_0_LICENSE.txt" to "apache 2.0",
        "third_party/ONNX_RUNTIME_LICENSE.txt" to "onnx mit",
        "third_party/ONNX_RUNTIME_THIRD_PARTY_NOTICES.txt" to "onnx third party",
    )

    @Test
    fun `every bundled notice is listed and readable without an installed pack`() {
        val documents = NarratifyLicenseNotices.documents({ bundled.getValue(it) }, packDirectory = null)

        assertEquals(bundled.size, documents.size)
        assertEquals(bundled.values.toSet(), documents.map { it.text() }.toSet())
    }

    @Test
    fun `the pack license is listed only while the verified pack is installed`() {
        val directory = Files.createTempDirectory("pack").toFile()
        assertTrue(NarratifyLicenseNotices.documents({ bundled.getValue(it) }, directory).none { it.title.contains("CMU") })

        File(directory, "LICENSE.cmudict.txt").writeText("cmudict bsd license")
        val documents = NarratifyLicenseNotices.documents({ bundled.getValue(it) }, directory)

        val cmudict = documents.single { it.title.contains("CMU") }
        assertEquals("cmudict bsd license", cmudict.text())
        directory.deleteRecursively()
    }

    @Test
    fun `an unreadable notice reports the failure instead of crashing the reader`() {
        val documents = NarratifyLicenseNotices.documents({ error("asset missing") }, packDirectory = null)

        assertTrue(documents.all { it.text().contains("could not be read") })
    }
}
