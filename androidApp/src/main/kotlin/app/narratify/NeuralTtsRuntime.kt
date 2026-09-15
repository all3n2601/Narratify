package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A model pack carries the shared graph and dictionary; a voice pack carries one style and names
 * the model pack it needs. Splitting them keeps the 325 MB graph to a single download.
 */
enum class NeuralPackKind { MODEL, VOICE }

data class NeuralPackRef(val packId: String, val packVersion: String)

/** A fully verified, commercially distributable model pack already present on disk. */
data class NeuralVoicePack(
    val directory: File,
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
    val commercialUseAllowed: Boolean,
    val attribution: String?,
    val pcmFormat: PcmFormat,
    val assets: List<NeuralModelAsset>,
    val dependsOn: NeuralPackRef? = null,
    /** Resolved by the store, never by a runtime, so adapters keep a single-pack signature. */
    val dependency: NeuralVoicePack? = null,
) {
    /**
     * Assets may live in this pack or in its model pack. Resolving by declared path rather than by
     * directory means a runtime cannot read a file the manifest never listed and the verifier
     * therefore never checked.
     */
    fun file(relativePath: String): File {
        assets.firstOrNull { it.relativePath == relativePath }
            ?.let { return File(directory, it.relativePath) }
        dependency?.let { pack ->
            pack.assets.firstOrNull { it.relativePath == relativePath }
                ?.let { return File(pack.directory, it.relativePath) }
        }
        throw VoicePackValidationException("This voice pack does not declare the asset $relativePath")
    }
}

