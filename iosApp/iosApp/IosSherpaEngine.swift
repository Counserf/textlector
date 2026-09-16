//
// Created by Денис Хамидуллин on 09.04.2026.
//

import Foundation
import ComposeApp

@objc class IosSherpaEngine: NSObject, SherpaOnnxTtsEngine {

    private let bridge = SherpaOnnxTtsBridge()
    private let repository: IosVoiceModelRepositoryImpl
    private var isModelLoaded = false
    private var paragraphs: [ComposeApp.LectorParagraph] = []
    private var currentLanguage: String = "ru"

    init(repository: IosVoiceModelRepositoryImpl) {
        self.repository = repository
    }

    private func diag(_ message: String) {
        TtsDiagnosticLog.shared.append(tag: "PiperNative", message: message)
    }

    func setPlaylist(paragraphs: [LectorParagraph]) {
        self.paragraphs = paragraphs as [ComposeApp.LectorParagraph]
        diag("setPlaylist count=\(paragraphs.count)")
    }

    func speak(index: Int32, speed: Float) async throws {
        guard isModelLoaded else {
            diag("speak skipped: model not loaded")
            return
        }
        guard index >= 0 && Int(index) < paragraphs.count else { return }
        diag("speak START index=\(index) speed=\(speed)")
        bridge.speak(text: paragraphs[Int(index)].ttsText ?? paragraphs[Int(index)].text, speed: speed)
        diag("speak END index=\(index)")
    }

    func loadVoice(model: VoiceModel) async throws {
        diag("loadVoice START id=\(model.id)")
        guard let path = repository.getModelPath(id: model.id) else {
            diag("loadVoice missing model path id=\(model.id)")
            return
        }

        currentLanguage = model.language
        bridge.loadModel(
            onnxPath: path.onnxPath,
            tokensPath: path.tokensPath,
            espeakDataPath: path.espeakDataPath
        )
        isModelLoaded = true
        diag("loadVoice END id=\(model.id)")
    }

    func generate(text: String, speed: Float) async throws -> KotlinByteArray {
        diag("generate START chars=\(text.count) speed=\(speed)")
        let data = bridge.generateAudio(text: text, speed: speed)
        diag("generate END bytes=\(data.count)")
        let bytes = [UInt8](data)
        let result = KotlinByteArray(size: Int32(bytes.count))
        for (i, byte) in bytes.enumerated() {
            result.set(index: Int32(i), value: Int8(bitPattern: byte))
        }
        return result
    }

    func playAudio(audio: KotlinByteArray) async throws {
        diag("play START bytes=\(audio.size)")
        var data = Data(count: Int(audio.size))
        for i in 0..<Int(audio.size) {
            data[i] = UInt8(bitPattern: audio.get(index: Int32(i)))
        }
        bridge.playWav(data: data)
        diag("play END")
    }

    func stop() {
        diag("stop")
        bridge.stop()
    }

    func shutdown() {
        diag("shutdown")
        bridge.stop()
    }
}