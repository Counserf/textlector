//
// Created by Денис Хамидуллин on 28.05.2026.
//

import Foundation
import AVFAudio
import ComposeApp

@objc class IosSupertonicEngine: NSObject, SherpaOnnxTtsEngine {

    let bridge = SupertonicBridge()
    private var paragraphs: [LectorParagraph] = []
    private var currentLang: String = "en"
    private var currentVoice: String = "M1"

    let tts: SupertonicTts = {
        let docsDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        let storageDir = docsDir + "/supertonic"
        print("[IosSupertonicEngine] tts storageDir: \(storageDir)")
        return SupertonicTts(config: SupertonicConfig(
            storageDir: storageDir,
            inferenceSteps: 7,
            defaultLang: "en",
            defaultVoice: SupertonicVoice.m1,
            defaultSpeed: 1.0,
            huggingFaceBaseUrl: SupertonicConfig.companion.DEFAULT_HF_URL
        ))
    }()

    func loadVoice(model: VoiceModel) async throws {
        currentLang = model.language
        currentVoice = model.gender == VoiceGender.male ? "M1" : "F1"

        // supertonic-kmp downloads files directly into storageDir. MODEL_FILES contain
        // the remote "onnx/..." prefix, but ModelDownloader flattens them to fileName.
        // Therefore the Swift bridge must receive Documents/supertonic, not /onnx.
        let docsDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        let storageDir = docsDir + "/supertonic"
        print("[IosSupertonicEngine] loading bridge from: \(storageDir)")
        bridge.load(storageDir: storageDir)
        print("[IosSupertonicEngine] bridge.isLoaded: \(bridge.isLoaded)")
    }

    func setPlaylist(paragraphs: [LectorParagraph]) {
        self.paragraphs = paragraphs
    }

    func speak(index: Int32, speed: Float) async throws {
        guard Int(index) < paragraphs.count else { return }
        let text = paragraphs[Int(index)].text
        let audio = try await generate(text: text, speed: speed)
        try await playAudio(audio: audio)
    }

    func generate(text: String, speed: Float) async throws -> KotlinByteArray {
        guard bridge.isLoaded else {
            throw makeError(
                "Supertonic не загрузил ONNX-модели. Ожидаемый каталог: Documents/supertonic. " +
                "Удалите модель Supertonic в настройках и скачайте её заново."
            )
        }

        let styleJson = loadVoiceStyle(currentVoice)
        print("[IosSupertonicEngine] generate: voice=\(currentVoice), lang=\(currentLang), styleJson empty=\(styleJson == "{}")")

        guard styleJson != "{}" else {
            throw makeError(
                "Не найден стиль голоса Supertonic \(currentVoice).json в bundle приложения."
            )
        }

        // Common Kotlin first normalizes Russian numbers and high-confidence rules.
        // Then the lazy tiny2.1 classifier resolves remaining context-dependent
        // homographs; finally the deterministic RUAccent dictionary fills ordinary
        // stress and ё. The displayed book text is never changed.
        let contextualText = currentLang.lowercased().hasPrefix("ru")
            ? RussianHomographResolver.shared.process(text)
            : text
        let preparedText = currentLang.lowercased().hasPrefix("ru")
            ? RussianPronunciationDictionary.shared.process(contextualText)
            : contextualText

        guard let data = bridge.generate(
            text: preparedText,
            lang: currentLang,
            voiceStyleJson: styleJson,
            speed: speed,
            steps: 8
        ) else {
            print("[IosSupertonicEngine] bridge.generate returned nil")
            throw makeError(
                "Supertonic не смог сгенерировать аудио. Модель: Supertonic v3 / \(currentVoice), язык: \(currentLang)."
            )
        }

        guard !data.isEmpty else {
            throw makeError("Supertonic вернул пустой WAV-файл.")
        }

        print("[IosSupertonicEngine] generate success: \(data.count) bytes")
        return data.toKotlinByteArray()
    }

    func playAudio(audio: KotlinByteArray) async throws {
        guard audio.size > 0 else {
            throw makeError("Supertonic: попытка воспроизвести пустой аудиобуфер.")
        }
        bridge.playWav(data: audio.toData())
    }

    func stop() {
        bridge.cancel()
        bridge.stopPlayback()
    }

    func shutdown() {
        bridge.cancel()
        bridge.close()
    }

    private func loadVoiceStyle(_ voice: String) -> String {
        let name = voice.uppercased()
        let candidates: [URL?] = [
            Bundle.main.url(
                forResource: name,
                withExtension: "json",
                subdirectory: "supertonic/voice_styles"
            ),
            Bundle.main.url(
                forResource: name,
                withExtension: "json",
                subdirectory: "voice_styles"
            )
        ]

        for candidate in candidates {
            if let url = candidate,
               let json = try? String(contentsOf: url, encoding: .utf8) {
                print("[IosSupertonicEngine] voice style loaded from: \(url.path)")
                return json
            }
        }

        print("[IosSupertonicEngine] voice style not found: \(voice)")
        return "{}"
    }

