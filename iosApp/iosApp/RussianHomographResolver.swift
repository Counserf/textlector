import Foundation
import OnnxRuntimeBindings

/// Context-aware Russian homograph resolver used only for words that cannot be
/// pronounced safely from a dictionary alone (за́мок/замо́к, а́тлас/атла́с, etc.).
///
/// This mirrors RUAccent's tiny2.1 omograph path: mark the target as <w>word</w>,
/// score each stressed hypothesis as the second tokenizer sequence and choose the
/// highest-probability variant. The model is loaded lazily only when a real
/// homograph is found and is explicitly released after background book markup.
final class RussianHomographResolver {
    static let shared = RussianHomographResolver()

    private struct Replacement {
        let range: NSRange
        let replacement: String
    }

    private let lock = NSLock()
    private var candidatesPack: RuAccentPack?
    private var attemptedPackLoad = false
    private var env: ORTEnv?
    private var session: ORTSession?
    private var tokenizer: BertWordPieceTokenizer?
    private var attemptedModelLoad = false

    private init() {}

    func process(_ text: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        return processLocked(text)
    }

    /// Frees the neural session, tokenizer and mmap handle after book markup. The
    /// next book can load them again lazily. Serialized with process(), so resources
    /// are never released during an inference call.
    func releaseResources() {
        lock.lock()
        session = nil
        tokenizer = nil
        env = nil
        candidatesPack = nil
        attemptedPackLoad = false
        attemptedModelLoad = false
        lock.unlock()
        print("[RussianHomographResolver] resources released")
    }

    private func processLocked(_ text: String) -> String {
        guard !text.isEmpty else { return text }
        guard let pack = loadCandidatePack() else { return text }

        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)
        let regex = try! NSRegularExpression(pattern: "[А-Яа-яЁё\\u{0301}]+")
        let matches = regex.matches(in: text, range: fullRange)

        // Resolve candidates from the mmap pack first. If there are no ambiguous
        // words in this paragraph, the ~10 MB quantized neural model is never loaded.
        var unresolved: [(match: NSTextCheckingResult, hypotheses: [String])] = []
        unresolved.reserveCapacity(2)
        for match in matches {
            let word = nsText.substring(with: match.range)
            guard !word.contains("\u{0301}"),
                  let variants = pack.listValue(for: word.lowercased()),
                  variants.count > 1 else { continue }
            unresolved.append((match, variants))
        }
        guard !unresolved.isEmpty else { return text }

        guard ensureModelLoaded(), let session, let tokenizer else {
            print("[RussianHomographResolver] model unavailable; leaving homographs unchanged")
            return text
        }

        var replacements: [Replacement] = []
        replacements.reserveCapacity(unresolved.count)

        for item in unresolved {
            let match = item.match
            let hypotheses = item.hypotheses
            let source = nsText.substring(with: match.range)

            // RUAccent wraps the target word in <w>...</w> before passing the
            // sentence/context as tokenizer sequence A and each hypothesis as B.
            let context = markedContext(text: text, targetRange: match.range, target: source)
            var scored: [(String, Float)] = []
            scored.reserveCapacity(hypotheses.count)

            for hypothesis in hypotheses {
                do {
                    let encoded = tokenizer.encodePair(context: context, hypothesis: hypothesis, maxLength: 512)
                    guard !encoded.inputIds.isEmpty else { continue }
                    let probability = try positiveProbability(
                        session: session,
                        inputIds: encoded.inputIds,
                        attentionMask: encoded.attentionMask
                    )
                    scored.append((hypothesis, probability))
                } catch {
                    print("[RussianHomographResolver] inference failed for '\(source)': \(error)")
                }
            }

            // Match RUAccent OmographModel.classify(): choose the maximum score.
            // Do not impose an app-specific confidence threshold.
            guard let winner = scored.max(by: { $0.1 < $1.1 }) else { continue }
            print("[RussianHomographResolver] resolved '\(source)' -> '\(winner.0)' p=\(winner.1)")

            let accented = Self.preserveCase(
                source: source,
                replacement: Self.plusToCombiningAcute(winner.0)
            )
            replacements.append(Replacement(range: match.range, replacement: accented))
        }