data class NeuralModelAsset(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class NeuralSynthesisRequest(
    val speechText: String,
    val rate: Float,
    val pitchSemitones: Float = 0f,
)

data class NeuralWordTiming(
    val spokenStart: Int,
    val spokenEndExclusive: Int,
    val startSample: Long,
    val endSample: Long,
)

/**
 * Runtime adapter implemented by a separately reviewed backend (for example sherpa-onnx).
 * Implementations must not perform network access and should stream bounded PCM blocks.
 */
interface OnDeviceNeuralTtsRuntime {
    val runtimeId: String
    fun supports(pack: NeuralVoicePack): Boolean
    fun open(pack: NeuralVoicePack): NeuralTtsSession
}

interface NeuralTtsSession : Closeable {
    fun synthesize(request: NeuralSynthesisRequest, callback: NeuralSynthesisCallback): NeuralSynthesisJob
}

fun interface NeuralSynthesisJob {
    fun cancel()
}

interface NeuralSynthesisCallback {
    /** Must arrive before the first PCM block. Timings use per-channel PCM frames. */
    fun onMetadata(format: PcmFormat, timings: List<NeuralWordTiming>)
    fun onPcm(bytes: ByteArray)
    fun onComplete()
    fun onError(message: String, cause: Throwable? = null)
}

/**
 * App-owned review record compiled with a runtime adapter. A pack-authored license claim alone
 * is never trusted; every identity and asset digest must match this record exactly.
 */
data class ApprovedNeuralVoicePack(
    val packId: String,
    val packVersion: String,
    val kind: NeuralPackKind,
    val runtimeId: String,
    val modelId: String,
    val modelVersion: String,
    val voiceId: String,
    val voiceVersion: String,
    val licenseSpdxId: String,
    val assets: Set<NeuralModelAsset>,
    /** Pinned so an approved voice cannot be pointed at an unapproved model pack. */
    val dependsOn: NeuralPackRef? = null,
) {
    internal fun matches(pack: NeuralVoicePack): Boolean =
        pack.commercialUseAllowed &&
            pack.packId == packId && pack.packVersion == packVersion && pack.kind == kind &&
            pack.runtimeId == runtimeId && pack.modelId == modelId && pack.modelVersion == modelVersion &&
            pack.voiceId == voiceId && pack.voiceVersion == voiceVersion &&
            pack.licenseSpdxId == licenseSpdxId && pack.assets.toSet() == assets &&
            pack.dependsOn == dependsOn
}

/** Process-local registry keeps proprietary/GPL/questionable runtimes out of the app by default. */
object NeuralTtsRuntimeRegistry {
    private data class TrustedRuntime(
        val runtime: OnDeviceNeuralTtsRuntime,
        val approvals: Set<ApprovedNeuralVoicePack>,
    )

    private val runtimes = ConcurrentHashMap<String, TrustedRuntime>()

    fun register(runtime: OnDeviceNeuralTtsRuntime, approvals: Set<ApprovedNeuralVoicePack>) {
        require(runtime.runtimeId.isNotBlank())
        require(approvals.isNotEmpty()) { "A neural runtime requires at least one app-reviewed voice-pack approval" }
        require(approvals.all { it.runtimeId == runtime.runtimeId }) { "Approval runtime identity does not match the adapter" }
        runtimes[runtime.runtimeId] = TrustedRuntime(runtime, approvals)
    }

    fun unregister(runtimeId: String) {
        runtimes.remove(runtimeId)
    }

    /**
     * Read-only view of exactly what a registered runtime is licensed to open. Exists so the
     * licensing gate can be asserted in tests rather than trusted to inspection; nothing in the
     * app takes decisions from it.
     */
    internal fun approvals(runtimeId: String): Set<ApprovedNeuralVoicePack> =
        runtimes[runtimeId]?.approvals.orEmpty()

    fun compatible(pack: NeuralVoicePack): OnDeviceNeuralTtsRuntime? =
        runtimes[pack.runtimeId]
            ?.takeIf { trusted -> approved(trusted.approvals, pack) && trusted.runtime.supports(pack) }
            ?.runtime

    /**
     * A voice's own approval pins only its dependency's packId and version, so the shared graph's
     * digests would otherwise be pinned by nothing but the model pack's own manifest — a
     * pack-authored claim. Requiring the resolved model pack to match an approval of its own puts
     * every digest on the path to opening a graph back under an app-owned review record.
     */
    private fun approved(approvals: Set<ApprovedNeuralVoicePack>, pack: NeuralVoicePack): Boolean {
        if (approvals.none { it.matches(pack) }) return false
        if (pack.dependsOn == null) return true
        val dependency = pack.dependency ?: return false
        return approvals.any { it.matches(dependency) }
    }
}

class VoicePackValidationException(message: String) : IllegalArgumentException(message)

/** Validation happens before a runtime is allowed to mmap or parse any model asset. */
object NeuralVoicePackVerifier {
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    /**
     * @param verifyDependency false only when the caller has already verified that exact model
     * pack instance in the same operation. Every structural invariant about the dependency is
     * still checked; only the recursive digest pass over the 325 MB graph is skipped, so the
     * shared model is read and hashed once per operation instead of once per voice.
     */
    fun verify(pack: NeuralVoicePack, verifyDependency: Boolean = true): NeuralVoicePack {
        if (pack.schemaVersion != 2) fail("Unsupported voice-pack schema ${pack.schemaVersion}")
        if (pack.packId.isBlank() || pack.packVersion.isBlank()) fail("Voice-pack identity is incomplete")
        if (pack.runtimeId.isBlank() || pack.modelId.isBlank() || pack.modelVersion.isBlank()) fail("Runtime/model identity is incomplete")
        if (pack.languageTags.isEmpty() || pack.languageTags.any { it.isBlank() }) fail("At least one language tag is required")
        if (pack.licenseSpdxId.isBlank() || !pack.commercialUseAllowed) {
            fail("Voice pack must explicitly declare a commercial-use-compatible license")
        }
        if (pack.pcmFormat.encoding != PcmEncoding.SIGNED_INT_16_LE) fail("Android streaming currently requires signed 16-bit PCM")
        if (pack.pcmFormat.channelCount !in 1..2) fail("Only mono or stereo PCM is supported")
        if (pack.assets.isEmpty()) fail("Voice pack contains no model assets")

        // Hashing is the expensive part, so it comes after every cheap check above: a pack that
        // fails one of those costs nothing to reject.
        when (pack.kind) {
            NeuralPackKind.VOICE -> {
                if (pack.voiceId.isBlank() || pack.voiceVersion.isBlank()) fail("Voice identity is incomplete")
                val declared = pack.dependsOn ?: fail("A voice pack must declare the model pack it needs")
                val resolved = pack.dependency ?: fail("The model pack for this voice is not installed")
                if (resolved.kind != NeuralPackKind.MODEL) fail("A voice pack may only depend on a model pack")
                if (resolved.packId != declared.packId || resolved.packVersion != declared.packVersion) {
                    fail("The resolved model pack does not match the declared dependency")
                }
                if (verifyDependency) verify(resolved)
            }

            NeuralPackKind.MODEL -> {
                if (pack.voiceId.isNotBlank() || pack.voiceVersion.isNotBlank()) {
                    fail("A model pack must not claim a voice identity")
                }
                if (pack.dependsOn != null || pack.dependency != null) {
                    fail("A model pack must not depend on another pack")
                }
            }
        }

        val root = pack.directory.canonicalFile
        if (!root.isDirectory) fail("Voice-pack directory does not exist")
        val seen = mutableSetOf<String>()
        pack.assets.forEach { asset ->
            if (asset.relativePath.isBlank() || asset.relativePath.startsWith('/') || asset.relativePath.startsWith('\\')) {
                fail("Model asset path must be relative")
            }
            if (!seen.add(asset.relativePath)) fail("Duplicate model asset path: ${asset.relativePath}")
            if (asset.sizeBytes <= 0L) fail("Model asset has an invalid size: ${asset.relativePath}")
            if (!sha256Pattern.matches(asset.sha256)) fail("Model asset has an invalid SHA-256: ${asset.relativePath}")
            val file = File(root, asset.relativePath).canonicalFile
            if (file != root && !file.path.startsWith(root.path + File.separator)) fail("Model asset escapes its pack directory")
            if (!file.isFile || file.length() != asset.sizeBytes) fail("Model asset is missing or has the wrong size: ${asset.relativePath}")
            if (!digest(file).equals(asset.sha256, ignoreCase = true)) fail("Model asset checksum failed: ${asset.relativePath}")
        }
        return pack
    }

    /**
     * Full-file digest passes per absolute path. Exists so the hashing cost of a scan can be
     * pinned by a regression test rather than trusted to inspection; nothing in the app reads it
     * and it can never change what [verify] accepts.
     */
    internal val digestCounts: MutableMap<String, Int> = ConcurrentHashMap()

    private fun digest(file: File): String {
        digestCounts.merge(file.path, 1, Int::plus)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun fail(message: String): Nothing = throw VoicePackValidationException(message)
}
