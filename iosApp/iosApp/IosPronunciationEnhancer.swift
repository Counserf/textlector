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
}