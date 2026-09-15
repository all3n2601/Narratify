package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class NeuralVoicePackInstallerTest {
    @Test
    fun approvedAndroidCandidateIsDownloadableAndMatchesItsReviewRecord() {
        val entry = NarratifyNeuralVoiceCatalog.kokoroVoices.first()

        assertTrue(entry.canDownload)
        assertTrue(entry.approval?.assets == entry.assets.map { it.modelAsset() }.toSet())
    }

    @Test
    fun rejectsDownloadedBytesThatDoNotMatchTheApprovedHash() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf("kokoro-v1.0.onnx" to "model-data".encodeToByteArray())
        val entry = modelFixture(bytes)
        val tampered = mapOf("kokoro-v1.0.onnx" to "wrong-data".encodeToByteArray())
        val installer = NeuralVoicePackInstaller(root, fixtureSource(tampered))

        assertFailsWith<NeuralVoicePackInstallException> { installer.install(entry) }
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-installing") })
    }

    @Test
    fun `a tampered voice asset fails the install even though the model pack is intact`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        // Same length as the approved bytes, so the digest is what refuses this, not the size.
        val tampered = bytes + ("af_one.bin" to "bad-data".encodeToByteArray())
        val installer = NeuralVoicePackInstaller(root, fixtureSource(tampered))

        assertFailsWith<NeuralVoicePackInstallException> { installer.install(one) }

        assertFalse(installer.installed(one))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-installing") })
    }

    @Test
    fun `one scan reports every installed voice and whether the shared model is present`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
            "af_two.bin" to "two-data".encodeToByteArray(),
            "af_three.bin" to "three-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val two = voiceFixture("two", "af_two", bytes, model)
        val never = voiceFixture("three", "af_three", bytes, model)
        installer.install(one)
        installer.install(two)

        val installed = installer.installedPacks(listOf(one, two, never), model)

        assertEquals(setOf(one.packId, two.packId), installed.voicePackIds)
        assertTrue(installed.modelInstalled)

        installer.remove(one)
        installer.remove(two)
        val emptied = installer.installedPacks(listOf(one, two, never), model)

        assertEquals(emptySet(), emptied.voicePackIds)
        assertFalse(emptied.modelInstalled)
    }

    @Test
    fun rejectsExecutableAssetsEvenWhenTheirHashIsApproved() {
        val bytes = mapOf("runtime.so" to "native-code".encodeToByteArray())
        val entry = modelFixture(bytes)

        assertFailsWith<NeuralVoicePackInstallException> {
            NeuralVoicePackInstaller(createTempDirectory("voice-pack-root").toFile(), fixtureSource(bytes)).install(entry)
        }
    }

    @Test
    fun `installing a voice installs its model pack first and reuses it for the next voice`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
            "af_two.bin" to "two-data".encodeToByteArray(),
        )
        val downloaded = mutableListOf<String>()
        val installer = NeuralVoicePackInstaller(root, countingSource(bytes, downloaded))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val two = voiceFixture("two", "af_two", bytes, model)

        installer.install(one)

        // The name's ordering claim: the shared model lands before the voice that needs it.
        assertEquals(listOf("kokoro-v1.0.onnx", "af_one.bin"), downloaded)

        downloaded.clear()
        installer.install(two)

        assertTrue(installer.installed(one))
        assertTrue(installer.installed(two))
        assertTrue(installer.installed(model))
        assertEquals(listOf("af_two.bin"), downloaded)
    }

    @Test
    fun `progress for a first install spans the model and the voice`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val expectedTotal = model.sizeBytes + one.sizeBytes
        val totals = mutableSetOf<Long>()
        val reported = mutableListOf<Long>()

        installer.install(one, onProgress = { downloaded, total -> reported.add(downloaded); totals.add(total) })

        assertEquals(setOf(expectedTotal), totals)
        // A bar that goes backwards or stops short of full is what the reader would see instead.
        assertEquals(reported.sorted(), reported)
        assertEquals(expectedTotal, reported.lastOrNull())
    }

    @Test
    fun `removing the last voice removes the shared model pack`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
            "af_two.bin" to "two-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val two = voiceFixture("two", "af_two", bytes, model)
        installer.install(one)
        installer.install(two)

        assertTrue(installer.remove(one))
        assertTrue(installer.installed(model))

        assertTrue(installer.remove(two))
        assertFalse(installer.installed(model))
    }

    @Test
    fun `a voice cannot be installed against an unapproved model pack`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)
        val mismatched = one.copy(approval = one.approval?.copy(dependsOn = NeuralPackRef("app.narratify.other", "1.0.0")))

        assertFailsWith<NeuralVoicePackInstallException> { installer.install(mismatched) }
    }

    @Test
    fun `cancelling during the model download leaves no voice pack behind`() {
        val root = createTempDirectory("voice-pack-root").toFile()
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val installer = NeuralVoicePackInstaller(root, fixtureSource(bytes))
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)

        assertFailsWith<Throwable> { installer.install(one, isCancelled = { true }) }

        assertFalse(installer.installed(one))
        assertFalse(installer.installed(model))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-installing") })
    }

    @Test
    fun `a voice reports the shared model in its download size only until the model is installed`() {
        val bytes = mapOf(
            "kokoro-v1.0.onnx" to "model-data".encodeToByteArray(),
            "af_one.bin" to "one-data".encodeToByteArray(),
        )
        val model = modelFixture(bytes.filterKeys { it == "kokoro-v1.0.onnx" })
        val one = voiceFixture("one", "af_one", bytes, model)

        assertEquals(model.sizeBytes + one.sizeBytes, one.downloadBytes(dependencyInstalled = false))
        assertEquals(one.sizeBytes, one.downloadBytes(dependencyInstalled = true))
        assertEquals(model.sizeBytes, model.downloadBytes(dependencyInstalled = false))
    }

    private fun fixtureSource(files: Map<String, ByteArray>) = NeuralPackAssetSource { asset, destination, onBytes, isCancelled ->
        if (isCancelled()) throw InterruptedException("cancelled")
        val content = files.getValue(asset.relativePath)
        destination.parentFile?.mkdirs()
        destination.writeBytes(content)
        onBytes(content.size.toLong())
    }

    private fun countingSource(files: Map<String, ByteArray>, downloaded: MutableList<String>) =
        NeuralPackAssetSource { asset, destination, onBytes, _ ->
            downloaded.add(asset.relativePath)
            val content = files.getValue(asset.relativePath)
            destination.parentFile?.mkdirs()
            destination.writeBytes(content)
            onBytes(content.size.toLong())
        }

    private fun modelFixture(files: Map<String, ByteArray>): NeuralVoicePackCatalogEntry {
        val assets = downloadAssets(files)
        val approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.test.model",
            packVersion = "1.0.0",
            kind = NeuralPackKind.MODEL,
            runtimeId = "test-runtime",
            modelId = "test-model",
            modelVersion = "1.0.0",
            voiceId = "",
            voiceVersion = "",
            licenseSpdxId = "Apache-2.0",
            assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
        )
        return entry("Test model", approval, assets, dependency = null)
    }

    private fun voiceFixture(
        name: String,
        voiceId: String,
        files: Map<String, ByteArray>,
        model: NeuralVoicePackCatalogEntry,
    ): NeuralVoicePackCatalogEntry {
        val assets = downloadAssets(files.filterKeys { it == "$voiceId.bin" })
        val approval = ApprovedNeuralVoicePack(
            packId = "app.narratify.test.voice.$name",
            packVersion = "1.0.0",
            kind = NeuralPackKind.VOICE,
            runtimeId = "test-runtime",
            modelId = "test-model",
            modelVersion = "1.0.0",
            voiceId = voiceId,
            voiceVersion = "$voiceId-1.0.0",
            licenseSpdxId = "Apache-2.0",
            assets = assets.map(NeuralPackDownloadAsset::modelAsset).toSet(),
            dependsOn = NeuralPackRef(model.packId, model.packVersion),
        )
        return entry("Test voice $name", approval, assets, dependency = model)
    }

    private fun downloadAssets(files: Map<String, ByteArray>) = files.map { (path, bytes) ->
        NeuralPackDownloadAsset(path, "https://example.test/$path", bytes.size.toLong(), sha256(bytes))
    }

    private fun entry(
        displayName: String,
        approval: ApprovedNeuralVoicePack,
        assets: List<NeuralPackDownloadAsset>,
        dependency: NeuralVoicePackCatalogEntry?,
    ) = NeuralVoicePackCatalogEntry(
        displayName = displayName,
        detail = "Test only",
        schemaVersion = 2,
        kind = approval.kind,
        packId = approval.packId,
        packVersion = approval.packVersion,
        runtimeId = approval.runtimeId,
        modelId = approval.modelId,
        modelVersion = approval.modelVersion,
        voiceId = approval.voiceId,
        voiceVersion = approval.voiceVersion,
        languageTags = setOf("en-US"),
        licenseSpdxId = approval.licenseSpdxId,
        attribution = "Test",
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = assets,
        releaseStatus = NeuralPackReleaseStatus.AVAILABLE,
        runtimeBundled = true,
        approval = approval,
        dependency = dependency,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
