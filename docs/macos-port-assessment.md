# macOS 移植评估

## 结论

macOS 版本可行，但不是把现有 Android 工程改几个配置就能运行。当前仓库是纯 Android 应用：构建依赖 Android Gradle Plugin、Android SDK 和 AndroidX；界面与主要行为集中在 Android `Activity`、`View`、`Intent` 和 `WebView` API 中。`MainActivity.java` 单文件约 3,389 行，Android 侧没有可直接复用的桌面 UI 层。

建议保留现有 Android 应用，在同一仓库新增独立 macOS 原生应用（SwiftUI/AppKit + WebKit）。不要尝试把 Android Java 源码编译成 macOS 应用，也不要在第一阶段把整个 Gradle 工程迁移到跨平台 UI 框架。

## 可复用与需重做

- **可复用概念/数据**：账号名称、头像、标签、代理配置等产品模型；muse.ai 地址与会话识别规则；多账号隔离、切换和下载的产品行为。
- **需重写**：主界面、账号配置、账号持久化适配、Web 内容容器、文件选择/下载、代理应用、权限与系统菜单、快捷入口、应用更新。
- **不能直接复用的实现**：Android `WebView` / AndroidX Multi-Profile、`SharedPreferences`、`Intent`、`MediaStore`、`DownloadManager`、`FileProvider`、桌面快捷方式 API、运行时权限和 APK 在线更新。

## macOS 技术路径

- 用 SwiftUI/AppKit 构建独立 `.app`，以 `WKWebView` 承载 muse.ai 网页。
- 每个账号使用独立、可持久化的 WebKit 网站数据存储，保持 Cookie 与网站数据隔离。目标 macOS 版本需要据实际使用的 WebKit API 确定；较新的持久化数据存储 API 有系统版本要求，需提供最低版本或降级策略。
- 账号元数据保存在应用容器中的 JSON 或轻量数据库；用户数据留在 macOS 沙盒容器，下载通过 NSSavePanel/系统下载目录处理。
- 代理需单独验证 WebKit 支持范围。不要假定 Android 当前“每账号代理”的实现可以照搬；尤其要覆盖 SOCKS5、认证代理、WebSocket 与 WebRTC/语音场景。
- 麦克风、摄像头、附件上传、下载和外部链接均需适配 macOS 隐私权限及 WebKit delegate，并在真实 Mac 上验证。

## 风险与范围

1. **代理是主要技术风险**：系统代理与每账号独立代理不是一回事；不同 macOS/WebKit 版本对自定义代理的支持能力可能不同。
2. **会话隔离需实测**：必须确认多个持久化数据存储能够并行使用、重启后保留登录态、删除账号后确实清除对应数据。
3. **功能 parity 不是首版必要条件**：建议首版先完成账号 CRUD、独立持久登录、切换、基础附件上传/下载和外链处理；代理、语音/摄像头、自动更新和高级媒体下载分阶段补齐。
4. **打包/分发需要 Apple 工具链**：签名、Developer ID、公证与最终 `.dmg`/`.zip` 分发需要 macOS 上的 Xcode 工具链和开发者账户设置；当前环境仅有 Command Line Tools，`xcodebuild` 不可用，因此无法在此完成 Xcode 编译或签名验证。

## 推荐实施顺序

1. 新增独立 `macos/` 原生应用骨架，不改 Android 的 Gradle 模块。
2. 设计并测试账号数据模型及每账号持久化 WebKit store 生命周期。
3. 实现账号列表、创建/编辑/删除和 Web 内容切换。
4. 验证代理能力与限制，再决定首版是否支持每账号代理及其协议范围。
5. 补齐上传、下载、权限、键盘快捷键、深色模式和系统集成。
6. 在真实 Mac 上执行构建、隔离/登录态测试，再配置签名、公证和分发。

## 当前检查结果

- 仓库当前只有 Android Gradle `:app` 模块，没有 macOS/Xcode 工程。
- Android 版本要求 JDK 17 和 Android SDK 34；当前执行环境检测到 Java 8，Gradle wrapper 也因用户级缓存目录不可写而未能启动，所以本次没有运行 Android 构建或测试。
- 当前环境缺少完整 Xcode，不能验证新建 macOS target 的构建结果。
