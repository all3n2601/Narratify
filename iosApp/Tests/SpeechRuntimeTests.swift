import Foundation
import XCTest
@testable import Narratify

final class SpeechRuntimeTests: XCTestCase {
    func testTimelinePublishesBoundariesFromRenderedSampleClock() throws {
        let requestID = UUID()
        var timeline = NeuralSampleTimeline()
        try timeline.append(NeuralAudioChunk(
            requestID: requestID,
            startFrame: 0,
            sampleRate: 24_000,
            samples: Array(repeating: 0, count: 1_200),
            wordTimings: [
                NeuralWordTiming(sourceRange: .init(location: 0, length: 5), startFrame: 0, endFrame: 400),
                NeuralWordTiming(sourceRange: .init(location: 6, length: 5), startFrame: 600, endFrame: 1_100),
            ],
            isFinal: true
        ))

        XCTAssertEqual(timeline.consumeBoundaries(through: 599), [.init(location: 0, length: 5)])
        XCTAssertEqual(timeline.consumeBoundaries(through: 600), [.init(location: 6, length: 5)])
        XCTAssertTrue(timeline.consumeBoundaries(through: 1_200).isEmpty)
    }

    func testTimelineRejectsDiscontinuousChunks() throws {
        let requestID = UUID()
        var timeline = NeuralSampleTimeline()
        try timeline.append(NeuralAudioChunk(
            requestID: requestID, startFrame: 0, sampleRate: 24_000,
            samples: [0, 0], wordTimings: [], isFinal: false
        ))

        XCTAssertThrowsError(try timeline.append(NeuralAudioChunk(
            requestID: requestID, startFrame: 3, sampleRate: 24_000,
            samples: [0], wordTimings: [], isFinal: true
        ))) { error in
            XCTAssertEqual(error as? NeuralTTSError, .discontinuousAudio(expected: 2, actual: 3))
        }
    }

    func testManifestRequiresExplicitCommercialApproval() {
        let manifest = NeuralModelManifest(
            modelID: "candidate", displayName: "Candidate", version: "1",
            licenseSPDX: "UNKNOWN", commercialUseApproved: false,
            languages: ["en-US"], sampleRate: 24_000
        )

        XCTAssertThrowsError(try manifest.validateForDistribution()) { error in
            XCTAssertEqual(error as? NeuralTTSError, .unapprovedLicense)
        }
    }

    func testPackCannotApproveItsOwnUnknownAssets() throws {
        let manifest = NeuralModelManifest(
            modelID: "candidate", displayName: "Candidate", version: "1",
            licenseSPDX: "Apache-2.0", commercialUseApproved: true,
            languages: ["en-US"], sampleRate: 24_000,
            assetSHA256: ["model.onnx": String(repeating: "a", count: 64)]
        )
        let reviewed = ApprovedNeuralModel(
            modelID: "candidate", version: "1", licenseSPDX: "Apache-2.0",
            sampleRate: 24_000,
            assetSHA256: ["model.onnx": String(repeating: "b", count: 64)]
        )

        try manifest.validateForDistribution()
        XCTAssertFalse(reviewed.matches(manifest))
    }

    func testSpeechRequestClampsUnsafeRateAndPitch() {
        let request = SpeechRequest(text: "Test", rate: 9, pitch: 0.1)
        XCTAssertEqual(request.rate, 2)
        XCTAssertEqual(request.pitch, 0.5)
    }

    func testKokoroDownloadStaysLockedUntilCommercialApprovalIsCompiledIntoTheApp() {
        let entry = NarratifyNeuralVoiceCatalog.kokoroEnglish

        XCTAssertFalse(entry.canDownload)
        XCTAssertThrowsError(try entry.validateForDownload()) { error in
            XCTAssertEqual(error as? NeuralVoicePackInstallError, .awaitingLicenseApproval)
        }
        XCTAssertEqual(
            Set(entry.assets.map(\.relativePath)),
            ["kokoro-v1.0.onnx", "af_heart.bin", "cmudict.dict", "LICENSE.cmudict.txt"]
        )
    }
}
