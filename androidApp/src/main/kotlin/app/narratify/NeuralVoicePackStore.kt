package app.narratify

import android.content.Context
import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import org.json.JSONObject
import java.io.File

/** Reads only verified packs from app-private storage. */
class NeuralVoicePackStore private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "tts-models"))

    internal constructor(root: File, testOnly: Unit = Unit) : this(root)

    /**
     * Every verified pack on disk, of either kind, from one scan. The shared model is verified
     * once per scan and shared with every voice that depends on it, so one scan digests it once —
     * ~329 MB of hashing. Callers that need both voices and models should use this rather than
     * calling the two accessors below, which would digest it once each.
     */
    fun installedPacks(): List<NeuralVoicePack> = scan()

    /** Every installed voice, each already composed with its verified model pack. */
    fun installedVoices(): List<NeuralVoicePack> = installedPacks()
        .filter { it.kind == NeuralPackKind.VOICE }
        .sortedByDescending { it.directory.lastModified() }

    /**
     * Only model packs are verified: the kind comes from the parsed manifest, so a voice pack is
     * skipped because it is a voice, not because verifying it happens to fail.
     */
    fun installedModels(): List<NeuralVoicePack> {
        val parsed = parsedPacks()
        val verifier = ScanVerifier(parsed)
        return parsed
            .filter { it.kind == NeuralPackKind.MODEL }
            .mapNotNull { runCatching { verifier.verify(it) }.getOrNull() }
    }

    /**
     * Every installed voice for [languageTag] that a registered, approved runtime can open, in
     * install-recency order. Callers apply their own preference ordering; the store stays free of
     * catalog knowledge so it can be tested without one.
     */
    fun compatible(languageTag: String): List<Pair<NeuralVoicePack, OnDeviceNeuralTtsRuntime>> {
        val requested = languageTag.substringBefore('-').lowercase()
        return installedVoices()
            .filter { pack -> pack.languageTags.any { it.substringBefore('-').lowercase() == requested } }
            .mapNotNull { pack -> NeuralTtsRuntimeRegistry.compatible(pack)?.let { pack to it } }
    }

    /**
     * Parses every manifest, which is cheap, then verifies only the matching directory and its
     * model pack. Verifying every pack in the root instead would digest the shared graph for
     * packs the caller never asked about.
     */
    internal fun installed(packId: String, packVersion: String): NeuralVoicePack? {
        val parsed = parsedPacks()
        val match = parsed.firstOrNull { it.packId == packId && it.packVersion == packVersion } ?: return null
        return runCatching { ScanVerifier(parsed).verify(match) }.getOrNull()
    }

    internal fun parseAndVerify(directory: File): NeuralVoicePack =
        ScanVerifier(parsedPacks()).verify(parse(directory))

    private fun scan(): List<NeuralVoicePack> {
        val parsed = parsedPacks()
        val verifier = ScanVerifier(parsed)
        return parsed.mapNotNull { runCatching { verifier.verify(it) }.getOrNull() }
    }

    /** Every pack directory parsed once. Manifest reads only, so nothing here hashes an asset. */
    private fun parsedPacks(): List<NeuralVoicePack> =
        root.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.take(MAX_PACKS_TO_SCAN)
            ?.mapNotNull { runCatching { parse(it) }.getOrNull() }
            ?.toList()
            .orEmpty()

    /**
     * Verifies each pack directory at most once for one operation, so the 325 MB graph is read
     * and hashed once however many voices depend on it. A voice is composed with the model pack
     * instance this verifier already verified, so its own verification re-checks every structural
     * dependency invariant without a second digest pass.
     */
    private class ScanVerifier(private val parsed: List<NeuralVoicePack>) {
        private val verified = mutableMapOf<File, Result<NeuralVoicePack>>()

        fun verify(pack: NeuralVoicePack): NeuralVoicePack = when (pack.kind) {
            NeuralPackKind.MODEL -> once(pack) { NeuralVoicePackVerifier.verify(pack) }
            NeuralPackKind.VOICE -> {
                val dependency = pack.dependsOn?.let { reference -> model(reference) }
                once(pack) {
                    NeuralVoicePackVerifier.verify(pack.copy(dependency = dependency), verifyDependency = false)
                }
            }
        }

        /** Only model packs are searched, so dependency resolution can never recurse. */
        private fun model(reference: NeuralPackRef): NeuralVoicePack? =
            parsed.firstOrNull {
                it.kind == NeuralPackKind.MODEL &&
                    it.packId == reference.packId && it.packVersion == reference.packVersion
            }?.let { runCatching { verify(it) }.getOrNull() }

        private fun once(pack: NeuralVoicePack, body: () -> NeuralVoicePack): NeuralVoicePack =
            verified.getOrPut(pack.directory) { runCatching(body) }.getOrThrow()
    }

    private fun parse(directory: File): NeuralVoicePack {
        val manifest = File(directory, "manifest.json")
        require(manifest.isFile && manifest.length() in 1..MAX_MANIFEST_BYTES) { "Invalid voice-pack manifest" }
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        val license = json.getJSONObject("license")
        val audio = json.getJSONObject("audio")
        val languages = json.getJSONArray("languageTags")
        val assetsJson = json.getJSONArray("assets")
        val assets = buildList {
            require(assetsJson.length() in 1..MAX_ASSETS) { "Invalid model asset count" }
            repeat(assetsJson.length()) { index ->
                val item = assetsJson.getJSONObject(index)
                add(NeuralModelAsset(item.getString("path"), item.getLong("sizeBytes"), item.getString("sha256")))
            }
        }
        return NeuralVoicePack(
            directory = directory,
            schemaVersion = json.getInt("schemaVersion"),
            kind = NeuralPackKind.valueOf(json.getString("kind")),
            packId = json.getString("packId"),
            packVersion = json.getString("packVersion"),
            runtimeId = json.getString("runtimeId"),
            modelId = json.getString("modelId"),
            modelVersion = json.getString("modelVersion"),
            voiceId = json.getString("voiceId"),
            voiceVersion = json.getString("voiceVersion"),
            languageTags = buildSet { repeat(languages.length()) { add(languages.getString(it)) } },
            licenseSpdxId = license.getString("spdxId"),
            commercialUseAllowed = license.optBoolean("commercialUseAllowed", false),
            attribution = license.optString("attribution").ifBlank { null },
            pcmFormat = PcmFormat(
                sampleRateHz = audio.getInt("sampleRateHz"),
                channelCount = audio.getInt("channelCount"),
                encoding = PcmEncoding.valueOf(audio.getString("encoding")),
            ),
            assets = assets,
            dependsOn = json.optJSONObject("dependsOn")?.let {
                NeuralPackRef(it.getString("packId"), it.getString("packVersion"))
            },
        )
    }

    private companion object {
        const val MAX_PACKS_TO_SCAN = 16
        const val MAX_ASSETS = 32
        const val MAX_MANIFEST_BYTES = 64L * 1024L
    }
}
