//
//  SherpaOnnxTtsBridge.swift
//  iosApp
//
//  Created by Денис Хамидуллин on 06.04.2026.
//

import Foundation
import AVFAudio
import Darwin
import ComposeApp

private let kSherpaMaxChunkLength = 240

@objc public class SherpaOnnxTtsBridge: NSObject, AVAudioPlayerDelegate {

    private var tts: SherpaOnnxOfflineTtsWrapper?
    private var audioPlayer: AVAudioPlayer?
    private var currentSemaphore: DispatchSemaphore?
    private var currentToken: Int = 0
    private let generateQueue = DispatchQueue(label: "com.lector.sherpa.generate")

    private func diag(_ message: String) {
        TtsDiagnosticLog.shared.append(tag: "PiperBridge", message: message)
    }

    @objc public func extractTarBz2(archivePath: String, destPath: String) -> Bool {
        guard let decompressed = Bz2Wrapper.decompressBz2File(archivePath) else {
            return false
        }

        let fileManager = FileManager.default
        var offset = 0
        let blockSize = 512

        while offset + blockSize <= decompressed.count {
            let header = decompressed.subdata(in: offset..<offset + blockSize)
            offset += blockSize

            if header.allSatisfy({ $0 == 0 }) { break }

            let nameBytes = header.subdata(in: 0..<100)
            guard let name = String(bytes: nameBytes.prefix(while: { $0 != 0 }), encoding: .utf8),
                  !name.isEmpty else { continue }

            let sizeBytes = header.subdata(in: 124..<136)
            let sizeStr = String(bytes: sizeBytes.prefix(while: { $0 != 0 }), encoding: .utf8) ?? "0"
            let fileSize = Int(sizeStr.trimmingCharacters(in: .whitespaces), radix: 8) ?? 0

            let typeFlag = header[156]
            let destURL = URL(fileURLWithPath: destPath).appendingPathComponent(name)

            if typeFlag == UInt8(ascii: "5") || name.hasSuffix("/") {
                try? fileManager.createDirectory(at: destURL, withIntermediateDirectories: true)
            } else {
                try? fileManager.createDirectory(at: destURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fileSize > 0 && offset + fileSize <= decompressed.count {
                    let fileData = decompressed.subdata(in: offset..<offset + fileSize)
                    try? fileData.write(to: destURL)
                }
            }

            let blocks = (fileSize + blockSize - 1) / blockSize
            offset += blocks * blockSize
        }

        return true
    }

    @objc public func loadModel(
        onnxPath: String,
        tokensPath: String,
        espeakDataPath: String
    ) {
        let vitsConfig = sherpaOnnxOfflineTtsVitsModelConfig(
            model: onnxPath,
            lexicon: "",
            tokens: tokensPath,
            dataDir: espeakDataPath
        )
        let modelConfig = sherpaOnnxOfflineTtsModelConfig(
            vits: vitsConfig,
            numThreads: 4,
            debug: 0,
            provider: "cpu"
        )
        var config = sherpaOnnxOfflineTtsConfig(model: modelConfig)
        withUnsafePointer(to: &config) { ptr in
            tts = SherpaOnnxOfflineTtsWrapper(config: ptr)
        }
        diag("loadModel ready=\(tts != nil)")
    }

    @objc public func speak(text: String, speed: Float) {
        let wav = generateAudio(text: text, speed: speed)
        guard !wav.isEmpty else { return }
        playWavData(wav)
    }

    @objc public func stop() {
        currentToken += 1
        audioPlayer?.stop()
        currentSemaphore?.signal()
        currentSemaphore = nil
        diag("stop token=\(currentToken)")
    }

    @objc public func downloadFile(
        urlString: String,
        destPath: String,
        onProgress: @escaping (Float) -> Void,
        onComplete: @escaping (Bool) -> Void
    ) {
        guard let url = URL(string: urlString) else {
            onComplete(false)
            return
        }

        let delegate = DownloadDelegate(destPath: destPath, onProgress: onProgress, onComplete: onComplete)
        let session = URLSession(configuration: .default, delegate: delegate, delegateQueue: nil)
        let task = session.downloadTask(with: url)
        delegate.task = task
        task.resume()
    }

    @objc public func generateAudio(text: String, speed: Float) -> Data {
        var result = Data()
        let myToken = self.currentToken

        generateQueue.sync {
            guard let tts = self.tts else {
                self.diag("generate ABORT engine-not-loaded")
                return
            }

            // Text arriving here is already persisted pronunciation markup (tts_text).
            // Do not run the RUAccent dictionary again during playback: doing so both
            // wastes memory and can mutate an already resolved contextual homograph.
            let chunks = SherpaTextChunker(maxChunkLength: kSherpaMaxChunkLength).split(text)
            guard !chunks.isEmpty else {
                self.diag("generate ABORT no-chunks chars=\(text.count)")
                return
            }

            self.diag("generate START sourceChars=\(text.count) chunks=\(chunks.count) speed=\(speed)")
            var allSamples: [Float] = []
            var sampleRate: Int32?

            final class CallbackContext {
                weak var owner: SherpaOnnxTtsBridge?
                let token: Int
                init(_ owner: SherpaOnnxTtsBridge, _ token: Int) {
                    self.owner = owner
                    self.token = token
                }
            }

            for (chunkIndex, chunk) in chunks.enumerated() {
                guard self.currentToken == myToken else {
                    self.diag("generate CANCELLED before chunk=\(chunkIndex + 1)/\(chunks.count)")
                    return
                }

                let ctx = CallbackContext(self, myToken)
                let rawCtx = Unmanaged.passRetained(ctx).toOpaque()
                defer { Unmanaged<CallbackContext>.fromOpaque(rawCtx).release() }

                let audioResult = tts.generateWithCallbackWithArg(
                    text: chunk,
                    callback: { _, _, rawArg -> Int32 in
                        guard let rawArg else { return 0 }
                        let ctx = Unmanaged<CallbackContext>.fromOpaque(rawArg).takeUnretainedValue()
                        return ctx.owner?.currentToken == ctx.token ? 1 : 0
                    },
                    arg: rawCtx,
                    sid: 0,
                    speed: speed
                )

                guard self.currentToken == myToken else {
                    self.diag("generate CANCELLED chunk=\(chunkIndex + 1)/\(chunks.count)")
                    return
                }

                let samples = audioResult.samples
                guard !samples.isEmpty else {
                    self.diag("generate ERROR empty chunk=\(chunkIndex + 1)/\(chunks.count) chars=\(chunk.count)")
                    return
                }

                if let expected = sampleRate, expected != audioResult.sampleRate {
                    self.diag("generate ERROR sample-rate changed \(expected)->\(audioResult.sampleRate)")
                    return
                }
                sampleRate = audioResult.sampleRate
                allSamples.append(contentsOf: samples)
                self.diag("chunk=\(chunkIndex + 1)/\(chunks.count) chars=\(chunk.count) samples=\(samples.count)")
            }

            guard self.currentToken == myToken,
                  !allSamples.isEmpty,
                  let finalRate = sampleRate else { return }

            var pcm = Data(capacity: allSamples.count * 2)
            for sample in allSamples {
                let clamped = max(-1.0, min(1.0, sample))
                var intSample = Int16(clamped * Float(Int16.max)).littleEndian
                Swift.withUnsafeBytes(of: &intSample) { pcm.append(contentsOf: $0) }
            }

            var wav = self.makeWavHeader(dataSize: pcm.count, sampleRate: Int(finalRate))
            wav.append(pcm)
            result = wav
            self.diag("generate DONE chunks=\(chunks.count) samples=\(allSamples.count) bytes=\(wav.count)")
        }
        return result
    }

    @objc public func playWav(data: Data) {
        playWavData(data)
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        currentSemaphore?.signal()
        currentSemaphore = nil
    }

    public func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        diag("decode error: \(error?.localizedDescription ?? "unknown")")
        currentSemaphore?.signal()
        currentSemaphore = nil
    }

