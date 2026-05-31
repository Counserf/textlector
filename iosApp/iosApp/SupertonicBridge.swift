//
//  SupertonicBridge.swift
//
//  Full Supertonic v3 TTS inference pipeline for iOS.
//  Implements SupertonicBridgeProtocol from the KMP framework.
//
//  Requires: onnxruntime via Swift Package Manager
//  URL: https://github.com/microsoft/onnxruntime-swift-package-manager
//

import Foundation
import OnnxRuntimeBindings
import AVFAudio
import ComposeApp

// latent_dim(24) * chunk_compress_factor(6)
private let kLatentChannels = 144
private let kStyleTtlTokens = 50
private let kStyleTtlDim = 256
private let kStyleDpTokens = 8
private let kStyleDpDim = 16
// ae.base_chunk_size(512) * ttl.chunk_compress_factor(6)
private let kChunkSize = 512 * 6
private let kSampleRate = 44100

@objc public class SupertonicBridge: NSObject, SupertonicBridgeProtocol, AVAudioPlayerDelegate {
    
    

    private var ortEnv: ORTEnv?
    private var dpSession: ORTSession?
    private var textEncoderSession: ORTSession?
    private var vectorEstimatorSession: ORTSession?
    private var vocoderSession: ORTSession?
    private var unicodeIndexer: [Int32] = []

    private var audioPlayer: AVAudioPlayer?
    private var currentSemaphore: DispatchSemaphore?

    private var cancelled = false

    @objc public var isLoaded: Bool {
        dpSession != nil
            && textEncoderSession != nil
            && vectorEstimatorSession != nil
            && vocoderSession != nil
            && !unicodeIndexer.isEmpty
    }

    // MARK: - Load

    @objc public func load(storageDir: String) {
        do {
            let env = try ORTEnv(loggingLevel: .warning)
            ortEnv = env

            dpSession = try ORTSession(env: env, modelPath: "\(storageDir)/duration_predictor.onnx", sessionOptions: nil)
            textEncoderSession = try ORTSession(env: env, modelPath: "\(storageDir)/text_encoder.onnx", sessionOptions: nil)
            vectorEstimatorSession = try ORTSession(env: env, modelPath: "\(storageDir)/vector_estimator.onnx", sessionOptions: nil)
            vocoderSession = try ORTSession(env: env, modelPath: "\(storageDir)/vocoder.onnx", sessionOptions: nil)

            unicodeIndexer = try loadIndexer(storageDir: storageDir)
        } catch {
            print("[SupertonicBridge] load error: \(error)")
        }
    }

    // MARK: - Generate

