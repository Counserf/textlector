import Foundation
import UIKit
import ComposeApp

@objc final class IosDiagnosticLogExporter: NSObject, DiagnosticLogExporter {
    func export(logText: String) -> Bool {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        let device = UIDevice.current
        let timestamp = ISO8601DateFormatter().string(from: Date())

        let report = """
        TextLector diagnostics
        exported: \(timestamp)
        app: \(version) (\(build))
        device: \(device.model)
        system: \(device.systemName) \(device.systemVersion)

        ----- persistent log -----
        \(logText)
        """

        let filename = "TextLector-diagnostics-\(Int(Date().timeIntervalSince1970)).txt"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)

        do {
            try report.write(to: url, atomically: true, encoding: .utf8)
        } catch {
            return false
        }

        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else { return }
            let share = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = share.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = CGRect(
                    x: presenter.view.bounds.midX,
                    y: presenter.view.bounds.midY,
                    width: 1,
                    height: 1
                )
                popover.permittedArrowDirections = []
            }
            presenter.present(share, animated: true)
        }
        return true
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes
            .flatMap { $0.windows }
            .first(where: { $0.isKeyWindow })
        var controller = window?.rootViewController

        while let presented = controller?.presentedViewController {
            controller = presented
        }
        if let nav = controller as? UINavigationController {
            return nav.visibleViewController ?? nav
        }
        if let tab = controller as? UITabBarController {
            return tab.selectedViewController ?? tab
        }
        return controller
    }
}
