import Foundation
import ComposeApp

/// Heavy Russian pronunciation work is intentionally kept out of the playback path.
/// The Kotlin background worker calls this once per paragraph and persists the result.
@objc final class IosPronunciationEnhancer: NSObject, NativePronunciationEnhancer {
    private func diag(_ message: String) {
        TtsDiagnosticLog.shared.append(tag: "PronunciationNative", message: message)
    }

    func enhance(text: String, language: String) -> String {
        guard language.lowercased().hasPrefix("ru"), !text.isEmpty else { return text }

        diag("enhance START chars=\(text.count)")
        return autoreleasepool {
            diag("homograph START")
            let contextual = RussianHomographResolver.shared.process(text)
            diag("homograph END chars=\(contextual.count)")

            diag("dictionary START")
            let prepared = RussianPronunciationDictionary.shared.process(contextual)
            diag("dictionary END chars=\(prepared.count)")
            diag("enhance END chars=\(prepared.count)")
            return prepared
        }
    }

    func releaseResources() {
        diag("release START")
        RussianHomographResolver.shared.releaseResources()
        RussianPronunciationDictionary.releaseShared()
        diag("release END")
    }
}
