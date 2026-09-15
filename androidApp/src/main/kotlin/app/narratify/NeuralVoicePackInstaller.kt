package app.narratify

import android.content.Context
import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class NeuralPackReleaseStatus {
    AWAITING_LICENSE_APPROVAL,
    AVAILABLE,
}

data class NeuralPackDownloadAsset(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun modelAsset() = NeuralModelAsset(relativePath, sizeBytes, sha256)
}

data class NeuralVoicePackCatalogEntry(
    val displayName: String,
    val detail: String,
    val schemaVersion: Int,
    val kind: NeuralPackKind,
    val packId: String,
    val packVersion: String,
    val runtimeId: String,
    val modelId: String,
    val modelVersion: String,
    val voiceId: String,
    val voiceVersion: String,
    val languageTags: Set<String>,
    val licenseSpdxId: String,
    val attribution: String,
    val pcmFormat: PcmFormat,
    val assets: List<NeuralPackDownloadAsset>,
    val releaseStatus: NeuralPackReleaseStatus,
    /** True only in an app build that contains the matching reviewed native runtime/frontend. */
    val runtimeBundled: Boolean,
    /** This record must be added by a release after human legal review. */
    val approval: ApprovedNeuralVoicePack? = null,
    /** The model pack this voice needs. Null for a model pack itself. */
    val dependency: NeuralVoicePackCatalogEntry? = null,
) {
    /** This pack's own assets only. See [downloadBytes] for what a reader would actually download. */
    val sizeBytes: Long get() = assets.sumOf { it.sizeBytes }

    /**
     * What installing this pack would download, given whether the shared model pack is already
     * present. Pure arithmetic, so the Voices screen can size a card on the main thread;
     * [NeuralVoicePackInstaller.pendingDownloadBytes] answers the same question authoritatively
     * but has to digest every installed asset to do it.
     */
    fun downloadBytes(dependencyInstalled: Boolean): Long =
        sizeBytes + if (dependencyInstalled) 0L else dependency?.sizeBytes ?: 0L
    val dependsOn: NeuralPackRef? get() = dependency?.let { NeuralPackRef(it.packId, it.packVersion) }
    val canDownload: Boolean
        get() = releaseStatus == NeuralPackReleaseStatus.AVAILABLE && runtimeBundled && approval != null &&
            (dependency == null || dependency.canDownload)

    fun asPack(directory: File) = NeuralVoicePack(
        directory = directory,
        schemaVersion = schemaVersion,
        kind = kind,
        packId = packId,
        packVersion = packVersion,
        runtimeId = runtimeId,
        modelId = modelId,
        modelVersion = modelVersion,
        voiceId = voiceId,
        voiceVersion = voiceVersion,
        languageTags = languageTags,
        licenseSpdxId = licenseSpdxId,
        commercialUseAllowed = canDownload,
        attribution = attribution,
        pcmFormat = pcmFormat,
        assets = assets.map(NeuralPackDownloadAsset::modelAsset),
        dependsOn = dependsOn,
    )
}

/**
 * The 325 MB graph and the CMU dictionary live in one model pack; each voice is a ~510 KB style
 * that names it. A voice ships locked until the project owner authorizes its exact artifact,
 * digest, and documented synthetic identity in benchmarks/tts/license-inventory.pending.json;
 * all six English voices were authorized on 2026-09-10, so each carries a compiled approval.
 */
object NarratifyNeuralVoiceCatalog {
    private const val VOICE_COMMIT = "1939ad2a8e416c0acfeecc08a694d14ef25f2231"
    private const val ATTRIBUTION =
        "Kokoro-82M by hexgrad (Apache-2.0). CMUdict Copyright (C) 1993-2015 Carnegie Mellon University (BSD-style license included in the model pack)."

    private val modelAssets = listOf(
        NeuralPackDownloadAsset(
            relativePath = "kokoro-v1.0.onnx",
            url = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx",
            sizeBytes = 325_505_369,
            sha256 = "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
        ),
        NeuralPackDownloadAsset(
            relativePath = "cmudict.dict",
            url = "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/cmudict.dict",
            sizeBytes = 3_618_488,
            sha256 = "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22",
        ),
        NeuralPackDownloadAsset(
            relativePath = "LICENSE.cmudict.txt",
            url = "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/LICENSE",
            sizeBytes = 1_754,
            sha256 = "bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a",
        ),
    )

