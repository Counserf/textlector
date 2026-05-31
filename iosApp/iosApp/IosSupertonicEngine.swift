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
        
        let docsDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        let storageDir = docsDir + "/supertonic/onnx"
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
        let styleJson = loadVoiceStyle(currentVoice)
        print("[IosSupertonicEngine] generate: voice=\(currentVoice), lang=\(currentLang), styleJson empty=\(styleJson == "{}")")
        
        guard let data = bridge.generate(
            text: text,
            lang: currentLang,
            voiceStyleJson: styleJson,
            speed: speed,
            steps: 8
        ) else {
            print("[IosSupertonicEngine] bridge.generate returned nil")
            return KotlinByteArray(size: 0)
        }
        print("[IosSupertonicEngine] generate success: \(data.count) bytes")
        return data.toKotlinByteArray()
    }

    func playAudio(audio: KotlinByteArray) async throws {
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
        guard let url = Bundle.main.url(
                forResource: voice.uppercased(),
                withExtension: "json",
                subdirectory: "supertonic/voice_styles"
            ),
            let json = try? String(contentsOf: url, encoding: .utf8) else {
                print("[IosSupertonicEngine] voice style not found: \(voice)")
                return "{}"
            }
            return json
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
