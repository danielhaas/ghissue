import Foundation
import Network
import Observation

@Observable
final class NetworkMonitor {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "NetworkMonitor")

    var isConnected = true
    var onConnectivityRestored: (() -> Void)?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let wasConnected = self?.isConnected ?? true
            let nowConnected = path.status == .satisfied
            DispatchQueue.main.async {
                self?.isConnected = nowConnected
                if !wasConnected && nowConnected {
                    self?.onConnectivityRestored?()
                }
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}
