package app.narratify

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** One legal text Narratify redistributes and must be able to show the reader. */
class LicenseNotice(
    val title: String,
    val detail: String,
    private val read: () -> String,
) {
    fun text(): String = runCatching(read).getOrElse { "This notice could not be read on this device." }
}

/**
 * Apache-2.0 section 4(d) and CMUdict's BSD-style terms require the shipped notices to be
 * reachable by the reader, not merely present on disk. Bundled texts come from the APK; the
 * dictionary license is read from the verified pack directory, so it is listed only while that
 * pack is installed.
 */
object NarratifyLicenseNotices {
    private const val BUNDLED = "third_party"
    private const val PACK_LICENSE = "LICENSE.cmudict.txt"

    fun documents(context: Context, packDirectory: File? = null): List<LicenseNotice> =
        documents({ path -> context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }, packDirectory)

    internal fun documents(readAsset: (String) -> String, packDirectory: File?): List<LicenseNotice> = buildList {
        add(LicenseNotice("Kokoro-82M", "Model attribution and sources") { readAsset("$BUNDLED/KOKORO_NOTICE.txt") })
        add(LicenseNotice("Kokoro-82M · Apache License 2.0", "Full license text") { readAsset("$BUNDLED/KOKORO_APACHE_2_0_LICENSE.txt") })
        add(LicenseNotice("ONNX Runtime · MIT License", "Speech runtime license") { readAsset("$BUNDLED/ONNX_RUNTIME_LICENSE.txt") })
        add(LicenseNotice("ONNX Runtime · third-party notices", "Components inside the runtime") { readAsset("$BUNDLED/ONNX_RUNTIME_THIRD_PARTY_NOTICES.txt") })
        val packLicense = packDirectory?.let { File(it, PACK_LICENSE) }?.takeIf(File::isFile)
        if (packLicense != null) {
            add(LicenseNotice("CMU Pronouncing Dictionary", "BSD-style license · installed with the voice pack") {
                packLicense.readText(Charsets.UTF_8)
            })
        }
    }
}

fun showLicenseNotices(context: Context, packDirectory: File? = null) {
    val documents = NarratifyLicenseNotices.documents(context, packDirectory)
    AlertDialog.Builder(context)
        .setTitle("Licenses & notices")
        .setItems(documents.map { "${it.title}\n${it.detail}" }.toTypedArray()) { _, index ->
            showLicenseNotice(context, documents[index], packDirectory)
        }
        .setPositiveButton("Close", null)
        .show()
}

private fun showLicenseNotice(context: Context, notice: LicenseNotice, packDirectory: File?) {
    val density = context.resources.displayMetrics.density
    val padding = (20 * density).toInt()
    val body = TextView(context).apply {
        text = notice.text()
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(padding, padding / 2, padding, padding / 2)
    }
    AlertDialog.Builder(context)
        .setTitle(notice.title)
        .setView(ScrollView(context).apply { addView(body) })
        .setPositiveButton("Close", null)
        .setNeutralButton("Back") { _, _ -> showLicenseNotices(context, packDirectory) }
        .show()
}