    @objc public func generate(
        text: String,
        lang: String,
        voiceStyleJson: String,
        speed: Float,
        steps: Int32
    ) -> Data? {
        cancelled = false

        guard isLoaded,
            let dp = dpSession,
            let textEncoder = textEncoderSession,
            let vectorEstimator = vectorEstimatorSession,
            let vocoder = vocoderSession
        else { return nil }

        do {
            // Preprocess and tokenize
            let processed = preprocessText(text, lang: lang)
            let tokenIds = tokenize(processed)
            guard !tokenIds.isEmpty else { return nil }
            let textLen = tokenIds.count

            // Parse voice styles
            let styleTtl = try parseStyle(voiceStyleJson, key: "style_ttl", tokens: kStyleTtlTokens, dim: kStyleTtlDim)
            let styleDp = try parseStyle(voiceStyleJson, key: "style_dp", tokens: kStyleDpTokens, dim: kStyleDpDim)

            // Common tensors
            let textIdsTensor = try makeTensor(int64Array: tokenIds, shape: [1, textLen])
            let styleTtlTensor = try makeTensor(floatArray: styleTtl, shape: [1, kStyleTtlTokens, kStyleTtlDim])
            let styleDpTensor = try makeTensor(floatArray: styleDp, shape: [1, kStyleDpTokens, kStyleDpDim])
            let textMaskTensor = try makeTensor(floatArray: [Float](repeating: 1, count: textLen), shape: [1, 1, textLen])

            // Duration predictor → latent length
            let dpOutputs = try dp.run(
                withInputs: [
                    "text_ids": textIdsTensor,
                    "style_dp": styleDpTensor,
                    "text_mask": textMaskTensor,
                ],
                outputNames: Set(["duration"]),
                runOptions: nil
            )
            let durationData = try dpOutputs["duration"]!.tensorData() as Data
            var durationSec: Float = 0
            (durationData as NSData).getBytes(&durationSec, length: 4)
            durationSec /= speed

            let latentLen = max(1, Int(ceil(durationSec * Float(kSampleRate) / Float(kChunkSize))))

            if cancelled { return nil }

            //Text encoder
            let encoderOutputs = try textEncoder.run(
                withInputs: [
                    "text_ids": textIdsTensor,
                    "style_ttl": styleTtlTensor,
                    "text_mask": textMaskTensor,
                ],
                outputNames: Set(["text_emb"]),
                runOptions: nil
            )
            let textEmbTensor = encoderOutputs["text_emb"]!

            // Latent mask
            let latentMaskTensor = try makeTensor(
                floatArray: [Float](repeating: 1, count: latentLen),
                shape: [1, 1, latentLen]
            )

            // Initial noise
            let noiseSize = kLatentChannels * latentLen
            var noisyLatent = gaussianNoise(count: noiseSize)

            // Denoising loop
            // vector_estimator directly outputs the updated latent each step.
            let totalStepTensor = try makeTensor(floatArray: [Float(steps)], shape: [1])

            for step in 0..<Int(steps) {
                if cancelled { return nil }

                let noisyTensor = try makeTensor(floatArray: noisyLatent, shape: [1, kLatentChannels, latentLen])
                let currentStepTensor = try makeTensor(floatArray: [Float(step)], shape: [1])

                let veOutputs = try vectorEstimator.run(
                    withInputs: [
                        "noisy_latent": noisyTensor,
                        "text_emb": textEmbTensor,
                        "style_ttl": styleTtlTensor,
                        "latent_mask": latentMaskTensor,
                        "text_mask": textMaskTensor,
                        "current_step": currentStepTensor,
                        "total_step": totalStepTensor,
                    ],
                    outputNames: Set(["denoised_latent"]),
                    runOptions: nil
                )

                // Direct replacement: xt = denoised_latent
                let denoised = try floats(from: veOutputs["denoised_latent"]!, count: noiseSize)
                noisyLatent = denoised
            }

            if cancelled { return nil }

            // Vocoder
            let latentTensor = try makeTensor(floatArray: noisyLatent, shape: [1, kLatentChannels, latentLen])
            let vocoderOutputs = try vocoder.run(
                withInputs: ["latent": latentTensor],
                outputNames: Set(["wav_tts"]),
                runOptions: nil
            )

            let wavData = try vocoderOutputs["wav_tts"]!.tensorData() as Data
            let wavCount = wavData.count / 4
            var samples = [Float](repeating: 0, count: wavCount)
            (wavData as NSData).getBytes(&samples, length: wavData.count)

            // Build WAV
            return buildWav(samples: samples)

        } catch {
            print("[SupertonicBridge] generate error: \(error)")
            return nil
        }
    }

    @objc public func playWav(data: Data) {
        guard let player = try? AVAudioPlayer(data: data) else { return }
        audioPlayer = player
        player.delegate = self
        let sem = DispatchSemaphore(value: 0)
        currentSemaphore = sem
        player.play()
        sem.wait()
        currentSemaphore = nil
    }

    @objc public func stopPlayback() {
        audioPlayer?.stop()
        currentSemaphore?.signal()
        currentSemaphore = nil
    }

    @objc public func cancel() { cancelled = true }

    @objc public func close() {
        dpSession = nil
        textEncoderSession = nil
        vectorEstimatorSession = nil
        vocoderSession = nil
        ortEnv = nil
        unicodeIndexer = []
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        currentSemaphore?.signal()
        currentSemaphore = nil
    }

    // MARK: - Text processing

    private func preprocessText(_ text: String, lang: String) -> String {
        var t = text.decomposedStringWithCompatibilityMapping  // NFKD
        let endingPunctuation = CharacterSet(charactersIn: ".!?;:,'\")}]…")
        if let last = t.unicodeScalars.last, !endingPunctuation.contains(last) {
            t += "."
        }
        return "<\(lang)>\(t)</\(lang)>"
    }

