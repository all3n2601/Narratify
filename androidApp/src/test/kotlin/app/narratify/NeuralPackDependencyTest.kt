package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class NeuralPackDependencyTest {
    @Test
    fun `a voice pack resolves assets from itself and from its model pack`() {
        val packs = fixture()

        assertEquals("voice-data", packs.voice.file("af_test.bin").readText())
        assertEquals("model-data", packs.voice.file("kokoro-v1.0.onnx").readText())
    }

    @Test
    fun `an undeclared asset path is refused instead of silently missing`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> { packs.voice.file("not-declared.bin") }
    }

    @Test
    fun `an intact voice plus model pair verifies`() {
        val packs = fixture()

        NeuralVoicePackVerifier.verify(packs.voice)
    }

    @Test
    fun `a voice pack without its resolved model pack is refused`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(dependency = null))
        }
    }

    @Test
    fun `a voice pack whose resolved model pack is the wrong one is refused`() {
        val packs = fixture()
        val impostor = packs.model.copy(packId = "app.narratify.kokoro.other")

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(dependency = impostor))
        }
    }

    @Test
    fun `a model pack must not claim a voice identity`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.model.copy(voiceId = "af_test", voiceVersion = "1.0.0"))
        }
    }

    @Test
    fun `a voice pack with a blank voice identity is refused`() {
        val packs = fixture()

        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(packs.voice.copy(voiceId = ""))
        }
    }

    @Test
    fun `tampered model bytes fail through the voice pack that depends on them`() {
        val packs = fixture()
        val graph = File(packs.model.directory, "kokoro-v1.0.onnx")
        val approvedLength = graph.length()
        // Same length as the approved bytes, so the SHA-256 comparison is what refuses this and
        // not the cheaper size check that runs before it.
        graph.writeText("wrong-data")
        assertEquals(approvedLength, graph.length())

        val failure = assertFailsWith<VoicePackValidationException> { NeuralVoicePackVerifier.verify(packs.voice) }

        // Names the model asset, so the failure really did arrive through the dependency.
        assertTrue(failure.message.orEmpty().contains("kokoro-v1.0.onnx"), failure.message)
    }

    @Test
    fun `the store composes an installed voice with its installed model pack`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        val store = NeuralVoicePackStore(root)

        val voices = store.installedVoices()

        assertEquals(1, voices.size)
        assertEquals("af_test", voices.single().voiceId)
        assertEquals("model-data", voices.single().file("kokoro-v1.0.onnx").readText())
        assertEquals(1, store.installedModels().size)
    }

    /**
     * Reading and hashing the 325 MB graph once per voice was a multi-second stall on every book
     * open. Nothing but this count will keep that fixed, so it is pinned rather than described.
     */
    @Test
    fun `one scan digests the shared graph once however many voices depend on it`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        val graph = writeInstalledModel(root)
        writeInstalledVoice(root, "one", "af_one")
        writeInstalledVoice(root, "two", "af_two")
        val store = NeuralVoicePackStore(root)
        // The verifier records the canonical path it actually reads, not the one handed to it.
        val graphPath = graph.canonicalPath
        NeuralVoicePackVerifier.digestCounts.remove(graphPath)

        val voices = store.installedVoices()

        assertEquals(2, voices.size)
        assertEquals(1, NeuralVoicePackVerifier.digestCounts[graphPath])
    }

    @Test
    fun `a voice whose model pack is missing is not reported as installed`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        File(root, "app.narratify.kokoro.model-1.0.0").deleteRecursively()
        val store = NeuralVoicePackStore(root)

        assertEquals(emptyList(), store.installedVoices())
    }

    @Test
    fun `a schema version 1 directory is skipped instead of crashing the scan`() {
        val root = createTempDirectory("store-root").toFile().apply { deleteOnExit() }
        writeInstalledPair(root)
        val stale = File(root, "legacy-pack").apply { mkdirs() }
        File(stale, "manifest.json").writeText("""{"schemaVersion":1,"packId":"legacy"}""")
        val store = NeuralVoicePackStore(root)

        assertEquals(1, store.installedVoices().size)
    }

    @Test
    fun `the runtime supports every approved catalog voice`() {
        NarratifyNeuralVoiceCatalog.kokoroVoices.forEach { entry ->
            val pack = entry.asPack(File("/tmp/${entry.packId}")).copy(
                dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
            )
            assertTrue(KokoroOnnxRuntime.supports(pack), "expected support for ${entry.voiceId}")
        }
    }

    @Test
    fun `the runtime refuses a pack whose voice file is not declared`() {
        val entry = NarratifyNeuralVoiceCatalog.kokoroVoices.first()
        val pack = entry.asPack(File("/tmp/${entry.packId}")).copy(
            assets = emptyList(),
            dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
        )

        assertFalse(KokoroOnnxRuntime.supports(pack))
    }

    @Test
    fun `the runtime refuses a model pack`() {
        val model = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model"))

        assertFalse(KokoroOnnxRuntime.supports(model))
    }

    @Test
    fun `every authorized catalog voice carries a compiled approval`() {
        val voices = NarratifyNeuralVoiceCatalog.kokoroVoices

        assertEquals(6, voices.size)
        voices.forEach { entry ->
            assertEquals(NeuralPackReleaseStatus.AVAILABLE, entry.releaseStatus, entry.voiceId)
            assertNotNull(entry.approval, entry.voiceId)
            assertTrue(entry.canDownload, entry.voiceId)
            assertEquals(entry.voiceId, entry.approval?.voiceId, entry.voiceId)
            assertEquals(NeuralPackKind.VOICE, entry.approval?.kind, entry.voiceId)
        }
        assertNotNull(NarratifyNeuralVoiceCatalog.kokoroModel.approval)
    }

    @Test
    fun `registration licenses exactly the catalog packs that carry an approval`() {
        // An entry with no compiled approval stands in for a voice the owner has not authorized.
        val unauthorized = unauthorizedEntry()

        KokoroOnnxRuntime.registerIfApproved(NarratifyNeuralVoiceCatalog.all + unauthorized)
        try {
            val approvals = NeuralTtsRuntimeRegistry.approvals(KokoroOnnxRuntime.runtimeId)

            assertEquals(NarratifyNeuralVoiceCatalog.all.mapNotNull { it.approval }.toSet(), approvals)
            assertEquals(7, approvals.size)
            assertTrue(
                approvals.none { it.packId == unauthorized.packId },
                "an approval was registered for a pack that carries none",
            )
        } finally {
            NeuralTtsRuntimeRegistry.unregister(KokoroOnnxRuntime.runtimeId)
        }
    }

    @Test
    fun `a well formed pack with no compiled approval still gets no runtime`() {
        val heart = packFor("af_heart")
        val unauthorized = unauthorizedEntry()
        val impostor = unauthorized.asPack(File("/tmp/${unauthorized.packId}")).copy(
            dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
            // asPack derives commercialUseAllowed from canDownload, so claim it outright: the
            // only thing left that can refuse this pack is the absent approval.
            commercialUseAllowed = true,
        )
        // Structure alone is not the gate: this pack is exactly what the runtime wants to see.
        assertTrue(KokoroOnnxRuntime.supports(impostor))
        // Nothing is openable before registration, so the assertions below measure registration.
        assertNull(NeuralTtsRuntimeRegistry.compatible(heart))

        withRegisteredKokoro {
            assertNotNull(NeuralTtsRuntimeRegistry.compatible(heart))
            assertNull(NeuralTtsRuntimeRegistry.compatible(impostor))
        }
    }

    /**
     * The voice's own approval pins only its dependency's packId and version, so without a check
     * on the model pack itself the graph's digest would be pinned by nothing but the model pack's
     * own manifest — exactly the pack-authored claim the approval records exist to distrust.
     */
    @Test
    fun `a voice whose model pack no approval covers still gets no runtime`() {
        val heart = packFor("af_heart")
        val model = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model"))
        // Identical asset paths and sizes, different digests: only the model pack's own manifest
        // vouches for these bytes, and no compiled approval does.
        val unapprovedModel = model.copy(assets = model.assets.map { it.copy(sha256 = "0".repeat(64)) })
        val impostor = heart.copy(dependency = unapprovedModel)
        // Structure alone is not the gate: this pack is exactly what the runtime wants to see.
        assertTrue(KokoroOnnxRuntime.supports(impostor))

        withRegisteredKokoro {
            assertNotNull(NeuralTtsRuntimeRegistry.compatible(heart))
            assertNull(NeuralTtsRuntimeRegistry.compatible(impostor))
        }
    }

    /**
     * A structurally perfect voice entry that no compiled approval covers. Keeping af_heart's
     * assets and voiceId makes it pass every structural check, so a test using it can only fail
     * on the approval lookup.
     */
    private fun unauthorizedEntry() = catalogEntry("af_heart").copy(
        packId = "app.narratify.kokoro.en-us.unauthorized",
        releaseStatus = NeuralPackReleaseStatus.AWAITING_LICENSE_APPROVAL,
        approval = null,
    )

    private fun catalogEntry(voiceId: String) =
        NarratifyNeuralVoiceCatalog.kokoroVoices.first { it.voiceId == voiceId }

    /** A structurally complete voice pack composed with the shared model pack, as the store would. */
    private fun packFor(voiceId: String): NeuralVoicePack {
        val entry = catalogEntry(voiceId)
        return entry.asPack(File("/tmp/${entry.packId}")).copy(
            dependency = NarratifyNeuralVoiceCatalog.kokoroModel.asPack(File("/tmp/model")),
        )
    }

    /** The registry is process-global, so registration never outlives the assertion that needs it. */
    private fun withRegisteredKokoro(body: () -> Unit) {
        KokoroOnnxRuntime.registerIfApproved(NarratifyNeuralVoiceCatalog.all)
        try {
            body()
        } finally {
            NeuralTtsRuntimeRegistry.unregister(KokoroOnnxRuntime.runtimeId)
        }
    }

    /** Writes a model pack and a voice pack to [root] exactly as the installer would. */
    private fun writeInstalledPair(root: File) {
        writeInstalledModel(root)
        writeInstalledVoice(root, "test", "af_test")
    }

    /** The shared model pack, whose only asset stands in for the 325 MB graph. */
    private fun writeInstalledModel(root: File): File {
        val directory = File(root, "app.narratify.kokoro.model-1.0.0").apply { mkdirs() }
        val graph = File(directory, "kokoro-v1.0.onnx").apply { writeText("model-data") }
        File(directory, "manifest.json").writeText(
            """
            {"schemaVersion":2,"kind":"MODEL","packId":"app.narratify.kokoro.model","packVersion":"1.0.0",
             "runtimeId":"narratify-kokoro-onnx","modelId":"kokoro-82m-v1.0-fp32-duration",
             "modelVersion":"model-files-v1.1","voiceId":"","voiceVersion":"","languageTags":["en-US"],
             "license":{"spdxId":"Apache-2.0","commercialUseAllowed":true,"attribution":"Test only"},
             "audio":{"sampleRateHz":24000,"channelCount":1,"encoding":"SIGNED_INT_16_LE"},
             "assets":[{"path":"kokoro-v1.0.onnx","sizeBytes":${graph.length()},"sha256":"${sha256(graph)}"}]}
            """.trimIndent(),
        )
        return graph
    }

    private fun writeInstalledVoice(root: File, name: String, voiceId: String) {
        val directory = File(root, "app.narratify.kokoro.en-us.$name-1.0.0").apply { mkdirs() }
        val voiceFile = File(directory, "$voiceId.bin").apply { writeText("voice-data-$name") }
        File(directory, "manifest.json").writeText(
            """
            {"schemaVersion":2,"kind":"VOICE","packId":"app.narratify.kokoro.en-us.$name","packVersion":"1.0.0",
             "runtimeId":"narratify-kokoro-onnx","modelId":"kokoro-82m-v1.0-fp32-duration",
             "modelVersion":"model-files-v1.1","voiceId":"$voiceId","voiceVersion":"${voiceId.replace('_', '-')}-1939ad2",
             "languageTags":["en-US"],
             "license":{"spdxId":"Apache-2.0","commercialUseAllowed":true,"attribution":"Test only"},
             "audio":{"sampleRateHz":24000,"channelCount":1,"encoding":"SIGNED_INT_16_LE"},
             "dependsOn":{"packId":"app.narratify.kokoro.model","packVersion":"1.0.0"},
             "assets":[{"path":"$voiceId.bin","sizeBytes":${voiceFile.length()},"sha256":"${sha256(voiceFile)}"}]}
            """.trimIndent(),
        )
    }

    private fun fixture(): Packs {
        val root = createTempDirectory("pack-root").toFile().apply { deleteOnExit() }
        val modelDirectory = File(root, "model").apply { mkdirs() }
        val voiceDirectory = File(root, "voice").apply { mkdirs() }
        val modelFile = File(modelDirectory, "kokoro-v1.0.onnx").apply { writeText("model-data") }
        val dictionaryFile = File(modelDirectory, "cmudict.dict").apply { writeText("dictionary-data") }
        val voiceFile = File(voiceDirectory, "af_test.bin").apply { writeText("voice-data") }

        val model = NeuralVoicePack(
            directory = modelDirectory,
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
            commercialUseAllowed = true,
            attribution = "Test only",
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = listOf(
                NeuralModelAsset("kokoro-v1.0.onnx", modelFile.length(), sha256(modelFile)),
                NeuralModelAsset("cmudict.dict", dictionaryFile.length(), sha256(dictionaryFile)),
            ),
        )
        val voice = NeuralVoicePack(
            directory = voiceDirectory,
            schemaVersion = 2,
            kind = NeuralPackKind.VOICE,
            packId = "app.narratify.kokoro.en-us.test",
            packVersion = "1.0.0",
            runtimeId = "narratify-kokoro-onnx",
            modelId = "kokoro-82m-v1.0-fp32-duration",
            modelVersion = "model-files-v1.1",
            voiceId = "af_test",
            voiceVersion = "af-test-1939ad2",
            languageTags = setOf("en-US"),
            licenseSpdxId = "Apache-2.0",
            commercialUseAllowed = true,
            attribution = "Test only",
            pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
            assets = listOf(NeuralModelAsset("af_test.bin", voiceFile.length(), sha256(voiceFile))),
            dependsOn = NeuralPackRef("app.narratify.kokoro.model", "1.0.0"),
            dependency = model,
        )
        return Packs(model, voice)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class Packs(val model: NeuralVoicePack, val voice: NeuralVoicePack)
}
