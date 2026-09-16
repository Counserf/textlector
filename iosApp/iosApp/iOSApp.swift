import SwiftUI
import ComposeApp

@main
struct iOSApp: App {

    init() {
        let repo = IosVoiceModelRepositoryImpl()
        let sherpaEngine = IosSherpaEngine(repository: repo)
        let supertonicEngine = IosSupertonicEngine()

        IosEngineHolder.shared.sherpaEngine = sherpaEngine
        IosEngineHolder.shared.supertonicEngine = supertonicEngine
        IosEngineHolder.shared.supertonicTts = supertonicEngine.tts
        IosEngineHolder.shared.pronunciationEnhancer = IosPronunciationEnhancer()
        IosEngineHolder.shared.remotePlaybackController = IosRemotePlaybackController()
        SupertonicHolder.shared.bridge = supertonicEngine.bridge

        IosEngineHolder.shared.tarExtractor = IosTarExtractor()
        IosEngineHolder.shared.ocrEngine = IosOcrEngine()
        IosEngineHolder.shared.fileDownloader = IosFileDownloader()
        IosEngineHolder.shared.pdfExtractor = IosPdfPageExtractor()
        MainViewControllerKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    handleOpenedDocument(url)
                }
        }
    }

    private func handleOpenedDocument(_ url: URL) {
        let lowerName = url.lastPathComponent.lowercased()
        guard lowerName.hasSuffix(".fb2") || lowerName.hasSuffix(".fb2.zip") || lowerName.hasSuffix(".zip") else {
            return
        }

        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let fileManager = FileManager.default
        let inbox = fileManager.temporaryDirectory.appendingPathComponent("TextLectorIncoming", isDirectory: true)

        do {
            try fileManager.createDirectory(at: inbox, withIntermediateDirectories: true)
            let destination = inbox.appendingPathComponent(url.lastPathComponent)
            if fileManager.fileExists(atPath: destination.path) {
                try fileManager.removeItem(at: destination)
            }
            try fileManager.copyItem(at: url, to: destination)
            MainViewControllerKt.handleIncomingFile(path: destination.path)
        } catch {
            MainViewControllerKt.handleIncomingFile(path: url.path)
        }
    }
}
