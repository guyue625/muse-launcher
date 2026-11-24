import AppKit
import WebKit

@main
@MainActor
enum MuseLauncherApp {
    static func main() {
        let application = NSApplication.shared
        let delegate = LauncherDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.regular)
        application.run()
    }
}

@MainActor
final class LauncherDelegate: NSObject, NSApplicationDelegate, NSTableViewDataSource, NSTableViewDelegate {
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 720),
                                  styleMask: [.titled, .closable, .miniaturizable, .resizable],
                                  backing: .buffered, defer: false)
    private let table = NSTableView()
    private let browserHost = NSView()
    private let address = NSTextField(labelWithString: "muse.ai")
    private let status = NSTextField(wrappingLabelWithString: "")
    private let statusPanel = NSStackView()
    private let loadingIndicator = NSProgressIndicator()
    private let navigationLoadingIndicator = NSProgressIndicator()
    private let back = NSButton()
    private let reload = NSButton()
    private let home = NSButton()
    private let settings = NSButton()
    private let remove = NSButton()
    private var file: AccountFile!
    private var state = AccountState()
    private var activeID: UUID?
    private var browsers: [UUID: Browser] = [:]
    private var recentlyUsed: [UUID] = []
    private var updatingTable = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            file = try AccountFile.standard()
            state = try file.load()
            if state.accounts.isEmpty {
                state.accounts = [Account(name: "Muse 1")]
                try file.save(state)
            }
        } catch {
            let alert = NSAlert(error: error)
            alert.messageText = "无法读取账号配置"
            alert.runModal()
            NSApp.terminate(nil)
            return
        }
        configureMenu()
        buildWindow()
        activeID = state.accounts.first { $0.id == state.selectedID }?.id ?? state.accounts.first?.id
        refreshAccounts()
        showSelectedAccount()
        state.pendingDeletions.forEach(purge)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func numberOfRows(in tableView: NSTableView) -> Int { state.accounts.count }

    func tableView(_ tableView: NSTableView, viewFor column: NSTableColumn?, row: Int) -> NSView? {
        let account = state.accounts[row]
        let image = NSImageView(image: NSImage(systemSymbolName: "person.crop.circle", accessibilityDescription: nil) ?? NSImage())
        image.contentTintColor = .secondaryLabelColor
        image.symbolConfiguration = .init(pointSize: 15, weight: .regular)
        let label = NSTextField(labelWithString: account.name)
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.lineBreakMode = .byTruncatingTail
        let line = NSStackView(views: [image, label])
        line.spacing = 8
        let cell = NSTableCellView()
        cell.textField = label
        line.translatesAutoresizingMaskIntoConstraints = false
        cell.addSubview(line)
        NSLayoutConstraint.activate([
            line.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 10),
            line.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -8),
            line.centerYAnchor.constraint(equalTo: cell.centerYAnchor),
            image.widthAnchor.constraint(equalToConstant: 17)
        ])
        return cell
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard !updatingTable, state.accounts.indices.contains(table.selectedRow) else { return }
        let id = state.accounts[table.selectedRow].id
        guard id != activeID else { return }
        var next = state
        next.selectedID = id
        guard save(next) else { refreshAccounts(); return }
        activeID = id
        showSelectedAccount()
    }

    private var currentAccount: Account? { state.accounts.first { $0.id == activeID } }
    private var currentBrowser: Browser? { activeID.flatMap { browsers[$0] } }

    private func configureMenu() {
        let menu = NSMenu()
        let appItem = NSMenuItem()
        let editItem = NSMenuItem()
        let viewItem = NSMenuItem()
        [appItem, editItem, viewItem].forEach(menu.addItem)
        let appMenu = NSMenu(title: "muse 多开器")
        appMenu.addItem(withTitle: "退出 muse 多开器", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        let editMenu = NSMenu(title: "编辑")
        editMenu.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = editMenu
        let viewMenu = NSMenu(title: "显示")
        let add = viewMenu.addItem(withTitle: "添加账号", action: #selector(addAccount), keyEquivalent: "n")
        add.target = self
        let refresh = viewMenu.addItem(withTitle: "重新加载", action: #selector(reloadPage), keyEquivalent: "r")
        refresh.target = self
        viewItem.submenu = viewMenu
        NSApp.mainMenu = menu
    }

    private func buildWindow() {
        let root = NSView()
        window.contentView = root
        window.minSize = NSSize(width: 660, height: 460)
        window.title = "muse 多开器"
        window.center()

        let sidebar = NSVisualEffectView()
        sidebar.material = .sidebar
        let divider = NSBox()
        divider.boxType = .separator
        let caption = NSTextField(labelWithString: "账号")
        caption.font = .systemFont(ofSize: 12, weight: .semibold)
        caption.textColor = .secondaryLabelColor

        let add = button("plus", "添加账号", #selector(addAccount))
        settings.image = NSImage(systemSymbolName: "gearshape", accessibilityDescription: "账号设置")
        settings.target = self
        settings.action = #selector(editAccount)
        settings.toolTip = "账号设置"
        remove.image = NSImage(systemSymbolName: "trash", accessibilityDescription: "删除账号")
        remove.target = self
        remove.action = #selector(deleteAccount)
        remove.toolTip = "删除账号"
        let actions = NSStackView(views: [add, settings, remove])
        actions.spacing = 8

        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("Account"))
        table.addTableColumn(column)
        table.headerView = nil
        table.style = .sourceList
        table.backgroundColor = .clear
        table.rowHeight = 33
        table.dataSource = self
        table.delegate = self
        let scroll = NSScrollView()
        scroll.documentView = table
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true

        back.image = NSImage(systemSymbolName: "chevron.left", accessibilityDescription: "返回")
        back.target = self
        back.action = #selector(goBack)
        back.toolTip = "返回"
        reload.image = NSImage(systemSymbolName: "arrow.clockwise", accessibilityDescription: "重新加载")
        reload.target = self
        reload.action = #selector(reloadPage)
        reload.toolTip = "重新加载"
        home.image = NSImage(systemSymbolName: "house", accessibilityDescription: "返回 Muse")
        home.target = self
        home.action = #selector(goHome)
        home.toolTip = "返回 Muse"
        address.textColor = .secondaryLabelColor
        address.font = .systemFont(ofSize: 13, weight: .medium)
        navigationLoadingIndicator.style = .spinning
        navigationLoadingIndicator.controlSize = .small
        navigationLoadingIndicator.isIndeterminate = true
        navigationLoadingIndicator.isDisplayedWhenStopped = false
        navigationLoadingIndicator.setAccessibilityLabel("网页正在加载")
        let navigation = NSStackView(views: [back, reload, home, address, navigationLoadingIndicator])
        navigation.setVisibilityPriority(.mustHold, for: navigationLoadingIndicator)
        navigation.spacing = 10
        let navigationDivider = NSBox()
        navigationDivider.boxType = .separator

        [sidebar, divider, navigation, navigationDivider, browserHost].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; root.addSubview($0) }
        [caption, scroll, actions].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; sidebar.addSubview($0) }
        status.alignment = .center
        status.textColor = .labelColor
        status.maximumNumberOfLines = 0
        loadingIndicator.style = .spinning
        loadingIndicator.controlSize = .regular
        loadingIndicator.isIndeterminate = true
        loadingIndicator.isDisplayedWhenStopped = false
        loadingIndicator.setAccessibilityLabel("正在连接 Muse")
        statusPanel.orientation = .vertical
        statusPanel.alignment = .centerX
        statusPanel.spacing = 16
        statusPanel.addArrangedSubview(loadingIndicator)
        statusPanel.addArrangedSubview(status)
        statusPanel.translatesAutoresizingMaskIntoConstraints = false
        browserHost.addSubview(statusPanel)

        NSLayoutConstraint.activate([
            sidebar.leadingAnchor.constraint(equalTo: root.leadingAnchor), sidebar.topAnchor.constraint(equalTo: root.topAnchor),
            sidebar.bottomAnchor.constraint(equalTo: root.bottomAnchor), sidebar.widthAnchor.constraint(equalToConstant: 212),
            divider.leadingAnchor.constraint(equalTo: sidebar.trailingAnchor), divider.topAnchor.constraint(equalTo: root.topAnchor),
            divider.bottomAnchor.constraint(equalTo: root.bottomAnchor), divider.widthAnchor.constraint(equalToConstant: 1),
            caption.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 18), caption.topAnchor.constraint(equalTo: sidebar.topAnchor, constant: 16),
            scroll.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 7), scroll.trailingAnchor.constraint(equalTo: sidebar.trailingAnchor, constant: -7),
            scroll.topAnchor.constraint(equalTo: caption.bottomAnchor, constant: 8), scroll.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -8),
            actions.leadingAnchor.constraint(equalTo: sidebar.leadingAnchor, constant: 14), actions.bottomAnchor.constraint(equalTo: sidebar.bottomAnchor, constant: -12),
            navigation.leadingAnchor.constraint(equalTo: divider.trailingAnchor, constant: 12), navigation.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -12),
            navigation.topAnchor.constraint(equalTo: root.topAnchor, constant: 7),
            navigationDivider.leadingAnchor.constraint(equalTo: divider.trailingAnchor), navigationDivider.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            navigationDivider.topAnchor.constraint(equalTo: navigation.bottomAnchor, constant: 7),
            browserHost.leadingAnchor.constraint(equalTo: divider.trailingAnchor), browserHost.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            browserHost.topAnchor.constraint(equalTo: navigationDivider.bottomAnchor), browserHost.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            navigationLoadingIndicator.widthAnchor.constraint(equalToConstant: 16),
            navigationLoadingIndicator.heightAnchor.constraint(equalToConstant: 16),
            loadingIndicator.widthAnchor.constraint(equalToConstant: 32), loadingIndicator.heightAnchor.constraint(equalToConstant: 32),
            statusPanel.centerXAnchor.constraint(equalTo: browserHost.centerXAnchor), statusPanel.centerYAnchor.constraint(equalTo: browserHost.centerYAnchor),
            statusPanel.leadingAnchor.constraint(greaterThanOrEqualTo: browserHost.leadingAnchor, constant: 24),
            statusPanel.trailingAnchor.constraint(lessThanOrEqualTo: browserHost.trailingAnchor, constant: -24)
        ])
        window.makeKeyAndOrderFront(nil)
    }

    private func button(_ symbol: String, _ title: String, _ action: Selector) -> NSButton {
        let result = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: title) ?? NSImage(), target: self, action: action)
        result.bezelStyle = .texturedRounded
        result.toolTip = title
        return result
    }

    private func refreshAccounts() {
        updatingTable = true
        table.reloadData()
        if let index = state.accounts.firstIndex(where: { $0.id == activeID }) {
            table.selectRowIndexes(IndexSet(integer: index), byExtendingSelection: false)
            table.scrollRowToVisible(index)
        } else { table.deselectAll(nil) }
        updatingTable = false
    }

    private func showSelectedAccount() {
        browsers.values.forEach { $0.view.removeFromSuperview() }
        guard let account = currentAccount else { updateNavigation(); return }
        do {
            let browser = try browsers[account.id] ?? Browser(account: account)
            browsers[account.id] = browser
            browser.didChange = { [weak self] in
                guard self?.activeID == account.id else { return }
                self?.updateNavigation()
            }
            browser.view.translatesAutoresizingMaskIntoConstraints = false
            browserHost.addSubview(browser.view, positioned: .below, relativeTo: statusPanel)
            NSLayoutConstraint.activate([
                browser.view.leadingAnchor.constraint(equalTo: browserHost.leadingAnchor),
                browser.view.trailingAnchor.constraint(equalTo: browserHost.trailingAnchor),
                browser.view.topAnchor.constraint(equalTo: browserHost.topAnchor),
                browser.view.bottomAnchor.constraint(equalTo: browserHost.bottomAnchor)
            ])
            recentlyUsed.removeAll { $0 == account.id }
            recentlyUsed.append(account.id)
            while recentlyUsed.count > 3, let oldest = recentlyUsed.first { discardBrowser(oldest) }
            updateNavigation()
        } catch { showError("无法打开网页", error.localizedDescription) }
    }

    private func updateNavigation() {
        let browser = currentBrowser
        window.title = currentAccount.map { "\($0.name) · muse 多开器" } ?? "muse 多开器"
        address.stringValue = browser?.view.url?.host ?? "muse.ai"
        back.isEnabled = browser?.view.canGoBack ?? false
        home.isEnabled = browser != nil
        reload.isEnabled = browser != nil
        settings.isEnabled = currentAccount != nil
        remove.isEnabled = currentAccount != nil
        let isLoading = browser?.isLoading == true && browser?.failure == nil
        let showsInitialLoading = isLoading && browser?.hasCommittedPage == false
        if let failure = browser?.failure {
            status.stringValue = "无法打开 muse.ai\n\n\(failure)"
        } else if showsInitialLoading {
            status.stringValue = "正在加载 Muse…\n\n首次连接可能需要几秒，请稍候。"
        } else {
            status.stringValue = ""
        }
        statusPanel.isHidden = browser == nil || (browser?.failure == nil && !showsInitialLoading)
        loadingIndicator.isHidden = !showsInitialLoading
        navigationLoadingIndicator.isHidden = !isLoading
        if showsInitialLoading { loadingIndicator.startAnimation(nil) }
        else { loadingIndicator.stopAnimation(nil) }
        if isLoading { navigationLoadingIndicator.startAnimation(nil) }
        else { navigationLoadingIndicator.stopAnimation(nil) }
        browser?.view.isHidden = showsInitialLoading || (browser?.failure != nil && browser?.hasCommittedPage == false)
    }

    @objc private func goBack() { currentBrowser?.view.goBack() }
    @objc private func reloadPage() { currentBrowser?.reload() }
    @objc private func goHome() { currentBrowser?.goHome() }
    @objc private func addAccount() { showEditor(existing: nil) }
    @objc private func editAccount() { if let currentAccount { showEditor(existing: currentAccount) } }

    private func showEditor(existing: Account?, draft: (String, String)? = nil, error: String? = nil) {
        let alert = NSAlert()
        alert.messageText = existing == nil ? "添加账号" : "账号设置"
        alert.informativeText = error ?? "默认代理为 \(defaultProxyAddress)。留空则使用 macOS 系统代理。"
        alert.addButton(withTitle: "保存")
        alert.addButton(withTitle: "取消")
        let name = NSTextField(string: draft?.0 ?? existing?.name ?? "Muse \(state.accounts.count + 1)")
        let proxy = NSTextField(string: draft?.1 ?? (existing == nil ? defaultProxyAddress : existing?.proxy ?? ""))
        proxy.placeholderString = "留空使用系统代理"
        let grid = NSGridView(views: [[NSTextField(labelWithString: "名称"), name], [NSTextField(labelWithString: "代理"), proxy]])
        grid.columnSpacing = 12
        grid.rowSpacing = 8
        name.widthAnchor.constraint(equalToConstant: 230).isActive = true
        proxy.widthAnchor.constraint(equalTo: name.widthAnchor).isActive = true
        grid.frame.size = grid.fittingSize
        alert.accessoryView = grid
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .alertFirstButtonReturn else { return }
            let title = name.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            let proxyAddress = proxy.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { self.showEditor(existing: existing, draft: (name.stringValue, proxy.stringValue), error: "请输入账号名称。"); return }
            do { _ = try AccountProxy(proxyAddress) }
            catch { self.showEditor(existing: existing, draft: (title, proxy.stringValue), error: error.localizedDescription); return }
            if let existing, let index = self.state.accounts.firstIndex(where: { $0.id == existing.id }) {
                self.discardBrowser(existing.id)
                self.state.accounts[index].name = title
                self.state.accounts[index].proxy = proxyAddress.isEmpty ? nil : proxyAddress
            } else {
                var created = Account(name: title)
                created.proxy = proxyAddress.isEmpty ? nil : proxyAddress
                self.state.accounts.append(created)
                self.activeID = created.id
                self.state.selectedID = created.id
            }
            if self.save(self.state) { self.refreshAccounts(); self.showSelectedAccount() }
        }
    }

    @objc private func deleteAccount() {
        guard let account = currentAccount else { return }
        let alert = NSAlert()
        alert.messageText = "删除“\(account.name)”及其登录数据？"
        alert.informativeText = "该账号的 Cookie、登录状态和本地网站数据也会被永久清除。"
        alert.addButton(withTitle: "删除")
        alert.addButton(withTitle: "取消")
        alert.alertStyle = .warning
        alert.beginSheetModal(for: window) { [weak self] response in
            guard response == .alertFirstButtonReturn else { return }
            self?.delete(account)
        }
    }

    private func delete(_ account: Account) {
        guard savePendingDeletion(account.id) else { return }
        browsers.removeValue(forKey: account.id)?.view.removeFromSuperview()
        recentlyUsed.removeAll { $0 == account.id }
        state.accounts.removeAll { $0.id == account.id }
        state.pendingDeletions.append(account.id)
        if state.accounts.isEmpty { state.accounts.append(Account(name: "Muse 1")) }
        activeID = state.accounts.first?.id
        state.selectedID = activeID
        guard save(state) else { return }
        refreshAccounts()
        showSelectedAccount()
        purge(account.id)
    }

    private func savePendingDeletion(_ id: UUID) -> Bool {
        state.pendingDeletions.append(id)
        return save(state)
    }

    private func purge(_ id: UUID) {
        let store = WKWebsiteDataStore(forIdentifier: id)
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            guard let self else { return }
            self.state.pendingDeletions.removeAll { $0 == id }
            _ = self.save(self.state)
        }
    }

    private func discardBrowser(_ id: UUID) {
        browsers.removeValue(forKey: id)?.view.removeFromSuperview()
        recentlyUsed.removeAll { $0 == id }
    }

    private func save(_ newState: AccountState) -> Bool {
        do { try file.save(newState); state = newState; return true }
        catch { showError("无法保存账号", error.localizedDescription); return false }
    }

    private func showError(_ title: String, _ detail: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = detail
        alert.beginSheetModal(for: window)
    }
}