    val kokoroModel = NeuralVoicePackCatalogEntry(
        displayName = "Kokoro English · shared model",
        detail = "The speech model and pronunciation dictionary every Kokoro voice uses. Downloaded once.",
        schemaVersion = 2,
        kind = NeuralPackKind.MODEL,
        packId = "app.narratify.kokoro.model",
        packVersion = "1.0.0",
        runtimeId = "narratify-kokoro-onnx",
        modelId = "kokoro-82m-v1.0-fp32-duration",
        modelVersion = "model-files-v1.1",
        voiceId = "",
        voiceVersion = "",
        languageTags = setOf("en-US"),
        licenseSpdxId = "Apache-2.0",
        attribution = ATTRIBUTION,
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = modelAssets,
        releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        runtimeBundled = true,
        approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.kokoro.model",
            packVersion = "1.0.0",
            kind = NeuralPackKind.MODEL,
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = "",
            voiceVersion = "",
            licenseSpdxId = "Apache-2.0",
            assets = modelAssets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
        ),
    )

    /**
     * Order is the display order and the Automatic preference order: highest overall grade in
     * hexgrad/Kokoro-82M VOICES.md first.
     */
    val kokoroVoices = listOf(
        voice(
            name = "heart",
            voiceId = "af_heart",
            displayName = "Kokoro English · Heart",
            detail = "American English, warm and even. Kokoro's highest-graded voice (A).",
            sha256 = "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "bella",
            voiceId = "af_bella",
            displayName = "Kokoro English · Bella",
            detail = "American English, expressive. Kokoro's most-trained voice (A-).",
            sha256 = "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "nicole",
            voiceId = "af_nicole",
            displayName = "Kokoro English · Nicole",
            detail = "American English, soft and close-miked (B-).",
            sha256 = "cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "michael",
            voiceId = "am_michael",
            displayName = "Kokoro English · Michael",
            detail = "American English, steady male narration (C+).",
            sha256 = "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "fenrir",
            voiceId = "am_fenrir",
            displayName = "Kokoro English · Fenrir",
            detail = "American English, deeper male narration (C+).",
            sha256 = "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
        voice(
            name = "puck",
            voiceId = "am_puck",
            displayName = "Kokoro English · Puck",
            detail = "American English, brighter male narration (C+).",
            sha256 = "fcf73c989033e9233e0b98713eca600c8c74dcc1614b37009d5450ff4a2274a0",
            releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        ),
    )

    val all: List<NeuralVoicePackCatalogEntry> get() = listOf(kokoroModel) + kokoroVoices

    /** Lower is preferred. Used only to pick a voice for [VoiceSelection.Automatic]. */
    fun preferenceRank(packId: String): Int =
        kokoroVoices.indexOfFirst { it.packId == packId }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    private fun voice(
        name: String,
        voiceId: String,
        displayName: String,
        detail: String,
        sha256: String,
        releaseStatus: NeuralPackReleaseStatus,
    ): NeuralVoicePackCatalogEntry {
        val packId = "app.narratify.kokoro.en-us.$name"
        val voiceVersion = "${voiceId.replace('_', '-')}-1939ad2"
        val assets = listOf(
            NeuralPackDownloadAsset(
                relativePath = "$voiceId.bin",
                url = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/$VOICE_COMMIT/voices/$voiceId.bin",
                sizeBytes = 522_240,
                sha256 = sha256,
            ),
        )
        val approved = releaseStatus == NeuralPackReleaseStatus.AVAILABLE
        return NeuralVoicePackCatalogEntry(
            displayName = displayName,
            detail = detail,
            schemaVersion = 2,
            kind = NeuralPackKind.VOICE,
            packId = packId,
            packVersion = "1.0.0",
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = voiceId,
            voiceVersion = voiceVersion,
            languageTags = setOf("en-US"),
            licenseSpdxId = "Apache-2.0",
            attribution = ATTRIBUTION,
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = assets,
            releaseStatus = releaseStatus,
            runtimeBundled = true,
            approval = if (!approved) null else ApprovedNeuralVoicePack(
                packId = packId,
                packVersion = "1.0.0",
                kind = NeuralPackKind.VOICE,
                runtimeId = "narratify-kokoro-onnx",
                modelId = "kokoro-82m-v1.0-fp32-duration",
                modelVersion = "model-files-v1.1",
                voiceId = voiceId,
                voiceVersion = voiceVersion,
                licenseSpdxId = "Apache-2.0",
                assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
                dependsOn = NeuralPackRef(kokoroModel.packId, kokoroModel.packVersion),
            ),
            dependency = kokoroModel,
        )
    }
}