    private func playWavData(_ data: Data) {
        guard let player = try? AVAudioPlayer(data: data) else {
            diag("play ABORT invalid WAV bytes=\(data.count)")
            return
        }
        audioPlayer = player
        player.delegate = self
        let sem = DispatchSemaphore(value: 0)
        currentSemaphore = sem
        player.play()
        sem.wait()
        currentSemaphore = nil
    }

    private func makeWavHeader(dataSize: Int, sampleRate: Int) -> Data {
        var header = Data()
        let totalSize = dataSize + 36
        header.append(contentsOf: "RIFF".utf8)
        header.append(littleEndian: UInt32(totalSize))
        header.append(contentsOf: "WAVE".utf8)
        header.append(contentsOf: "fmt ".utf8)
        header.append(littleEndian: UInt32(16))
        header.append(littleEndian: UInt16(1))
        header.append(littleEndian: UInt16(1))
        header.append(littleEndian: UInt32(sampleRate))
        header.append(littleEndian: UInt32(sampleRate * 2))
        header.append(littleEndian: UInt16(2))
        header.append(littleEndian: UInt16(16))
        header.append(contentsOf: "data".utf8)
        header.append(littleEndian: UInt32(dataSize))
        return header
    }
}

/// Conservative sentence-aware chunker for Piper/sherpa-onnx. Keeping each call
/// short prevents long VITS requests from silently truncating or destabilising on
/// mobile, while preserving every source character in exactly one chunk.
private struct SherpaTextChunker {
    let maxChunkLength: Int

