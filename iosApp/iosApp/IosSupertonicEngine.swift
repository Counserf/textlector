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

/// Compact mmap-friendly RUAccent dictionary. The binary file keeps a sorted
/// fixed-width index followed by UTF-8 bytes, so lookups do not materialize
/// hundreds of thousands of Swift Dictionary/String objects in RAM.
final class RuAccentPack {
    private static let magic: [UInt8] = [82, 65, 80, 65, 67, 75, 49, 0] // RAPACK1\0
    private static let headerSize = 16
    private static let expectedEntrySize = 16

    private let data: Data
    private let count: Int
    private let entrySize: Int
    private let blobOffset: Int

    convenience init?(resource name: String) {
        guard let url = Bundle.main.url(
            forResource: name,
            withExtension: "rapack",
            subdirectory: "pronunciation/ru"
        ) else {
            print("[RuAccentPack] missing resource: \(name).rapack")
            return nil
        }
        self.init(url: url)
    }

    init?(url: URL) {
        guard let mapped = try? Data(contentsOf: url, options: .mappedIfSafe),
              mapped.count >= Self.headerSize else {
            print("[RuAccentPack] failed to mmap \(url.lastPathComponent)")
            return nil
        }

        let validMagic = mapped.withUnsafeBytes { raw -> Bool in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard bytes.count >= Self.magic.count else { return false }
            for i in 0..<Self.magic.count where bytes[i] != Self.magic[i] { return false }
            return true
        }
        guard validMagic else {
            print("[RuAccentPack] invalid magic in \(url.lastPathComponent)")
            return nil
        }

        let fileCount = Int(Self.readUInt32(mapped, at: 8))
        let fileEntrySize = Int(Self.readUInt32(mapped, at: 12))
        guard fileEntrySize == Self.expectedEntrySize else {
            print("[RuAccentPack] unsupported entry size \(fileEntrySize)")
            return nil
        }

        let fileBlobOffset = Self.headerSize + fileCount * fileEntrySize
        guard fileCount >= 0, fileBlobOffset <= mapped.count else {
            print("[RuAccentPack] invalid index bounds in \(url.lastPathComponent)")
            return nil
        }

        data = mapped
        count = fileCount
        entrySize = fileEntrySize
        blobOffset = fileBlobOffset
        print("[RuAccentPack] mmap \(url.lastPathComponent): entries=\(count), bytes=\(mapped.count)")
    }

    func contains(_ key: String) -> Bool {
        findValueRange(for: key) != nil
    }

    func stringValue(for key: String) -> String? {
        guard let range = findValueRange(for: key) else { return nil }
        return data.withUnsafeBytes { raw -> String? in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard let base = bytes.baseAddress,
                  range.lowerBound >= 0,
                  range.upperBound <= bytes.count else { return nil }
            let buffer = UnsafeBufferPointer(
                start: base.advanced(by: range.lowerBound),
                count: range.count
            )
            return String(decoding: buffer, as: UTF8.self)
        }
    }

    func listValue(for key: String) -> [String]? {
        guard let raw = stringValue(for: key), !raw.isEmpty else { return nil }
        return raw.split(separator: "\u{001F}", omittingEmptySubsequences: true).map(String.init)
    }

    private func findValueRange(for key: String) -> Range<Int>? {
        guard count > 0 else { return nil }
        let query = Array(key.lowercased().utf8)
        guard !query.isEmpty else { return nil }

        return data.withUnsafeBytes { raw -> Range<Int>? in
            let bytes = raw.bindMemory(to: UInt8.self)
            var low = 0
            var high = count - 1

            while low <= high {
                let mid = low + (high - low) / 2
                let entry = Self.headerSize + mid * entrySize
                guard entry + Self.expectedEntrySize <= bytes.count else { return nil }

                let keyOffset = Int(Self.readUInt32(raw, at: entry))
                let keyLength = Int(Self.readUInt32(raw, at: entry + 4))
                let valueOffset = Int(Self.readUInt32(raw, at: entry + 8))
                let valueLength = Int(Self.readUInt32(raw, at: entry + 12))
                let keyStart = blobOffset + keyOffset
                let valueStart = blobOffset + valueOffset

                guard keyStart >= blobOffset,
                      keyStart + keyLength <= bytes.count,
                      valueStart >= blobOffset,
                      valueStart + valueLength <= bytes.count else { return nil }

                let comparison = Self.compare(
                    bytes: bytes,
                    start: keyStart,
                    length: keyLength,
                    query: query
                )
                if comparison == 0 {
                    return valueStart..<(valueStart + valueLength)
                } else if comparison < 0 {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return nil
        }
    }

    private static func compare(
        bytes: UnsafeBufferPointer<UInt8>,
        start: Int,
        length: Int,
        query: [UInt8]
    ) -> Int {
        let common = min(length, query.count)
        if common > 0 {
            for i in 0..<common {
                let left = bytes[start + i]
                let right = query[i]
                if left < right { return -1 }
                if left > right { return 1 }
            }
        }
        if length < query.count { return -1 }
        if length > query.count { return 1 }
        return 0
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        data.withUnsafeBytes { raw in readUInt32(raw, at: offset) }
    }

    private static func readUInt32(_ raw: UnsafeRawBufferPointer, at offset: Int) -> UInt32 {
        let bytes = raw.bindMemory(to: UInt8.self)
        guard offset >= 0, offset + 4 <= bytes.count else { return 0 }
        return UInt32(bytes[offset]) |
            (UInt32(bytes[offset + 1]) << 8) |
            (UInt32(bytes[offset + 2]) << 16) |
            (UInt32(bytes[offset + 3]) << 24)
    }
}

/// Deterministic RUAccent dictionaries for ordinary stress and ё. The backing
/// packs remain mmap-backed and are explicitly releasable before TTS loads.
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
        print("[RussianPronunciationDictionary] mmap resources released")
    }

    private let accents: RuAccentPack?
    private let yoWords: RuAccentPack?
    private let homographs: RuAccentPack?
    private let yoHomographs: RuAccentPack?
    private let wordRegex: NSRegularExpression

    private init() {
        accents = RuAccentPack(resource: "accents_full")
        yoWords = RuAccentPack(resource: "yo_words")
        homographs = RuAccentPack(resource: "omographs")
        yoHomographs = RuAccentPack(resource: "yo_homographs")
        wordRegex = try! NSRegularExpression(pattern: "[А-Яа-яЁё\\u{0301}]+")
        print("[RussianPronunciationDictionary] full mmap packs ready")
    }

    func process(_ text: String) -> String {
        guard !text.isEmpty, accents != nil || yoWords != nil else { return text }

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

            if yoHomographs?.contains(lower) != true,
               let yo = yoWords?.stringValue(for: lower) {
                candidate = Self.preserveCase(source: original, replacement: yo)
            }

            let normalizedKey = candidate.lowercased()
            let isContextual = homographs?.contains(lower) == true ||
                homographs?.contains(normalizedKey) == true
            if !isContextual,
               let rawAccent = accents?.stringValue(for: normalizedKey) ?? accents?.stringValue(for: lower) {
                let accented = Self.plusToCombiningAcute(rawAccent)
                candidate = Self.preserveCase(source: original, replacement: accented)
            }

            if candidate != original {
                output.replaceCharacters(in: match.range, with: candidate)
            }
        }
        return output as String
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