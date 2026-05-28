//
// Created by Денис Хамидуллин on 28.05.2026.
//

import Foundation
import ComposeApp

@objc class IosSupertonicEngine: NSObject, SherpaOnnxTtsEngine {



    func generate(text: String, speed: Float) async throws -> KotlinByteArray {
        <#code#>
    }

    func playAudio(audio: KotlinByteArray) async throws {
        <#code#>
    }


    func loadVoice(model: VoiceModel) async throws {
        <#code#>
    }

    func setPlaylist(paragraphs: [LectorParagraph]) {
        <#code#>
    }

    func shutdown() {
        <#code#>
    }

    func speak(index: Int32, speed: Float) async throws {
        <#code#>
    }

    func stop() {
        <#code#>
    }


}