    func split(_ text: String) -> [String] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        if trimmed.count <= maxChunkLength { return [trimmed] }

        var chunks: [String] = []
        var current = ""
        let sentences = splitSentences(trimmed)

        for sentence in sentences {
            if sentence.count > maxChunkLength {
                if !current.isEmpty {
                    chunks.append(current)
                    current = ""
                }
                chunks.append(contentsOf: splitLong(sentence))
                continue
            }

            let candidate = current.isEmpty ? sentence : "\(current) \(sentence)"
            if candidate.count <= maxChunkLength {
                current = candidate
            } else {
                if !current.isEmpty { chunks.append(current) }
                current = sentence
            }
        }
        if !current.isEmpty { chunks.append(current) }
        return chunks.filter { !$0.isEmpty }
    }

    private func splitSentences(_ text: String) -> [String] {
        var result: [String] = []
        var buffer = ""
        let characters = Array(text)

        for (index, character) in characters.enumerated() {
            buffer.append(character)
            guard character == "." || character == "!" || character == "?" || character == "…" else { continue }

            let nextIndex = index + 1
            if nextIndex < characters.count,
               !characters[nextIndex].isWhitespace {
                continue
            }
            let sentence = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            if !sentence.isEmpty { result.append(sentence) }
            buffer.removeAll(keepingCapacity: true)
        }

        let tail = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty { result.append(tail) }
        return result
    }

    private func splitLong(_ text: String) -> [String] {
        let words = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        var result: [String] = []
        var current = ""

        for word in words {
            if word.count > maxChunkLength {
                if !current.isEmpty {
                    result.append(current)
                    current = ""
                }
                var remainder = word
                while remainder.count > maxChunkLength {
                    let cut = remainder.index(remainder.startIndex, offsetBy: maxChunkLength)
                    result.append(String(remainder[..<cut]))
                    remainder = String(remainder[cut...])
                }
                current = remainder
                continue
            }

            let candidate = current.isEmpty ? word : "\(current) \(word)"
            if candidate.count <= maxChunkLength {
                current = candidate
            } else {
                if !current.isEmpty { result.append(current) }
                current = word
            }
        }
        if !current.isEmpty { result.append(current) }
        return result
    }
}

private class DownloadDelegate: NSObject, URLSessionDownloadDelegate {
    let destPath: String
    let onProgress: (Float) -> Void
    let onComplete: (Bool) -> Void
    var task: URLSessionDownloadTask?

    init(destPath: String, onProgress: @escaping (Float) -> Void, onComplete: @escaping (Bool) -> Void) {
        self.destPath = destPath
        self.onProgress = onProgress
        self.onComplete = onComplete
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let destURL = URL(fileURLWithPath: destPath)
        do {
            try FileManager.default.moveItem(at: location, to: destURL)
            onComplete(true)
        } catch {
            print("DownloadDelegate: move error \(error)")
            onComplete(false)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let progress = Float(totalBytesWritten) / Float(totalBytesExpectedToWrite)
        onProgress(progress)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            print("DownloadDelegate: error \(error)")
            onComplete(false)
        }
    }
}

private extension Data {
    mutating func append<T: FixedWidthInteger>(littleEndian value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { self.append(contentsOf: $0) }
    }
}