        guard !replacements.isEmpty else { return text }
        let output = NSMutableString(string: text)
        for replacement in replacements.sorted(by: { $0.range.location > $1.range.location }) {
            output.replaceCharacters(in: replacement.range, with: replacement.replacement)
        }
        return output as String
    }

    private func loadCandidatePack() -> RuAccentPack? {
        if let candidatesPack { return candidatesPack }
        if attemptedPackLoad { return nil }
        attemptedPackLoad = true
        candidatesPack = RuAccentPack(resource: "omographs")
        if candidatesPack == nil {
            print("[RussianHomographResolver] omographs.rapack missing or invalid")
        }
        return candidatesPack
    }

    private func ensureModelLoaded() -> Bool {
        if session != nil, tokenizer != nil { return true }
        if attemptedModelLoad { return false }
        attemptedModelLoad = true

        guard let folder = Bundle.main.url(
            forResource: "tiny2.1",
            withExtension: nil,
            subdirectory: "pronunciation/ru/homograph"
        ) else {
            print("[RussianHomographResolver] tiny2.1 bundle directory missing")
            return false
        }

        let int8URL = folder.appendingPathComponent("model.int8.onnx")
        let fp32URL = folder.appendingPathComponent("model.onnx")
        let modelURL = FileManager.default.fileExists(atPath: int8URL.path) ? int8URL : fp32URL
        let vocabURL = folder.appendingPathComponent("vocab.txt")
        let addedTokensURL = folder.appendingPathComponent("added_tokens.json")
        let tokenizerConfigURL = folder.appendingPathComponent("tokenizer_config.json")

        guard FileManager.default.fileExists(atPath: modelURL.path),
              FileManager.default.fileExists(atPath: vocabURL.path) else {
            print("[RussianHomographResolver] tiny2.1 model/vocab missing")
            return false
        }

        do {
            let newEnv = try ORTEnv(loggingLevel: .warning)
            let newSession = try ORTSession(env: newEnv, modelPath: modelURL.path, sessionOptions: nil)
            let newTokenizer = try BertWordPieceTokenizer(
                vocabURL: vocabURL,
                addedTokensURL: addedTokensURL,
                configURL: tokenizerConfigURL
            )
            env = newEnv
            session = newSession
            tokenizer = newTokenizer
            print("[RussianHomographResolver] RUAccent tiny2.1 loaded lazily: \(modelURL.lastPathComponent)")
            return true
        } catch {
            print("[RussianHomographResolver] model load error: \(error)")
            env = nil
            session = nil
            tokenizer = nil
            return false
        }
    }

    private func markedContext(text: String, targetRange: NSRange, target: String) -> String {
        let marked = NSMutableString(string: text)
        marked.replaceCharacters(in: targetRange, with: "<w>\(target)</w>")

        // The original library works sentence-by-sentence. Paragraphs in TextLector
        // can be long, so keep a target-centred window before exact token truncation.
        let markerLocation = targetRange.location
        let radius = 900
        let start = max(0, markerLocation - radius)
        let end = min(marked.length, markerLocation + targetRange.length + radius + 7)
        let safeRange = NSRange(location: start, length: max(0, end - start))
        let context = marked.substring(with: safeRange)

        // Mirrors RUAccent's cleanup immediately before tokenizer invocation.
        let regex = try? NSRegularExpression(pattern: "\\s+(?=[,.?!:;…])")
        let full = NSRange(location: 0, length: (context as NSString).length)
        return regex?.stringByReplacingMatches(in: context, range: full, withTemplate: "") ?? context
    }

    private func positiveProbability(
        session: ORTSession,
        inputIds: [Int64],
        attentionMask: [Int64]
    ) throws -> Float {
        let shape = [NSNumber(value: 1), NSNumber(value: inputIds.count)]
        let idsTensor = try Self.makeInt64Tensor(inputIds, shape: shape)
        let maskTensor = try Self.makeInt64Tensor(attentionMask, shape: shape)

        // tiny2.1 is DistilBERT, so it uses input_ids + attention_mask and no
        // token_type_ids. The exported classifier output is named "logits".
        let outputs = try session.run(
            withInputs: [
                "input_ids": idsTensor,
                "attention_mask": maskTensor,
            ],
            outputNames: Set(["logits"]),
            runOptions: nil
        )
        guard let logitsValue = outputs["logits"] else {
            throw NSError(
                domain: "TextLector.RussianHomographResolver",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "ONNX output 'logits' is missing"]
            )
        }

        let data = try logitsValue.tensorData() as Data
        guard data.count >= 2 * MemoryLayout<Float>.size else {
            throw NSError(
                domain: "TextLector.RussianHomographResolver",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "Invalid logits tensor"]
            )
        }

        var logits = [Float](repeating: 0, count: 2)
        (data as NSData).getBytes(&logits, length: 2 * MemoryLayout<Float>.size)
        let delta = max(Float(-80), min(Float(80), logits[1] - logits[0]))
        return 1 / (1 + exp(-delta))
    }

    private static func makeInt64Tensor(_ values: [Int64], shape: [NSNumber]) throws -> ORTValue {
        var copy = values
        let data = NSMutableData(bytes: &copy, length: copy.count * MemoryLayout<Int64>.size)
        return try ORTValue(tensorData: data, elementType: .int64, shape: shape)
    }

    private static func plusToCombiningAcute(_ value: String) -> String {
        var result = String()
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
        if source == source.uppercased() { return replacement.uppercased() }
        if source.first?.isUppercase == true {
            return replacement.prefix(1).uppercased() + String(replacement.dropFirst())
        }
        return replacement
    }
}