    private func tokenize(_ text: String) -> [Int64] {
        var result = [Int64]()
        for scalar in text.unicodeScalars {
            let cp = Int(scalar.value)
            if cp < unicodeIndexer.count && unicodeIndexer[cp] != -1 {
                result.append(Int64(unicodeIndexer[cp]))
            }
        }
        return result
    }

    // MARK: - Style parsing

    private func parseStyle(_ json: String, key: String, tokens: Int, dim: Int) throws -> [Float] {
        guard let data = json.data(using: .utf8),
            let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let styleObj = root[key] as? [String: Any],
            let dataArr = styleObj["data"] as? [[[Double]]],
            let batch = dataArr.first
        else {
            throw NSError(domain: "SupertonicBridge", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to parse \(key)"])
        }
        var result = [Float]()
        result.reserveCapacity(tokens * dim)
        for i in 0..<tokens {
            let row = batch[i]
            for j in 0..<dim {
                result.append(Float(row[j]))
            }
        }
        return result
    }

    // MARK: - ORT helpers

    private func makeTensor(floatArray: [Float], shape: [Int]) throws -> ORTValue {
        var arr = floatArray
        let data = NSMutableData(bytes: &arr, length: arr.count * 4)
        return try ORTValue(
            tensorData: data,
            elementType: .float,
            shape: shape.map { NSNumber(value: $0) }
        )
    }

    private func makeTensor(int64Array: [Int64], shape: [Int]) throws -> ORTValue {
        var arr = int64Array
        let data = NSMutableData(bytes: &arr, length: arr.count * 8)
        return try ORTValue(
            tensorData: data,
            elementType: .int64,
            shape: shape.map { NSNumber(value: $0) }
        )
    }

    private func floats(from value: ORTValue, count: Int) throws -> [Float] {
        let data = try value.tensorData() as Data
        var result = [Float](repeating: 0, count: count)
        (data as NSData).getBytes(&result, length: count * 4)
        return result
    }

    // MARK: - Utilities

    private func loadIndexer(storageDir: String) throws -> [Int32] {
        let path = "\(storageDir)/unicode_indexer.json"
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        guard let array = try JSONSerialization.jsonObject(with: data) as? [Int] else {
            throw NSError(domain: "SupertonicBridge", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid unicode_indexer.json"])
        }
        return array.map { Int32($0) }
    }

    private func gaussianNoise(count: Int) -> [Float] {
        var result = [Float](repeating: 0, count: count)
        for i in stride(from: 0, to: count - 1, by: 2) {
            let u1 = Float.random(in: Float.ulpOfOne...1)
            let u2 = Float.random(in: 0..<1)
            let mag = (-2 * log(u1)).squareRoot()
            result[i] = mag * cos(2 * .pi * u2)
            result[i + 1] = mag * sin(2 * .pi * u2)
        }
        if count % 2 == 1 {
            let u1 = Float.random(in: Float.ulpOfOne...1)
            let u2 = Float.random(in: 0..<1)
            result[count - 1] = (-2 * log(u1)).squareRoot() * cos(2 * .pi * u2)
        }
        return result
    }

    private func buildWav(samples: [Float]) -> Data {
        var pcm = Data(capacity: samples.count * 2)
        for s in samples {
            let clamped = max(-1, min(1, s))
            let intVal = Int16(clamped * Float(Int16.max))
            withUnsafeBytes(of: intVal.littleEndian) { pcm.append(contentsOf: $0) }
        }
        var wav = makeWavHeader(dataSize: pcm.count)
        wav.append(pcm)
        return wav
    }

    private func makeWavHeader(dataSize: Int) -> Data {
        var h = Data()
        h.append(contentsOf: "RIFF".utf8)
        h.appendLE(UInt32(dataSize + 36))
        h.append(contentsOf: "WAVE".utf8)
        h.append(contentsOf: "fmt ".utf8)
        h.appendLE(UInt32(16))
        h.appendLE(UInt16(1))  // PCM
        h.appendLE(UInt16(1))  // mono
        h.appendLE(UInt32(kSampleRate))
        h.appendLE(UInt32(kSampleRate * 2))
        h.appendLE(UInt16(2))  // block align
        h.appendLE(UInt16(16))  // bits per sample
        h.append(contentsOf: "data".utf8)
        h.appendLE(UInt32(dataSize))
        return h
    }
}

// MARK: - Data little-endian helpers

extension Data {
    fileprivate mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { self.append(contentsOf: $0) }
    }
}
