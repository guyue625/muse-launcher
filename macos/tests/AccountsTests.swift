import Foundation

private struct TestFailure: Error, CustomStringConvertible {
    let description: String
}

private func expect(_ condition: Bool, _ message: String) throws {
    if !condition { throw TestFailure(description: message) }
}

@main
enum AccountsTests {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = AccountFile(url: directory.appendingPathComponent("accounts.json"))

        let fresh = try file.load()
        try expect(fresh.accounts.first?.proxy == defaultProxyAddress, "New accounts must use port 7897")
        try expect(fresh.schemaVersion == 2, "New files must use the current schema")

        let missingID = UUID()
        let blankID = UUID()
        let customID = UUID()
        let pendingID = UUID()
        let legacy = """
        {"accounts":[
            {"id":"\(missingID)","name":"Missing proxy"},
            {"id":"\(blankID)","name":"Blank proxy","proxy":"  "},
            {"id":"\(customID)","name":"Custom proxy","proxy":"socks5://127.0.0.1:1080"}
        ],"selectedID":"\(customID)","pendingDeletions":["\(pendingID)"]}
        """
        try Data(legacy.utf8).write(to: file.url)
        let migrated = try file.load()
        try expect(migrated.accounts[0].proxy == defaultProxyAddress, "Missing proxies must migrate")
        try expect(migrated.accounts[1].proxy == defaultProxyAddress, "Blank proxies must migrate")
        try expect(migrated.accounts[2].proxy == "socks5://127.0.0.1:1080", "Custom proxies must not change")
        try expect(migrated.accounts.map(\.id) == [missingID, blankID, customID], "Account identities must not change")
        try expect(migrated.selectedID == customID && migrated.pendingDeletions == [pendingID], "Selection and deletions must survive")
        let persisted = try JSONDecoder().decode(AccountState.self, from: Data(contentsOf: file.url))
        try expect(persisted.schemaVersion == 2 && persisted.accounts[0].proxy == defaultProxyAddress, "Migration must persist immediately")

        var systemProxy = migrated
        systemProxy.accounts[0].proxy = nil
        systemProxy.accounts[1].proxy = ""
        try file.save(systemProxy)
        let reopened = try file.load()
        try expect(reopened.accounts[0].proxy == nil && reopened.accounts[1].proxy == "", "Cleared proxies must stay cleared after restart")

        let minimal = "{\"accounts\":[{\"id\":\"\(missingID)\",\"name\":\"Old account\"}]}"
        try Data(minimal.utf8).write(to: file.url)
        let minimalState = try file.load()
        try expect(minimalState.accounts[0].proxy == defaultProxyAddress && minimalState.pendingDeletions.isEmpty, "Older files with missing optional fields must load")

        for address in [defaultProxyAddress, "http://127.0.0.1:7897", "https://proxy.example:443", "socks5://127.0.0.1:1080", "http://[::1]:7897"] {
            try expect(try AccountProxy(address) != nil, "Valid proxy rejected: \(address)")
        }
        try expect(try AccountProxy("  ") == nil, "Blank input must select the system proxy")
        try expect(try AccountProxy(nil) == nil, "Missing input must select the system proxy")
        for address in ["127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "ftp://127.0.0.1:7897", "http://user@127.0.0.1:7897", "http://127.0.0.1:7897/path"] {
            do {
                _ = try AccountProxy(address)
                throw TestFailure(description: "Invalid proxy accepted: \(address)")
            } catch ProxyInputError.invalidAddress {}
        }
        print("PASS: account defaults, migration, restart persistence, and proxy validation")
    }
}
