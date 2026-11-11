import Foundation
import Network

/// Finds Hearby hosts on the local network with Bonjour (`_hearby._udp`), the same service an
/// Android host registers through NsdManager.
///
/// Starting the browser is what triggers iOS's one-time "find devices on your local network"
/// prompt. The reason shown there comes from NSLocalNetworkUsageDescription, and the service type
/// must be listed under NSBonjourServices in Info.plist or the browser silently finds nothing.
final class HostBrowser: ObservableObject {
    struct Host: Identifiable, Equatable {
        let id: String
        let name: String
        let endpoint: NWEndpoint
    }

    @Published private(set) var hosts: [Host] = []
    /// True when iOS reports that Local Network access was denied for this app.
    @Published private(set) var permissionDenied = false

    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjour(type: Proto.serviceType, domain: nil), using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found: [Host] = results.compactMap { result in
                guard case let .service(name, _, _, _) = result.endpoint else { return nil }
                return Host(id: name, name: HostBrowser.displayName(name), endpoint: result.endpoint)
            }
            self?.hosts = found.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
        browser.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.permissionDenied = false
            case let .waiting(error), let .failed(error):
                // kDNSServiceErr_PolicyDenied: the user declined the Local Network prompt.
                if case let .dns(code) = error, code == -65570 { self?.permissionDenied = true }
            default:
                break
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        hosts = []
    }

    /// Hosts advertise as "Hearby <device name>".
    private static func displayName(_ serviceName: String) -> String {
        let prefix = "Hearby "
        return serviceName.hasPrefix(prefix) && serviceName.count > prefix.count
            ? String(serviceName.dropFirst(prefix.count)) : serviceName
    }
}
