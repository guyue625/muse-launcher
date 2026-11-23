import Foundation
import Network

struct Account: Codable, Identifiable {
    let id: UUID
    var name: String
    var proxy: String?

    init(name: String) {
        id = UUID()
        self.name = name
    }
}

struct AccountState: Codable {
    var accounts: [Account] = [Account(name: "Muse 1")]
    var selectedID: UUID?
    var pendingDeletions: [UUID] = []
}

struct AccountFile {
    let url: URL

    static func standard() throws -> AccountFile {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        return AccountFile(url: directory.appendingPathComponent("MuseLauncher/accounts.json"))
    }

    func load() throws -> AccountState {
        do {
            return try JSONDecoder().decode(AccountState.self, from: Data(contentsOf: url))
        } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
            let state = AccountState()
            try save(state)
            return state
        }
    }

    func save(_ state: AccountState) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(state).write(to: url, options: .atomic)
    }
}

struct AccountProxy {
    let address: String
    let configuration: ProxyConfiguration

    init?(_ input: String?) throws {
        let value = input?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !value.isEmpty else { return nil }
        let address = value.contains("://") ? value : "http://" + value
        guard let parts = URLComponents(string: address),
              let scheme = parts.scheme?.lowercased(),
              let host = parts.host, !host.isEmpty,
              let port = parts.port.flatMap(UInt16.init(exactly:)).flatMap(NWEndpoint.Port.init(rawValue:)),
              port.rawValue != 0,
              parts.user == nil, parts.password == nil,
              parts.path.isEmpty || parts.path == "/", parts.query == nil, parts.fragment == nil else {
            throw ProxyInputError.invalidAddress
        }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: port)
        var configuration: ProxyConfiguration
        switch scheme {
        case "http": configuration = ProxyConfiguration(httpCONNECTProxy: endpoint)
        case "https": configuration = ProxyConfiguration(httpCONNECTProxy: endpoint, tlsOptions: NWProtocolTLS.Options())
        case "socks5": configuration = ProxyConfiguration(socksv5Proxy: endpoint)
        default: throw ProxyInputError.invalidAddress
        }
        configuration.allowFailover = false
        self.address = address
        self.configuration = configuration
    }
}

enum ProxyInputError: LocalizedError {
    case invalidAddress

    var errorDescription: String? {
        "请输入带端口的 HTTP、HTTPS 或 SOCKS5 代理地址（例如 127.0.0.1:7890）。暂不支持代理账密。"
    }
}
