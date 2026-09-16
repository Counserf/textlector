import Foundation
import AVFoundation
import MediaPlayer
import ComposeApp

@objc final class IosRemotePlaybackController: NSObject, RemotePlaybackController {
    private var handler: RemotePlaybackHandler?
    private let commands = MPRemoteCommandCenter.shared()

    func bind(handler: RemotePlaybackHandler) {
        self.handler = handler
        configureAudioSession()
        installCommands()
    }

    func update(isPlaying: Bool, title: String, subtitle: String) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyAlbumTitle: subtitle,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    func unbind() {
        commands.playCommand.removeTarget(nil)
        commands.pauseCommand.removeTarget(nil)
        commands.togglePlayPauseCommand.removeTarget(nil)
        commands.nextTrackCommand.removeTarget(nil)
        commands.previousTrackCommand.removeTarget(nil)
        handler = nil
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .spokenAudio, options: [])
            try session.setActive(true)
        } catch {
            print("[RemotePlayback] AVAudioSession error: \(error)")
        }
    }

    private func installCommands() {
        unbindCommandsOnly()

        commands.playCommand.isEnabled = true
        commands.pauseCommand.isEnabled = true
        commands.togglePlayPauseCommand.isEnabled = true
        commands.nextTrackCommand.isEnabled = true
        commands.previousTrackCommand.isEnabled = true

        commands.playCommand.addTarget { [weak self] _ in
            self?.dispatch { $0.play() }
            return .success
        }
        commands.pauseCommand.addTarget { [weak self] _ in
            self?.dispatch { $0.pause() }
            return .success
        }
        commands.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            let isPlaying = MPNowPlayingInfoCenter.default().playbackState == .playing
            self.dispatch { handler in
                if isPlaying { handler.pause() } else { handler.play() }
            }
            return .success
        }
        commands.nextTrackCommand.addTarget { [weak self] _ in
            self?.dispatch { $0.next() }
            return .success
        }
        commands.previousTrackCommand.addTarget { [weak self] _ in
            self?.dispatch { $0.previous() }
            return .success
        }
    }

    private func unbindCommandsOnly() {
        commands.playCommand.removeTarget(nil)
        commands.pauseCommand.removeTarget(nil)
        commands.togglePlayPauseCommand.removeTarget(nil)
        commands.nextTrackCommand.removeTarget(nil)
        commands.previousTrackCommand.removeTarget(nil)
    }

    private func dispatch(_ action: @escaping (RemotePlaybackHandler) -> Void) {
        guard let handler else { return }
        DispatchQueue.main.async {
            action(handler)
        }
    }
}