/// Minimal BERT BasicTokenizer + WordPiece implementation for RUAccent tiny2.1.
/// Keeping this local avoids adding another tokenizer framework to the app.
private struct BertWordPieceTokenizer {
    struct EncodedPair {
        let inputIds: [Int64]
        let attentionMask: [Int64]
    }

    private let vocab: [String: Int]
    private let clsId: Int
    private let sepId: Int
    private let unkId: Int
    private let openMarkerId: Int?
    private let doLowerCase: Bool

    init(vocabURL: URL, addedTokensURL: URL, configURL: URL) throws {
        let vocabText = try String(contentsOf: vocabURL, encoding: .utf8)
        var loaded: [String: Int] = [:]
        for (index, line) in vocabText.components(separatedBy: .newlines).enumerated() {
            let token = line.trimmingCharacters(in: .newlines)
            if !token.isEmpty { loaded[token] = index }
        }

        if let data = try? Data(contentsOf: addedTokensURL),
           let added = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (token, rawId) in added {
                if let id = rawId as? Int {
                    loaded[token] = id
                } else if let number = rawId as? NSNumber {
                    loaded[token] = number.intValue
                }
            }
        }

        var lower = true
        if let data = try? Data(contentsOf: configURL),
           let config = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let configured = config["do_lower_case"] as? Bool {
            lower = configured
        }

        guard let cls = loaded["[CLS]"],
              let sep = loaded["[SEP]"],
              let unk = loaded["[UNK]"] else {
            throw NSError(
                domain: "TextLector.BertWordPieceTokenizer",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Required BERT special tokens are missing"]
            )
        }

