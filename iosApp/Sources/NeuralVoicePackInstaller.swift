import CryptoKit
import Foundation

enum NeuralPackReleaseStatus: Sendable {
    case awaitingLicenseApproval
    case available
}

struct NeuralPackDownloadAsset: Equatable, Sendable {
    let relativePath: String
    let remoteURL: URL
    let sizeBytes: Int64
    let sha256: String
}

struct NeuralVoicePackCatalogEntry: Sendable {
    let displayName: String
    let detail: String
    let manifest: NeuralModelManifest
    let assets: [NeuralPackDownloadAsset]
    let releaseStatus: NeuralPackReleaseStatus
    /// True only when this app build contains the matching reviewed native runtime/frontend.
    let runtimeBundled: Bool
    /// Added only in the reviewed app release that is permitted to distribute this pack.
    let approval: ApprovedNeuralModel?

    var sizeBytes: Int64 { assets.reduce(0) { $0 + $1.sizeBytes } }
    var canDownload: Bool { releaseStatus == .available && runtimeBundled && approval != nil }

    func validateForDownload() throws {
        guard canDownload, let approval else { throw NeuralVoicePackInstallError.awaitingLicenseApproval }
        try manifest.validateForDistribution()
        guard approval.matches(manifest) else { throw NeuralVoicePackInstallError.approvalMismatch }
        guard !assets.isEmpty, assets.count <= 32 else { throw NeuralVoicePackInstallError.invalidCatalog }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        var paths = Set<String>()
        for asset in assets {
            let path = asset.relativePath
            let lower = path.lowercased()
            guard !path.isEmpty, !path.hasPrefix("/"), !path.contains(".."),
                  path.unicodeScalars.allSatisfy(allowed.contains), paths.insert(path).inserted,
                  asset.remoteURL.scheme == "https", asset.sizeBytes > 0,
                  asset.sha256.count == 64, asset.sha256.allSatisfy(\.isHexDigit),
                  !Self.forbiddenSuffixes.contains(where: lower.hasSuffix)
            else { throw NeuralVoicePackInstallError.invalidCatalog }
            guard manifest.assetSHA256[path] == asset.sha256 else {
                throw NeuralVoicePackInstallError.approvalMismatch
            }
        }
        guard Set(manifest.assetSHA256.keys) == Set(paths) else {
            throw NeuralVoicePackInstallError.approvalMismatch
        }
    }

    private static let forbiddenSuffixes = [
        ".dylib", ".framework", ".so", ".dex", ".jar", ".class", ".js", ".py", ".sh", ".app",
    ]
}

enum NarratifyNeuralVoiceCatalog {
    /// Visible in the app so its status is honest, but deliberately impossible to download until
    /// counsel approves the exact graph, voices, notices, and permissive English frontend.
    static let kokoroEnglish = NeuralVoicePackCatalogEntry(
        displayName: "Kokoro English · Heart",
        detail: "Natural English narration downloaded once and used entirely offline.",
        manifest: NeuralModelManifest(
            modelID: "kokoro-82m-v1.0-fp32-duration",
            displayName: "Kokoro English · Heart",
            version: "model-files-v1.1",
            licenseSPDX: "Apache-2.0",
            commercialUseApproved: false,
            languages: ["en-US"],
            sampleRate: 24_000,
            assetSHA256: [
                "kokoro-v1.0.onnx": "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
                "af_heart.bin": "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b",
                "cmudict.dict": "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22",
                "LICENSE.cmudict.txt": "bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a",
            ]
        ),
        assets: [
            NeuralPackDownloadAsset(
                relativePath: "kokoro-v1.0.onnx",
                remoteURL: URL(string: "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx")!,
                sizeBytes: 325_505_369,
                sha256: "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a"
            ),
            NeuralPackDownloadAsset(
                relativePath: "af_heart.bin",
                remoteURL: URL(string: "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/1939ad2a8e416c0acfeecc08a694d14ef25f2231/voices/af_heart.bin")!,
                sizeBytes: 522_240,
                sha256: "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b"
            ),
            NeuralPackDownloadAsset(
                relativePath: "cmudict.dict",
                remoteURL: URL(string: "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/cmudict.dict")!,
                sizeBytes: 3_618_488,
                sha256: "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22"
            ),
            NeuralPackDownloadAsset(
                relativePath: "LICENSE.cmudict.txt",
                remoteURL: URL(string: "https://raw.githubusercontent.com/cmusphinx/cmudict/74790861f652b15e4ac49015a90074ad62a27690/LICENSE")!,
                sizeBytes: 1_754,
                sha256: "bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a"
            ),
        ],
        releaseStatus: .awaitingLicenseApproval,
        runtimeBundled: false,
        approval: nil
    )
}

enum NeuralVoicePackInstallError: Error, Equatable, LocalizedError {
    case awaitingLicenseApproval
    case invalidCatalog
    case insufficientStorage
    case invalidResponse
    case assetSizeMismatch(String)
    case assetHashMismatch(String)
    case approvalMismatch
    case activationFailed

    var errorDescription: String? {
        switch self {
        case .awaitingLicenseApproval: "This voice is awaiting commercial licensing approval."
        case .invalidCatalog: "The voice-pack catalog entry is invalid."
        case .insufficientStorage: "There is not enough free space to install this voice."
        case .invalidResponse: "The voice-pack download returned an invalid response."
        case let .assetSizeMismatch(path): "The downloaded size did not match the approved catalog: \(path)"
        case let .assetHashMismatch(path): "The downloaded checksum did not match the approved catalog: \(path)"
        case .approvalMismatch: "The voice pack does not match the app-reviewed approval."
        case .activationFailed: "The downloaded voice could not be activated."
        }
    }
}

