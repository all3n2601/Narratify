package app.narratify

import com.narratify.domain.PcmEncoding
import com.narratify.domain.PcmFormat
import java.io.File
import java.security.MessageDigest
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeuralVoicePackVerifierTest {
    @Test
    fun acceptsAnIntactCommercialPack() {
        val fixture = fixture()
        NeuralVoicePackVerifier.verify(fixture.pack)
    }

    @Test
    fun rejectsTamperedModelBytes() {
        val fixture = fixture()
        val approvedLength = fixture.asset.length()
        // Same length as the approved bytes, so the SHA-256 comparison is what refuses this and
        // not the cheaper size check that runs before it.
        fixture.asset.writeText("tampered model bytes")
        assertEquals(approvedLength, fixture.asset.length())

        val failure = assertFailsWith<VoicePackValidationException> { NeuralVoicePackVerifier.verify(fixture.pack) }

        assertTrue(failure.message.orEmpty().contains("checksum"), failure.message)
    }

    @Test
    fun rejectsPathEscapingThePackDirectory() {
        val fixture = fixture()
        val outside = File(fixture.pack.directory.parentFile, "outside-${System.nanoTime()}.bin").apply { writeText("outside") }
        val unsafe = fixture.pack.copy(
            assets = listOf(NeuralModelAsset("../${outside.name}", outside.length(), sha256(outside))),
        )
        try {
            assertFailsWith<VoicePackValidationException> { NeuralVoicePackVerifier.verify(unsafe) }
        } finally {
            outside.delete()
        }
    }

    @Test
    fun rejectsLicenseWithoutExplicitCommercialPermission() {
        val fixture = fixture()
        assertFailsWith<VoicePackValidationException> {
            NeuralVoicePackVerifier.verify(fixture.pack.copy(commercialUseAllowed = false))
        }
    }

    @Test
    fun registryReturnsOnlyACompatibleRuntime() {
        val fixture = fixture()
        val runtime = object : OnDeviceNeuralTtsRuntime {
            override val runtimeId = fixture.pack.runtimeId
            override fun supports(pack: NeuralVoicePack) = true
            override fun open(pack: NeuralVoicePack): NeuralTtsSession = error("Not used")
        }
        assertNull(NeuralTtsRuntimeRegistry.compatible(fixture.pack))
        try {
            NeuralTtsRuntimeRegistry.register(runtime, setOf(fixture.pack.approval()))
            assertNotNull(NeuralTtsRuntimeRegistry.compatible(fixture.pack))
        } finally {
            NeuralTtsRuntimeRegistry.unregister(runtime.runtimeId)
        }
    }

    @Test
    fun registryRejectsASelfAssertedButUnapprovedPack() {
        val fixture = fixture()
        val runtime = object : OnDeviceNeuralTtsRuntime {
            override val runtimeId = fixture.pack.runtimeId
            override fun supports(pack: NeuralVoicePack) = true
            override fun open(pack: NeuralVoicePack): NeuralTtsSession = error("Not used")
        }
        val differentApproval = fixture.pack.copy(packVersion = "reviewed-version").approval()
        try {
            NeuralTtsRuntimeRegistry.register(runtime, setOf(differentApproval))
            assertNull(NeuralTtsRuntimeRegistry.compatible(fixture.pack))
        } finally {
            NeuralTtsRuntimeRegistry.unregister(runtime.runtimeId)
        }
    }

    private fun fixture(): Fixture {
        val directory = createTempDirectory("voice-pack").toFile().apply { deleteOnExit() }
        val asset = File(directory, "voice.bin").apply { writeText("verified model bytes"); deleteOnExit() }
        return Fixture(
            asset,
            NeuralVoicePack(
                directory = directory,
                schemaVersion = 2,
                kind = NeuralPackKind.MODEL,
                packId = "test-pack",
                packVersion = "1.0.0",
                runtimeId = "test-runtime",
                modelId = "test-model",
                modelVersion = "1.0.0",
                voiceId = "",
                voiceVersion = "",
                languageTags = setOf("en-US"),
                licenseSpdxId = "Apache-2.0",
                commercialUseAllowed = true,
                attribution = "Test only",
                pcmFormat = PcmFormat(24_000, 1, PcmEncoding.SIGNED_INT_16_LE),
                assets = listOf(NeuralModelAsset(asset.name, asset.length(), sha256(asset))),
            ),
        )
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun NeuralVoicePack.approval() = ApprovedNeuralVoicePack(
        packId = packId,
        packVersion = packVersion,
        kind = kind,
        runtimeId = runtimeId,
        modelId = modelId,
        modelVersion = modelVersion,
        voiceId = voiceId,
        voiceVersion = voiceVersion,
        licenseSpdxId = licenseSpdxId,
        assets = assets.toSet(),
        dependsOn = dependsOn,
    )

    private data class Fixture(val asset: File, val pack: NeuralVoicePack)
}
