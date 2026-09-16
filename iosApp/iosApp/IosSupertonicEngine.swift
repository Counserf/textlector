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

    private func diag(_ message: String) {
        TtsDiagnosticLog.shared.append(tag: "SupertonicNative", message: message)
    }

    func loadVoice(model: VoiceModel) async throws {
        currentLang = model.language
        currentVoice = model.gender == VoiceGender.male ? "M1" : "F1"
        diag("loadVoice START voice=\(currentVoice) lang=\(currentLang)")

        let docsDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        let storageDir = docsDir + "/supertonic"
        bridge.load(storageDir: storageDir)
        diag("loadVoice END loaded=\(bridge.isLoaded)")
    }

    func setPlaylist(paragraphs: [LectorParagraph]) {
        self.paragraphs = paragraphs
        diag("setPlaylist count=\(paragraphs.count)")
    }

    func speak(index: Int32, speed: Float) async throws {
        guard index >= 0, Int(index) < paragraphs.count else { return }
        let paragraph = paragraphs[Int(index)]
        guard let prepared = paragraph.ttsText else {
            diag("speak ABORT index=\(index) pronunciation markup not ready")
            throw makeError("Отрывок \(index + 1) ещё не прошёл разметку произношения.")
        }
        diag("speak START index=\(index) prepared=true")
        let audio = try await generate(text: prepared, speed: speed)
        try await playAudio(audio: audio)
        diag("speak END index=\(index)")
    }

    func generate(text: String, speed: Float) async throws -> KotlinByteArray {
        diag("generate START chars=\(text.count) voice=\(currentVoice) lang=\(currentLang) speed=\(speed)")
        guard bridge.isLoaded else {
            diag("generate ABORT bridge not loaded")
            throw makeError(
                "Supertonic не загрузил ONNX-модели. Ожидаемый каталог: Documents/supertonic. " +
                "Удалите модель Supertonic в настройках и скачайте её заново."
            )
        }

        let styleJson = loadVoiceStyle(currentVoice)
        guard styleJson != "{}" else {
            diag("generate ABORT voice style missing")
            throw makeError("Не найден стиль голоса Supertonic \(currentVoice).json в bundle приложения.")
        }

        guard let data = bridge.generate(
            text: text,
            lang: currentLang,
            voiceStyleJson: styleJson,
            speed: speed,
            steps: 8
        ) else {
            diag("generate END nil")
            throw makeError(
                "Supertonic не смог сгенерировать аудио. Модель: Supertonic v3 / \(currentVoice), язык: \(currentLang)."
            )
        }

        guard !data.isEmpty else {
            diag("generate END empty")
            throw makeError("Supertonic вернул пустой WAV-файл.")
        }
        diag("generate END bytes=\(data.count)")
        return data.toKotlinByteArray()
    }

    func playAudio(audio: KotlinByteArray) async throws {
        guard audio.size > 0 else {
            diag("play ABORT empty")
            throw makeError("Supertonic: попытка воспроизвести пустой аудиобуфер.")
        }
        diag("play START bytes=\(audio.size)")
        bridge.playWav(data: audio.toData())
        diag("play END")
    }

    func stop() {
        diag("stop")
        bridge.cancel()
        bridge.stopPlayback()
    }

    func shutdown() {
        diag("shutdown")
        bridge.cancel()
        bridge.close()
    }

    private func loadVoiceStyle(_ voice: String) -> String {
        let name = voice.uppercased()
        let candidates: [URL?] = [
            Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "supertonic/voice_styles"),
            Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "voice_styles")
        ]

        for candidate in candidates {
            if let url = candidate,
               let json = try? String(contentsOf: url, encoding: .utf8) {
                return json
            }
        }
        return "{}"
    }

    private func makeError(_ message: String) -> NSError {
        NSError(domain: "TextLector.Supertonic", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}

/// Deterministic RUAccent dictionaries for ordinary stress and ё. The singleton
/// is explicitly releasable so several large Swift dictionaries do not remain in
/// memory when background markup hands control over to Piper/Supertonic.
final class RussianPronunciationDictionary {
    private static let instanceLock = NSLock()
    private static var instance: RussianPronunciationDictionary?

    static var shared: RussianPronunciationDictionary {
        instanceLock.lock()
        defer { instanceLock.unlock() }
        if let instance { return instance }
        let created = RussianPronunciationDictionary()
        instance = created
        return created
    }

    static func releaseShared() {
        instanceLock.lock()
        instance = nil
        instanceLock.unlock()
        print("[RussianPronunciationDictionary] resources released")
    }

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
        wordRegex = try! NSRegularExpression(pattern: "[А-Яа-яЁё\\u{0301}]+")
        print("[RussianPronunciationDictionary] loaded: accents=\(accents.count), yo=\(yoWords.count), homographs=\(homographs.count), yoHomographs=\(yoHomographs.count)")
    }

    func process(_ text: String) -> String {
        guard !text.isEmpty, !accents.isEmpty || !yoWords.isEmpty else { return text }

        let originalText = text as NSString
        let fullRange = NSRange(location: 0, length: originalText.length)
        let matches = wordRegex.matches(in: text, range: fullRange)
        guard !matches.isEmpty else { return text }

        let output = NSMutableString(string: text)
        for match in matches.reversed() {
            let original = originalText.substring(with: match.range)
            if original.contains("\u{0301}") { continue }

            let lower = original.lowercased()
            var candidate = original

            // Context-dependent ё forms are intentionally not guessed by a static
            // dictionary. Safe, non-homographic ё replacements are still applied.
            if !yoHomographs.contains(lower), let yo = yoWords[lower] {
                candidate = Self.preserveCase(source: original, replacement: yo)
            }

            let normalizedKey = candidate.lowercased()
            let isContextual = homographs.contains(lower) || homographs.contains(normalizedKey)
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
        guard let url = Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "pronunciation/ru") else {
            print("[RussianPronunciationDictionary] missing resource: \(name).json")
            return [:]
        }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
            var result: [String: String] = [:]
            result.reserveCapacity(object.count)
            for (key, value) in object {
                if let string = value as? String { result[key.lowercased()] = string }
            }
            return result
        } catch {
            print("[RussianPronunciationDictionary] load error \(name): \(error)")
            return [:]
        }
    }

    private static func loadKeys(_ name: String) -> Set<String> {
        guard let url = Bundle.main.url(forResource: name, withExtension: "json", subdirectory: "pronunciation/ru") else {
            print("[RussianPronunciationDictionary] missing resource: \(name).json")
            return []
        }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
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
        if source == source.uppercased() { return replacement.uppercased() }
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