actor NeuralVoicePackInstaller {
    private let root: URL
    private let fileManager: FileManager

    init(root: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        if let root {
            self.root = root
        } else {
            let support = try! fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            self.root = support.appending(path: "Narratify/tts-models", directoryHint: .isDirectory)
        }
    }

    func isInstalled(_ entry: NeuralVoicePackCatalogEntry) -> Bool {
        guard entry.approval != nil else { return false }
        return (try? verifyInstalled(entry, at: targetURL(for: entry))) != nil
    }

    func install(_ entry: NeuralVoicePackCatalogEntry) async throws {
        try entry.validateForDownload()
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        var storage = root
        var storageValues = URLResourceValues()
        storageValues.isExcludedFromBackup = true
        try storage.setResourceValues(storageValues)
        let values = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        if let available = values.volumeAvailableCapacityForImportantUsage,
           available < entry.sizeBytes + 128 * 1024 * 1024 {
            throw NeuralVoicePackInstallError.insufficientStorage
        }

        let target = targetURL(for: entry)
        if (try? verifyInstalled(entry, at: target)) != nil { return }
        let staging = childURL(".\(directoryName(for: entry))-installing")
        try? removeOwnedDirectory(staging)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: false)

        do {
            for asset in entry.assets {
                try Task.checkCancellation()
                let (temporary, response) = try await URLSession.shared.download(from: asset.remoteURL)
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode), response.url?.scheme == "https"
                else { throw NeuralVoicePackInstallError.invalidResponse }
                let destination = staging.appending(path: asset.relativePath, directoryHint: .notDirectory)
                try fileManager.moveItem(at: temporary, to: destination)
                try verify(asset, at: destination)
            }
            let manifestData = try JSONEncoder().encode(entry.manifest)
            try manifestData.write(to: staging.appending(path: "manifest.json"), options: .atomic)
            try verifyInstalled(entry, at: staging)
            if fileManager.fileExists(atPath: target.path) { try removeOwnedDirectory(target) }
            try fileManager.moveItem(at: staging, to: target)
            try verifyInstalled(entry, at: target)
        } catch {
            try? removeOwnedDirectory(staging)
            throw error
        }
    }

    func remove(_ entry: NeuralVoicePackCatalogEntry) throws {
        let target = targetURL(for: entry)
        if fileManager.fileExists(atPath: target.path) { try removeOwnedDirectory(target) }
    }

    @discardableResult
    private func verifyInstalled(_ entry: NeuralVoicePackCatalogEntry, at directory: URL) throws -> NeuralModelManifest {
        let manifestURL = directory.appending(path: "manifest.json")
        let manifest = try JSONDecoder().decode(NeuralModelManifest.self, from: Data(contentsOf: manifestURL))
        try manifest.validateForDistribution()
        guard entry.approval?.matches(manifest) == true, manifest == entry.manifest else {
            throw NeuralVoicePackInstallError.approvalMismatch
        }
        for asset in entry.assets {
            try verify(asset, at: directory.appending(path: asset.relativePath))
        }
        return manifest
    }

    private func verify(_ asset: NeuralPackDownloadAsset, at url: URL) throws {
        let attributes = try fileManager.attributesOfItem(atPath: url.path)
        guard (attributes[.size] as? NSNumber)?.int64Value == asset.sizeBytes else {
            throw NeuralVoicePackInstallError.assetSizeMismatch(asset.relativePath)
        }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard digest == asset.sha256 else {
            throw NeuralVoicePackInstallError.assetHashMismatch(asset.relativePath)
        }
    }

    private func directoryName(for entry: NeuralVoicePackCatalogEntry) -> String {
        "\(entry.manifest.modelID)-\(entry.manifest.version)"
    }

    private func targetURL(for entry: NeuralVoicePackCatalogEntry) -> URL {
        childURL(directoryName(for: entry))
    }

    private func childURL(_ name: String) -> URL {
        precondition(!name.contains("/") && !name.contains(".."))
        return root.appending(path: name, directoryHint: .isDirectory)
    }

    private func removeOwnedDirectory(_ url: URL) throws {
        let parent = url.deletingLastPathComponent().standardizedFileURL
        guard parent == root.standardizedFileURL else { throw NeuralVoicePackInstallError.activationFailed }
        try fileManager.removeItem(at: url)
    }
}

@MainActor
final class NeuralVoicePackLibrary: ObservableObject {
    enum State: Equatable {
        case checking
        case locked
        case available
        case downloading
        case installed
        case failed(String)
    }

    @Published private(set) var state: State = .checking
    let entry = NarratifyNeuralVoiceCatalog.kokoroEnglish
    private let installer: NeuralVoicePackInstaller
    private var task: Task<Void, Never>?

    init(installer: NeuralVoicePackInstaller = NeuralVoicePackInstaller()) {
        self.installer = installer
        refresh()
    }

    func refresh() {
        task?.cancel()
        task = Task {
            let installed = await installer.isInstalled(entry)
            if installed {
                state = .installed
            } else {
                state = entry.canDownload ? .available : .locked
            }
        }
    }

    func install() {
        guard entry.canDownload else { state = .locked; return }
        state = .downloading
        task = Task {
            do {
                try await installer.install(entry)
                state = .installed
            } catch is CancellationError {
                state = .available
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
    }

    func cancel() {
        task?.cancel()
        state = entry.canDownload ? .available : .locked
    }

    func remove() {
        task = Task {
            do {
                try await installer.remove(entry)
                state = entry.canDownload ? .available : .locked
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
    }
}