class NeuralVoicePackInstallException(message: String) : IllegalStateException(message)

internal fun interface NeuralPackAssetSource {
    fun download(
        asset: NeuralPackDownloadAsset,
        destination: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    )
}

internal object HttpsNeuralPackAssetSource : NeuralPackAssetSource {
    override fun download(
        asset: NeuralPackDownloadAsset,
        destination: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val requested = URL(asset.url)
        if (requested.protocol != "https") throw NeuralVoicePackInstallException("Voice packs require HTTPS")
        val connection = (requested.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) throw NeuralVoicePackInstallException("Download failed with HTTP $response")
            if (connection.url.protocol != "https") throw NeuralVoicePackInstallException("Voice-pack download redirected away from HTTPS")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0 && declaredLength != asset.sizeBytes) {
                throw NeuralVoicePackInstallException("Downloaded asset size does not match the approved catalog")
            }
            destination.parentFile?.mkdirs()
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Voice-pack download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.sizeBytes) {
                            throw NeuralVoicePackInstallException("Downloaded asset exceeded its approved size")
                        }
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Installs data-only model packs. Native libraries, bytecode, and scripts are rejected. */
class NeuralVoicePackInstaller internal constructor(
    private val root: File,
    private val source: NeuralPackAssetSource = HttpsNeuralPackAssetSource,
) {
    constructor(context: Context) : this(File(context.filesDir, "tts-models"))

    private val store = NeuralVoicePackStore(root)

    fun installed(entry: NeuralVoicePackCatalogEntry): Boolean =
        store.installed(entry.packId, entry.packVersion)?.let { pack ->
            entry.approval?.matches(pack) == true
        } == true

    /** What the Voices screen needs to render, so it can be gathered in one storage scan. */
    data class InstalledPacks(val voicePackIds: Set<String>, val modelInstalled: Boolean)

    /**
     * Which of [voices] are installed, and whether [model] is, from a single storage scan. One
     * scan digests the shared model once; calling [installed] per entry instead would digest it
     * once per entry — ~329 MB of hashing each, which is seconds of latency on a phone.
     */
    fun installedPacks(
        voices: List<NeuralVoicePackCatalogEntry>,
        model: NeuralVoicePackCatalogEntry,
    ): InstalledPacks {
        val onDisk = store.installedPacks().associateBy { it.packId to it.packVersion }
        val voicePackIds = voices.filterTo(mutableSetOf()) { entry ->
            val pack = onDisk[entry.packId to entry.packVersion]
            pack != null && entry.approval?.matches(pack) == true
        }.mapTo(mutableSetOf()) { it.packId }
        val modelPack = onDisk[model.packId to model.packVersion]
        return InstalledPacks(voicePackIds, modelPack != null && model.approval?.matches(modelPack) == true)
    }

    /** Directory of the verified installed pack, used to display the notices shipped inside it. */
    fun installedDirectory(entry: NeuralVoicePackCatalogEntry): File? =
        store.installed(entry.packId, entry.packVersion)
            ?.takeIf { pack -> entry.approval?.matches(pack) == true }
            ?.directory

    /**
     * Bytes still to download for [entry], including its model pack when that is not installed.
     * Never call this from the main thread: [installed] verifies every declared asset digest, so
     * this reads and hashes hundreds of megabytes. UI code wants
     * [NeuralVoicePackCatalogEntry.downloadBytes] with a cached installed-flag instead.
     */
    fun pendingDownloadBytes(entry: NeuralVoicePackCatalogEntry): Long =
        (entry.dependency?.takeIf { !installed(it) }?.sizeBytes ?: 0L) + entry.sizeBytes

    fun install(
        entry: NeuralVoicePackCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): NeuralVoicePack {
        // Resolved once: asking whether the model pack is installed digests it, so asking twice
        // would hash 325 MB for an answer already in hand.
        val pending = entry.dependency?.takeIf { !installed(it) }
        val total = (pending?.sizeBytes ?: 0L) + entry.sizeBytes
        var completed = 0L
        pending?.let { dependency ->
            installOne(dependency, completed, total, onProgress, isCancelled)
            completed += dependency.sizeBytes
        }
        return installOne(entry, completed, total, onProgress, isCancelled)
    }

    private fun installOne(
        entry: NeuralVoicePackCatalogEntry,
        alreadyCompleted: Long,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): NeuralVoicePack {
        val approval = entry.approval
            ?: throw NeuralVoicePackInstallException("This voice is awaiting commercial licensing approval")
        if (!entry.canDownload) throw NeuralVoicePackInstallException("This voice is not released for download")
        validateCatalog(entry)
        root.mkdirs()
        if (root.usableSpace < entry.sizeBytes + MIN_FREE_SPACE_AFTER_INSTALL) {
            throw NeuralVoicePackInstallException("Not enough free space to install this voice")
        }

        val directoryName = safeDirectoryName(entry)
        val target = child(directoryName)
        store.installed(entry.packId, entry.packVersion)?.let { existing ->
            if (approval.matches(existing)) return existing
        }
        val staging = child(".$directoryName-installing")
        deleteOwnedDirectory(staging)
        if (!staging.mkdirs()) throw NeuralVoicePackInstallException("Could not create voice-pack staging storage")

        return try {
            var downloaded = 0L
            var lastReported = 0L
            entry.assets.forEach { asset ->
                val output = File(staging, asset.relativePath).canonicalFile
                if (!output.path.startsWith(staging.canonicalPath + File.separator)) {
                    throw NeuralVoicePackInstallException("Voice-pack asset escapes staging storage")
                }
                source.download(asset, output, { count ->
                    downloaded += count
                    if (downloaded - lastReported >= PROGRESS_REPORT_BYTES || downloaded == entry.sizeBytes) {
                        lastReported = downloaded
                        onProgress(alreadyCompleted + downloaded, total)
                    }
                }, isCancelled)
                if (output.length() != asset.sizeBytes) {
                    throw NeuralVoicePackInstallException("Downloaded asset has the wrong size: ${asset.relativePath}")
                }
            }
            writeManifest(entry, staging)
            // A rejection here is an install failure like any other the installer reports, so it
            // surfaces as one rather than as an argument exception from the verifier.
            val verified = runCatching { store.parseAndVerify(staging) }.getOrElse { failure ->
                throw NeuralVoicePackInstallException(
                    failure.message ?: "Downloaded voice pack failed verification",
                )
            }
            if (!approval.matches(verified)) {
                throw NeuralVoicePackInstallException("Downloaded voice does not match the app-reviewed approval")
            }
            if (target.exists()) deleteOwnedDirectory(target)
            if (!staging.renameTo(target)) throw NeuralVoicePackInstallException("Could not activate the downloaded voice")
            // Re-verifying would re-hash everything that was just verified; only the path changed.
            verified.copy(directory = target).also { onProgress(alreadyCompleted + entry.sizeBytes, total) }
        } catch (error: Throwable) {
            deleteOwnedDirectory(staging)
            throw error
        }
    }

    /**
     * Removing a voice also drops the shared model pack once nothing depends on it. The count is
     * derived from what is on disk, so there is no separate state that can disagree with it.
     */
    fun remove(entry: NeuralVoicePackCatalogEntry): Boolean {
        val installed = store.installed(entry.packId, entry.packVersion) ?: return false
        deleteOwnedDirectory(installed.directory)
        if (entry.kind == NeuralPackKind.VOICE) removeOrphanedModels()
        return true
    }

    private fun removeOrphanedModels() {
        val required = store.installedVoices().mapNotNull { it.dependsOn }.toSet()
        store.installedModels()
            .filterNot { NeuralPackRef(it.packId, it.packVersion) in required }
            .forEach { deleteOwnedDirectory(it.directory) }
    }

    private fun validateCatalog(entry: NeuralVoicePackCatalogEntry) {
        if (!PACK_ID.matches(entry.packId) || !VERSION.matches(entry.packVersion)) {
            throw NeuralVoicePackInstallException("Voice-pack catalog identity is invalid")
        }
        if (entry.assets.isEmpty() || entry.assets.size > MAX_ASSETS) {
            throw NeuralVoicePackInstallException("Voice-pack catalog has an invalid asset count")
        }
        val paths = mutableSetOf<String>()
        entry.assets.forEach { asset ->
            val lower = asset.relativePath.lowercase()
            if (!paths.add(asset.relativePath) || asset.relativePath.contains("..") ||
                asset.relativePath.startsWith('/') || asset.relativePath.startsWith('\\') ||
                FORBIDDEN_EXECUTABLE_SUFFIXES.any(lower::endsWith)
            ) {
                throw NeuralVoicePackInstallException("Voice-pack catalog contains an unsafe asset path")
            }
            if (!asset.url.startsWith("https://") || asset.sizeBytes <= 0 || !SHA256.matches(asset.sha256)) {
                throw NeuralVoicePackInstallException("Voice-pack catalog contains invalid asset metadata")
            }
        }
        val approval = entry.approval
        if (approval?.assets != entry.assets.map { it.modelAsset() }.toSet()) {
            throw NeuralVoicePackInstallException("Catalog assets do not match the app-owned approval")
        }
        if (approval.kind != entry.kind || approval.dependsOn != entry.dependsOn) {
            throw NeuralVoicePackInstallException("Catalog pack kind or dependency does not match the app-owned approval")
        }
    }

    private fun writeManifest(entry: NeuralVoicePackCatalogEntry, directory: File) {
        val json = JSONObject()
            .put("schemaVersion", entry.schemaVersion)
            .put("kind", entry.kind.name)
            .put("packId", entry.packId)
            .put("packVersion", entry.packVersion)
            .put("runtimeId", entry.runtimeId)
            .put("modelId", entry.modelId)
            .put("modelVersion", entry.modelVersion)
            .put("voiceId", entry.voiceId)
            .put("voiceVersion", entry.voiceVersion)
            .put("languageTags", JSONArray(entry.languageTags.toList()))
        entry.dependsOn?.let { reference ->
            json.put("dependsOn", JSONObject().put("packId", reference.packId).put("packVersion", reference.packVersion))
        }
        json
            .put("license", JSONObject()
                .put("spdxId", entry.licenseSpdxId)
                .put("commercialUseAllowed", true)
                .put("attribution", entry.attribution))
            .put("audio", JSONObject()
                .put("sampleRateHz", entry.pcmFormat.sampleRateHz)
                .put("channelCount", entry.pcmFormat.channelCount)
                .put("encoding", entry.pcmFormat.encoding.name))
            .put("assets", JSONArray(entry.assets.map { asset ->
                JSONObject()
                    .put("path", asset.relativePath)
                    .put("sizeBytes", asset.sizeBytes)
                    .put("sha256", asset.sha256)
            }))
        File(directory, "manifest.json").writeText(json.toString(), Charsets.UTF_8)
    }

    private fun safeDirectoryName(entry: NeuralVoicePackCatalogEntry) = "${entry.packId}-${entry.packVersion}"

    private fun child(name: String): File {
        val canonicalRoot = root.canonicalFile
        val value = File(canonicalRoot, name).canonicalFile
        if (value.parentFile != canonicalRoot) throw NeuralVoicePackInstallException("Unsafe voice-pack storage path")
        return value
    }

    private fun deleteOwnedDirectory(directory: File) {
        if (!directory.exists()) return
        val canonicalRoot = root.canonicalFile
        val target = directory.canonicalFile
        if (target.parentFile != canonicalRoot) throw NeuralVoicePackInstallException("Refusing to remove storage outside the voice-pack directory")
        if (!target.deleteRecursively()) throw NeuralVoicePackInstallException("Could not clean voice-pack storage")
    }

    private companion object {
        val PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val FORBIDDEN_EXECUTABLE_SUFFIXES = setOf(
            ".so", ".dex", ".jar", ".apk", ".aab", ".dylib", ".framework", ".class", ".js", ".py", ".sh",
        )
        const val MAX_ASSETS = 32
        const val PROGRESS_REPORT_BYTES = 1024L * 1024L
        const val MIN_FREE_SPACE_AFTER_INSTALL = 128L * 1024L * 1024L
    }
}
