package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class VoiceSelectionTest {
    private val heart = pack("app.narratify.kokoro.en-us.heart", "af_heart")
    private val bella = pack("app.narratify.kokoro.en-us.bella", "af_bella")
    private val rank = { packId: String ->
        listOf(heart.packId, bella.packId).indexOf(packId).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    @Test
    fun `selections survive a store and parse round trip`() {
        listOf(
            VoiceSelection.Automatic,
            VoiceSelection.SystemVoice("en-us-x-sfg#female_1"),
            VoiceSelection.NeuralVoice(heart.packId),
        ).forEach { selection ->
            assertEquals(selection, VoiceSelection.parse(selection.store()))
        }
    }

    @Test
    fun `unknown and legacy stored values degrade safely`() {
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse(null))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse(""))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parse("nonsense"))
        assertEquals(VoiceSelection.SystemVoice("legacy-voice"), VoiceSelection.parseLegacyVoiceId("legacy-voice"))
        assertEquals(VoiceSelection.Automatic, VoiceSelection.parseLegacyVoiceId(null))
    }

    @Test
    fun `a chosen neural voice is picked out of the installed voices`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.NeuralVoice(bella.packId), listOf(heart, bella), rank)

        assertEquals(bella, resolved.neuralPack)
        assertTrue(resolved.reason.contains("chose", ignoreCase = true))
    }

    @Test
    fun `a chosen neural voice falls back to system speech when it is not installed`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.NeuralVoice(bella.packId), listOf(heart), rank)

        assertNull(resolved.neuralPack)
        assertTrue(resolved.reason.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `a chosen system voice is never overridden by an installed neural pack`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.SystemVoice("en-us-x-sfg#female_1"), listOf(heart), rank)

        assertNull(resolved.neuralPack)
        assertEquals("en-us-x-sfg#female_1", resolved.systemVoiceName)
    }

    @Test
    fun `automatic follows catalog order rather than install order`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.Automatic, listOf(bella, heart), rank)

        assertEquals(heart, resolved.neuralPack)
    }

    @Test
    fun `automatic uses system speech when no neural voice is installed`() {
        val resolved = VoiceRouter.resolve(VoiceSelection.Automatic, emptyList(), rank)

        assertNull(resolved.neuralPack)
        assertNull(resolved.systemVoiceName)
    }

    /**
     * Every test above injects its own rank, so the production default the app actually passes to
     * [VoiceRouter.resolve] would otherwise never be exercised.
     */
    @Test
    fun `the catalog preference rank follows the published voice grades`() {
        val ranks = listOf("heart", "bella", "nicole", "michael", "fenrir", "puck")
            .map { NarratifyNeuralVoiceCatalog.preferenceRank("app.narratify.kokoro.en-us.$it") }

        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct(), ranks)
        assertEquals(Int.MAX_VALUE, NarratifyNeuralVoiceCatalog.preferenceRank("app.narratify.kokoro.en-us.nobody"))
    }

    private fun pack(packId: String, voiceId: String) = NeuralVoicePack(
        directory = File("/tmp/$packId"),
        schemaVersion = 2,
        kind = NeuralPackKind.VOICE,
        packId = packId,
        packVersion = "1.0.0",
        runtimeId = "narratify-kokoro-onnx",
        modelId = "kokoro-82m-v1.0-fp32-duration",
        modelVersion = "model-files-v1.1",
        voiceId = voiceId,
        voiceVersion = "${voiceId.replace('_', '-')}-1939ad2",
        languageTags = setOf("en-US"),
        licenseSpdxId = "Apache-2.0",
        commercialUseAllowed = true,
        attribution = "Kokoro-82M",
        pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
        assets = emptyList(),
        dependsOn = NeuralPackRef("app.narratify.kokoro.model", "1.0.0"),
    )
}