        vocab = loaded
        clsId = cls
        sepId = sep
        unkId = unk
        openMarkerId = loaded["<w>"]
        doLowerCase = lower
    }

    func encodePair(context: String, hypothesis: String, maxLength: Int) -> EncodedPair {
        var contextIds = encodeWithoutSpecialTokens(context)
        var hypothesisIds = encodeWithoutSpecialTokens(hypothesis)

        // [CLS] context [SEP] hypothesis [SEP]
        let reserved = 3
        let maxPayload = max(1, maxLength - reserved)
        if hypothesisIds.count > maxPayload / 2 {
            hypothesisIds = Array(hypothesisIds.prefix(maxPayload / 2))
        }
        let contextBudget = max(1, maxPayload - hypothesisIds.count)
        if contextIds.count > contextBudget {
            contextIds = centeredSlice(contextIds, maxCount: contextBudget)
        }

        var ids: [Int64] = [Int64(clsId)]
        ids.append(contentsOf: contextIds.map(Int64.init))
        ids.append(Int64(sepId))
        ids.append(contentsOf: hypothesisIds.map(Int64.init))
        ids.append(Int64(sepId))

        return EncodedPair(
            inputIds: ids,
            attentionMask: [Int64](repeating: 1, count: ids.count)
        )
    }

    private func encodeWithoutSpecialTokens(_ input: String) -> [Int] {
        let basic = basicTokens(input)
        var ids: [Int] = []
        for token in basic {
            if let direct = vocab[token] {
                ids.append(direct)
            } else {
                ids.append(contentsOf: wordPiece(token))
            }
        }
        return ids
    }

    private func basicTokens(_ input: String) -> [String] {
        var normalized = input
        if doLowerCase {
            normalized = normalized.lowercased()
            // BertTokenizer with do_lower_case=true and strip_accents=null strips
            // combining accents as part of lower-case normalization.
            normalized = normalized.folding(
                options: [.diacriticInsensitive],
                locale: Locale(identifier: "ru_RU")
            )
        }

        var tokens: [String] = []
        var current = String()
        let chars = Array(normalized)
        var index = 0

        func flush() {
            if !current.isEmpty {
                tokens.append(current)
                current.removeAll(keepingCapacity: true)
            }
        }

        while index < chars.count {
            if index + 2 < chars.count,
               chars[index] == "<", chars[index + 1] == "w", chars[index + 2] == ">" {
                flush()
                tokens.append("<w>")
                index += 3
                continue
            }
            if index + 3 < chars.count,
               chars[index] == "<", chars[index + 1] == "/",
               chars[index + 2] == "w", chars[index + 3] == ">" {
                flush()
                tokens.append("</w>")
                index += 4
                continue
            }

            let character = chars[index]
            if character.isWhitespace {
                flush()
            } else if Self.isPunctuation(character) {
                flush()
                tokens.append(String(character))
            } else {
                current.append(character)
            }
            index += 1
        }
        flush()
        return tokens
    }

    private func wordPiece(_ token: String) -> [Int] {
        let scalars = Array(token.unicodeScalars)
        guard !scalars.isEmpty, scalars.count <= 100 else { return [unkId] }

        var result: [Int] = []
        var start = 0
        while start < scalars.count {
            var end = scalars.count
            var found: Int?
            var foundEnd = start

            while start < end {
                let pieceBody = String(String.UnicodeScalarView(scalars[start..<end]))
                let piece = start == 0 ? pieceBody : "##" + pieceBody
                if let id = vocab[piece] {
                    found = id
                    foundEnd = end
                    break
                }
                end -= 1
            }

            guard let id = found else { return [unkId] }
            result.append(id)
            start = foundEnd
        }
        return result
    }

    private func centeredSlice(_ ids: [Int], maxCount: Int) -> [Int] {
        guard ids.count > maxCount else { return ids }
        guard maxCount > 0 else { return [] }

        let center: Int
        if let marker = openMarkerId, let markerIndex = ids.firstIndex(of: marker) {
            center = markerIndex
        } else {
            center = ids.count / 2
        }

        var start = max(0, center - maxCount / 2)
        if start + maxCount > ids.count {
            start = ids.count - maxCount
        }
        return Array(ids[start..<(start + maxCount)])
    }

    private static func isPunctuation(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first else { return false }
        let value = scalar.value
        if (33...47).contains(value) || (58...64).contains(value) ||
            (91...96).contains(value) || (123...126).contains(value) {
            return true
        }
        return CharacterSet.punctuationCharacters.contains(scalar)
    }
}