    private func makeError(_ message: String) -> NSError {
        NSError(
            domain: "TextLector.Supertonic",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
    }
}

/// Broad deterministic Russian pronunciation dictionary shared by Piper and
/// Supertonic on iOS. The bundle contains RUAccent's compact dictionaries only;
/// contextual homographs are deliberately skipped here so they are not assigned
/// the wrong meaning. They are resolved by RussianHomographResolver first.
final class RussianPronunciationDictionary {
    static let shared = RussianPronunciationDictionary()

    private let accents: [String: String]
    private let yoWords: [String: String]
    private let homographs: Set<String>
    private let yoHomographs: Set<String>
    private let wordRegex: NSRegularExpression

    private init() {
        accents = Self.loadStringMap("accents_nn")
        yoWords = Self.loadStringMap("yo_words")
        homographs = Self.loadKeys("omographs")
        yoHomographs = Self.loadKeys("yo_homographs")
        wordRegex = try! NSRegularExpression(pattern: "[А-Яа-яЁё\u{0301}]+")

        print(
            "[RussianPronunciationDictionary] loaded: accents=\(accents.count), " +
            "yo=\(yoWords.count), homographs=\(homographs.count), yoHomographs=\(yoHomographs.count)"
        )
    }

    func process(_ text: String) -> String {
        guard !text.isEmpty, !accents.isEmpty || !yoWords.isEmpty else { return text }

        let originalText = text as NSString
        let fullRange = NSRange(location: 0, length: originalText.length)
        let matches = wordRegex.matches(in: text, range: fullRange)
        guard !matches.isEmpty else { return text }

        // Replace backwards so UTF-16 ranges obtained from the original string
        // remain valid when combining accents make replacement strings longer.
        let output = NSMutableString(string: text)
        for match in matches.reversed() {
            let original = originalText.substring(with: match.range)
            if original.contains("\u{0301}") { continue }

            let lower = original.lowercased()
            var candidate = original

            // е/ё can itself be contextual (e.g. «все/всё»), so do not force
            // deterministic ё for entries explicitly listed as yo-homographs.
            if !yoHomographs.contains(lower), let yo = yoWords[lower] {
                candidate = Self.preserveCase(source: original, replacement: yo)
            }

            let normalizedKey = candidate.lowercased()
            let isContextual = homographs.contains(lower) || homographs.contains(normalizedKey)

            // accents_nn uses RUAccent's '+' immediately before the stressed
            // vowel. Convert it to U+0301 combining acute, which our neural TTS
            // preprocessing already uses.
            if !isContextual,
               let rawAccent = accents[normalizedKey] ?? accents[lower] {
                let accented = Self.plusToCombiningAcute(rawAccent)
                candidate = Self.preserveCase(source: original, replacement: accented)
            }

            if candidate != original {
                output.replaceCharacters(in: match.range, with: candidate)
            }
        }

        return output as String
    }

    private static func loadStringMap(_ name: String) -> [String: String] {
        guard let url = Bundle.main.url(
            forResource: name,
            withExtension: "json",
            subdirectory: "pronunciation/ru"
        ) else {
            print("[RussianPronunciationDictionary] missing resource: \(name).json")
            return [:]
        }

        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                print("[RussianPronunciationDictionary] invalid dictionary JSON: \(name)")
                return [:]
            }
            var result: [String: String] = [:]
            result.reserveCapacity(object.count)
            for (key, value) in object {
                if let string = value as? String {
                    result[key.lowercased()] = string
                }
            }
            return result
        } catch {
            print("[RussianPronunciationDictionary] load error \(name): \(error)")
            return [:]
        }
    }

    private static func loadKeys(_ name: String) -> Set<String> {
        guard let url = Bundle.main.url(
            forResource: name,
            withExtension: "json",
            subdirectory: "pronunciation/ru"
        ) else {
            print("[RussianPronunciationDictionary] missing resource: \(name).json")
            return []
        }

        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                print("[RussianPronunciationDictionary] invalid key dictionary JSON: \(name)")
                return []
            }
            return Set(object.keys.map { $0.lowercased() })
        } catch {
            print("[RussianPronunciationDictionary] load error \(name): \(error)")
            return []
        }
    }

    private static func plusToCombiningAcute(_ value: String) -> String {
        var result = String()
        result.reserveCapacity(value.count + 1)
        var accentNext = false

        for character in value {
            if character == "+" {
                accentNext = true
                continue
            }
            result.append(character)
            if accentNext {
                result.append("\u{0301}")
                accentNext = false
            }
        }
        return result
    }

    private static func preserveCase(source: String, replacement: String) -> String {
        guard !source.isEmpty, !replacement.isEmpty else { return replacement }

        if source == source.uppercased() {
            return replacement.uppercased()
        }
        if source.first?.isUppercase == true {
            return replacement.prefix(1).uppercased() + String(replacement.dropFirst())
        }
        return replacement
    }
}

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() {
            result.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return result
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var data = Data(count: Int(size))
        for i in 0..<Int(size) {
            data[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return data
    }
}
