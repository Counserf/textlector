import Foundation
import ComposeApp

/// Heavy Russian pronunciation work is intentionally kept out of the playback path.
/// The Kotlin background worker calls this once per paragraph and persists the result.
@objc final class IosPronunciationEnhancer: NSObject, NativePronunciationEnhancer {
    func enhance(text: String, language: String) -> String {
        guard language.lowercased().hasPrefix("ru"), !text.isEmpty else { return text }

        return autoreleasepool {
            let contextual = RussianHomographResolver.shared.process(text)
            return RussianPronunciationDictionary.shared.process(contextual)
        }
    }

    func releaseResources() {
        // Lifecycle hook used after a book has been fully marked up. The current
        // RUAccent implementation keeps one shared session to avoid repeated model
        // loads; playback itself never touches it. A later model wrapper can release
        // the ORT session here without changing the Kotlin processing pipeline.
    }
}