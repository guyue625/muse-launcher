package com.seekstars.muselauncher;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://muse.ai/";
    private static final int REQ_FILE = 10001;
    private static final int REQ_CONFIG = 10002;
    private static final int REQ_WEB_PERMISSION = 10003;
    private static final long MIN_SPLASH_MS = 500;
    private static final long SPLASH_TIMEOUT_MS = 8000;
    private static final long POPUP_BRIDGE_TIMEOUT_MS = 15000;
    /** muse.ai 登录会话 cookie（httpOnly）。匿名访问只会落下 hatch_native_auth_* 等流程 cookie。 */
    private static final String AUTH_COOKIE_MARKER = "hatch_sess=";

    private AccountsStore store;
    private ProfileStore profileStore;
    private boolean darkTheme = true;

    private View rootFrame;
    private DrawerLayout drawerLayout;
    private View drawerContent;
    private View rail;
    private View edgeSwipe;
    private View mainContentArea;
    private ImageButton btnRailExpand;
    private ImageButton btnThemeToggle;
    private ImageButton btnFullscreen;
    private ImageButton btnRailAdd;
    private ImageButton btnDrawerThemeToggle;
    private ImageButton btnCollapse;
    private LinearLayout railAvatars;
    private LinearLayout accountList;
    private View searchBox;
    private ImageView searchIcon;
    private EditText searchInput;
    private TextView drawerTitle;
    private View drawerEmpty;
    private TextView accCount;
    private View btnAddAccount;
    private ImageView addPlusIcon;
    private TextView addAccountTitle;
    private TextView addAccountSub;
    private ImageView shieldIcon;
    private TextView drawerFootnote;
    private FrameLayout webContainer;
    private View emptyState;
    private TextView emptyTile;
    private TextView emptyTitle;
    private TextView emptySub;
    private View splash;
    private TextView splashTile;
    private TextView splashName;
    private TextView splashSub;
    private View errorOverlay;
    private TextView errorTile;
    private TextView errorTitle;
    private TextView errorMsg;
    private TextView errorHint;
    private View btnBackToMuse;
    private ImageView iconBackToMuse;
    private TextView textBackToMuse;
    private ProgressBar webLoadProgress;
    private FrameLayout fullscreenVideoContainer;
    private View customFullscreenView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private Dialog activeContextDialog;
    private View downloadBanner;
    private FrameLayout downloadBadge;
    private ImageView downloadBadgeIcon;
    private TextView textDownloadTitle;
    private TextView textDownloadSaved;
    private ProgressBar downloadProgressBar;
    private TextView btnOpenDownload;
    private View downloadDirCard;
    private TextView downloadDirTitle;
    private TextView downloadDirPath;
    private TextView btnPickDownloadDir;
    private TextView btnResetDownloadDir;
    private View updateCard;
    private TextView updateCardTitle;
    private View updateBadgeDot;
    private TextView updateVersionSub;
    private TextView btnCheckUpdate;
    private ActivityResultLauncher<Uri> pickDownloadDirLauncher;
    private ActivityResultLauncher<Intent> installPermissionLauncher;

    private static final String UPDATE_DOWNLOAD_TOKEN = "__app_update__";
    /** 闲置 30 秒自动回收下载工作线程，不下载时零后台线程常驻 */
    private final ExecutorService downloadExecutor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final Map<String, WebDownloadSession> activeWebDownloads = new ConcurrentHashMap<>();
    private String bannerDownloadToken;
    private boolean bannerShowsApkReady;
    private MediaDownloadHelper.SavedMedia lastSavedMedia;
    private AppUpdateHelper.ReleaseInfo availableUpdate;
    private boolean checkingUpdate;
    private Dialog activeUpdateDialog;
    private File pendingInstallApk;
    private AtomicBoolean activeUpdateCancelFlag;
    private final Runnable hideDownloadBannerRunnable = this::hideDownloadBannerAnimated;

    /** 每个账号一个独立 WebView（Profile 只能在 WebView 初始化前设置一次）；按设备内存动态设定常驻上限 */
    private LruAccountRegistry<WebView> webViews = new LruAccountRegistry<>(3);
    private final SparseArray<Boolean> webLoaded = new SparseArray<>();
    /** 主框架加载出错标记（错误遮罩依据），重试成功后清除 */
    private final SparseArray<Boolean> webErrored = new SparseArray<>();
    /** 加载耗时统计起点 */
    private final SparseArray<Long> loadStartAt = new SparseArray<>();
    /** 临时用于捕获 window.open / target="_blank" 的桥接 WebView，防止泄漏 */
    private final Set<WebView> transientPopups = new HashSet<>();
    private WebView currentWebView;
    private Context cachedThemedContext;
    private boolean timersPaused;
    private boolean inBackground;
    private boolean delayedEvictPending;
    private long lastLoggedProbeAt;
    private final Runnable delayedEvictRunnable = () -> {
        delayedEvictPending = false;
        if (inBackground && !isFinishing() && !isDestroyed()) {
            AvatarStore.trimMemory();
            AvatarTile.trimMemory();
            evictInactiveWebViews();
        }
    };

    private long editId = -1;
    private long confirmDelId = -1;
    private EditText renameInput;
    private long splashShownAt;
    private final Runnable hideSplashRunnable = this::hideSplash;
    private ValueCallback<Uri[]> fileCallback;
    private File pendingCameraFile;
    private Uri pendingCameraUri;
    private PermissionRequest pendingPermissionRequest;
    private String[] pendingPermissionResources;
    private boolean fullscreen;
    private float edgeStartX;
    private long edgeDownAt;
    private float fabDownRawX;
    private float fabDownRawY;
    private float fabStartTransX;
    private float fabStartTransY;
    private boolean fabDragging;

    /** M4: 登录态分块探测。androidx Profile API 强制主线程调用，无法后台化；
     *  改为主线程分块（每块 20 个账号，块间让出一帧），账号多时不阻塞 UI，脏标记合并请求 */
    private static final int PROBE_CHUNK = 20;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean probeRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean probeDirty =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private List<Account> probeQueue;
    private int probeIndex;
    private boolean probeChanged;

    private final Runnable probeStep = this::probeStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new AccountsStore(this);
        darkTheme = store.isDarkTheme();
        AppCompatDelegate.setDefaultNightMode(
                darkTheme ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            showWebViewUpgradeDialog();
            return;
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        setContentView(R.layout.activity_main);
        webViews = new LruAccountRegistry<>(resolveWebViewCapacity());
        checkStoreNotice();
        profileStore = ProfileStore.getInstance();

        rootFrame = findViewById(R.id.rootFrame);
        drawerLayout = findViewById(R.id.drawerLayout);
        drawerContent = findViewById(R.id.drawerContent);
        rail = findViewById(R.id.rail);
        edgeSwipe = findViewById(R.id.edgeSwipe);
        mainContentArea = findViewById(R.id.mainContentArea);
        btnRailExpand = findViewById(R.id.btnRailExpand);
        btnThemeToggle = findViewById(R.id.btnThemeToggle);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnRailAdd = findViewById(R.id.btnRailAdd);
        btnDrawerThemeToggle = findViewById(R.id.btnDrawerThemeToggle);
        btnCollapse = findViewById(R.id.btnCollapse);
        railAvatars = findViewById(R.id.railAvatars);
        accountList = findViewById(R.id.accountList);
        searchBox = findViewById(R.id.searchBox);
        searchIcon = findViewById(R.id.searchIcon);
        searchInput = findViewById(R.id.drawerSearch);
        drawerTitle = findViewById(R.id.drawerTitle);
        drawerEmpty = findViewById(R.id.drawerEmpty);
        accCount = findViewById(R.id.accCount);
        btnAddAccount = findViewById(R.id.btnAddAccount);
        addPlusIcon = findViewById(R.id.addPlusIcon);
        addAccountTitle = findViewById(R.id.addAccountTitle);
        addAccountSub = findViewById(R.id.addAccountSub);
        shieldIcon = findViewById(R.id.shieldIcon);
        drawerFootnote = findViewById(R.id.drawerFootnote);
        webContainer = findViewById(R.id.webContainer);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorTile = findViewById(R.id.errorTile);
        errorTitle = findViewById(R.id.errorTitle);
        errorMsg = findViewById(R.id.errorMsg);
        errorHint = findViewById(R.id.errorHint);
        emptyState = findViewById(R.id.emptyState);
        emptyTile = findViewById(R.id.emptyTile);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptySub = findViewById(R.id.emptySub);
        splash = findViewById(R.id.splash);
        splashTile = findViewById(R.id.splashTile);
        splashName = findViewById(R.id.splashName);
        splashSub = findViewById(R.id.splashSub);
        btnBackToMuse = findViewById(R.id.btnBackToMuse);
        iconBackToMuse = findViewById(R.id.iconBackToMuse);
        textBackToMuse = findViewById(R.id.textBackToMuse);
        webLoadProgress = findViewById(R.id.webLoadProgress);
        fullscreenVideoContainer = findViewById(R.id.fullscreenVideoContainer);
        downloadBanner = findViewById(R.id.downloadBanner);
        downloadBadge = findViewById(R.id.downloadBadge);
        downloadBadgeIcon = findViewById(R.id.downloadBadgeIcon);
        textDownloadTitle = findViewById(R.id.textDownloadTitle);
        textDownloadSaved = findViewById(R.id.textDownloadSaved);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        btnOpenDownload = findViewById(R.id.btnOpenDownload);
        downloadDirCard = findViewById(R.id.downloadDirCard);
        downloadDirTitle = findViewById(R.id.downloadDirTitle);
        downloadDirPath = findViewById(R.id.downloadDirPath);
        btnPickDownloadDir = findViewById(R.id.btnPickDownloadDir);
        btnResetDownloadDir = findViewById(R.id.btnResetDownloadDir);
        updateCard = findViewById(R.id.updateCard);
        updateCardTitle = findViewById(R.id.updateCardTitle);
        updateBadgeDot = findViewById(R.id.updateBadgeDot);
        updateVersionSub = findViewById(R.id.updateVersionSub);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        if (downloadBanner != null) {
            downloadBanner.setOnClickListener(v -> {
                if (bannerDownloadToken != null) return;
                if (bannerShowsApkReady && pendingInstallApk != null && pendingInstallApk.exists()) {
                    requestInstallApk(pendingInstallApk);
                } else {
                    openLastDownloadedMedia();
                }
            });
        }
        if (btnOpenDownload != null) {
            btnOpenDownload.setOnClickListener(v -> {
                if (bannerDownloadToken != null) {
                    cancelActiveDownload(bannerDownloadToken);
                } else if (bannerShowsApkReady && pendingInstallApk != null && pendingInstallApk.exists()) {
                    requestInstallApk(pendingInstallApk);
                } else {
                    openLastDownloadedMedia();
                }
            });
        }
        // 启动时异步清理过期拍照缓存与旧版安装包，避免缓存目录膨胀
        downloadExecutor.execute(() -> {
            long now = System.currentTimeMillis();
            ResourcePolicy.cleanupStaleCacheFiles(
                    new File(getCacheDir(), "captures"), now, ResourcePolicy.CAPTURE_CACHE_MAX_AGE_MS);
            ResourcePolicy.cleanupStaleCacheFiles(
                    new File(getCacheDir(), "updates"), now, 0L);
        });
        pickDownloadDirLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onDownloadDirPicked);
        installPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    File apk = pendingInstallApk;
                    if (apk != null && apk.exists() && getPackageManager().canRequestPackageInstalls()) {
                        launchApkInstaller(apk);
                    }
                });
        if (btnPickDownloadDir != null) {
            btnPickDownloadDir.setOnClickListener(v -> openDownloadDirPicker());
        }
        if (btnResetDownloadDir != null) {
            btnResetDownloadDir.setOnClickListener(v -> resetDownloadDir());
        }
        View.OnClickListener updateClickListener = v -> {
            if (availableUpdate != null) {
                showUpdateDialog(availableUpdate);
            } else {
                checkForUpdates(true);
            }
        };
        if (btnCheckUpdate != null) btnCheckUpdate.setOnClickListener(updateClickListener);
        if (updateCard != null) updateCard.setOnClickListener(updateClickListener);
        refreshDownloadDirUi();
        refreshUpdateCardUi();
        // 冷启动满 24 小时延迟 3.5 秒静默检查一次更新，避免与首屏网页加载争抢带宽
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && store != null
                    && AppUpdateHelper.shouldAutoCheck(store.getLastUpdateCheckAt(), System.currentTimeMillis())) {
                checkForUpdates(false);
            }
        }, 3500L);

        btnRailExpand.setOnClickListener(v -> drawerLayout.openDrawer(drawerContent));
        btnCollapse.setOnClickListener(v -> drawerLayout.closeDrawer(drawerContent));
        btnThemeToggle.setOnClickListener(v -> setDarkTheme(!darkTheme, true));
        btnDrawerThemeToggle.setOnClickListener(v -> setDarkTheme(!darkTheme, true));
        btnRailAdd.setOnClickListener(v -> addAccount());
        btnAddAccount.setOnClickListener(v -> addAccount());
        findViewById(R.id.btnEmptyAdd).setOnClickListener(v -> addAccount());
        btnFullscreen.setOnClickListener(v -> enterFullscreen());
        setupBackToMuseButton();
        applyThemeToNativeUi();

        // 加载失败遮罩：重试按钮重新加载当前账号页面
        findViewById(R.id.btnRetry).setOnClickListener(v -> {
            long curId = store.getCurrentId();
            if (currentWebView != null && curId >= 0) {
                webErrored.put((int) curId, false);
                hideErrorOverlay();
                loadStartAt.put((int) curId, SystemClock.uptimeMillis());
                Log.i("MusePerf", "load retry id=" + curId);
                currentWebView.reload();
            }
        });

        // 搜索框：输入即时按名称 + 标签过滤
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderAccounts();
            }
        });

        edgeSwipe.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    edgeStartX = event.getX();
                    edgeDownAt = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_UP: {
                    float dx = event.getX() - edgeStartX;
                    boolean swipeIn = dx > dp(24);
                    boolean tap = Math.abs(dx) < dp(8)
                            && (SystemClock.uptimeMillis() - edgeDownAt) < 500;
                    if (swipeIn || tap) exitFullscreen();
                    return true;
                }
                default:
                    return true;
            }
        });

        Intent launchIntent = getIntent();
        boolean shortcutIntent = ShortcutsHelper.isShortcutIntent(launchIntent);
        boolean viaShortcut = savedInstanceState == null && handleShortcutIntent(launchIntent);

        if (!viaShortcut) {
            Long cur = store.getCurrentId();
            if (cur == null || cur < 0 || store.findById(cur) == null) {
                if (store.all().isEmpty()) {
                    renderAccounts();
                } else {
                    switchTo(store.all().get(0).id, false);
                }
            } else {
                switchTo(cur, false);
            }
        }

        // 恢复上次的全屏状态；调试 extra 优先
        if (store.isFullscreen()) enterFullscreen();

        // 调试入口：am start --ez show_drawer true / --el start_account <id> / --ez fullscreen true
        if (savedInstanceState == null && launchIntent != null && !shortcutIntent)
            handleLaunchExtras(launchIntent);
        if (shortcutIntent) consumeShortcutIntent(launchIntent);
    }

    @Override
    protected void onNewIntent(Intent launchIntent) {
        super.onNewIntent(launchIntent);
        setIntent(launchIntent);
        if (store == null || drawerLayout == null) return;
        store.reload();
        boolean shortcutIntent = ShortcutsHelper.isShortcutIntent(launchIntent);
        if (shortcutIntent) {
            handleShortcutIntent(launchIntent);
            consumeShortcutIntent(launchIntent);
        } else if (launchIntent != null) {
            handleLaunchExtras(launchIntent);
        }
    }

    /** 消费一次性启动参数，避免退出全屏/切号后重建 Activity 又重放旧快捷方式。 */
    private void consumeShortcutIntent(Intent intent) {
        Intent consumed = new Intent(intent);
        consumed.removeExtra(ShortcutsHelper.EXTRA_ACCOUNT);
        consumed.removeExtra(ShortcutsHelper.EXTRA_TOKEN);
        consumed.setAction(Intent.ACTION_MAIN);
        setIntent(consumed);
    }

    private boolean handleShortcutIntent(Intent i) {
        if (!ShortcutsHelper.isShortcutIntent(i)) return false;
        Account a = ShortcutsHelper.resolve(store, i);
        if (a == null) {
            toast(getString(R.string.toast_shortcut_invalid));
            return false;
        }
        if (!switchTo(a.id, true)) return false;
        enterFullscreen();
        return true;
    }

    private void handleLaunchExtras(Intent launchIntent) {
        // M1: 调试/自动化参数仅 debug 构建响应；release 对 intent extra 不做任何动作，
        // 避免第三方应用通过显式 intent 操纵账号配置（如改写代理）。
        if (!LaunchCommands.enabled(BuildConfig.DEBUG)) return;
        LaunchCommands cmd = LaunchCommands.fromIntent(launchIntent);
        // 先切账号（switchTo 会关抽屉），再处理全屏/主题，最后开抽屉，保证终态符合预期
        if (cmd.startAccount >= 0 && store.findById(cmd.startAccount) != null) {
            switchTo(cmd.startAccount, true);
        }
        if (cmd.hasDarkTheme) {
            setDarkTheme(cmd.darkTheme, false);
        }
        if (cmd.hasFullscreen) {
            if (cmd.fullscreen) {
                enterFullscreen();
            } else {
                exitFullscreen();
            }
        }
        if (cmd.showDrawer) {
            drawerLayout.openDrawer(drawerContent);
        }
        if (cmd.hasOpenConfig) {
            long cfgId = cmd.openConfigId >= 0 ? cmd.openConfigId : store.getCurrentId();
            if (cfgId >= 0) {
                openConfig(cfgId, cmd.proxyPreset, cmd.proxyAutoTest, cmd.proxySave);
            }
        }
        if (launchIntent != null && launchIntent.getBooleanExtra("preview_update_dialog", false)) {
            AppUpdateHelper.ReleaseInfo sample = new AppUpdateHelper.ReleaseInfo(
                    "v1.4", "1.4", "Muse Launcher v1.4",
                    "• 新增：应用内一键检查更新与代理下载安装\n• 新增：可配置自定义下载目录与悬浮实时进度卡\n• 新增：长按图片保存、顶部渐变加载条与视频真全屏\n• 优化：后台延迟回收与 R8 精简（安装包缩至 1.4 MB）",
                    AppUpdateHelper.RELEASES_WEB_URL, "muse-launcher-v1.4.apk", 1450000L,
                    AppUpdateHelper.RELEASES_WEB_URL);
            availableUpdate = sample;
            refreshUpdateCardUi();
            mainHandler.postDelayed(() -> showUpdateDialog(sample), 300L);
        }
        if (launchIntent != null && launchIntent.getBooleanExtra("preview_context_menu", false)) {
            mainHandler.postDelayed(() -> {
                if (currentWebView != null) {
                    showWebContextMenu(currentWebView, store.getCurrentId(),
                            "https://cdn.muse.ai/assets/generated_poster_2026.png",
                            "https://muse.ai/space/demo");
                }
            }, 300L);
        }
        if (cmd.searchQuery != null) {
            searchInput.setText(cmd.searchQuery);
        }
        if (cmd.dumpAuth) {
            dumpAuthState();
        }
        if (cmd.addAccount) {
            addAccount();
        }
        if (cmd.hasDelAccount && cmd.delAccountId >= 0 && store.findById(cmd.delAccountId) != null) {
            deleteAccount(cmd.delAccountId);
        }
    }

    private void dumpAuthState() {
        for (Account a : store.all()) {
            Profile p = profileStore.getProfile(profileName(a.id));
            String cookies = null;
            try {
                cookies = p != null ? p.getCookieManager().getCookie("https://muse.ai/") : null;
            } catch (Exception ignored) {
            }
            Log.i("MuseAuth", "acct=" + a.id + " name=" + a.name
                    + " cookies=" + cookieNames(cookies));
            WebView wv = webViews.get(a.id);
            if (wv != null) {
                wv.evaluateJavascript(
                        "(function(){try{return 'LS:'+(Object.keys(localStorage).join(',')||'-')"
                                + "+'|CK:'+document.cookie}catch(e){return 'ERR:'+e}})()",
                        v -> Log.i("MuseAuth", "acct=" + a.id + " js=" + v));
            } else {
                Log.i("MuseAuth", "acct=" + a.id + " js=<no webview>");
            }
        }
    }

    private static String cookieNames(String header) {
        if (header == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (String c : header.split(";")) {
            c = c.trim();
            int eq = c.indexOf('=');
            sb.append(eq > 0 ? c.substring(0, eq) : c).append(' ');
        }
        return sb.append(']').toString();
    }

    // ---------- 日夜模式切换（侧栏 + Muse 网页同步） ----------

    private Configuration nightModeConfiguration() {
        Configuration cfg = new Configuration(getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (darkTheme ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return cfg;
    }

    /** 基于当前日夜模式缓存显式配置上下文，避免每次 renderAccounts 重复分配 Context/Resources */
    private Context themedContext() {
        if (cachedThemedContext == null) {
            cachedThemedContext = createConfigurationContext(nightModeConfiguration());
        }
        return cachedThemedContext;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cachedThemedContext = null;
    }

    private void setDarkTheme(boolean dark, boolean showToast) {
        if (darkTheme == dark) return;
        darkTheme = dark;
        cachedThemedContext = null;
        store.setDarkTheme(dark);
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        applyThemeToNativeUi();
        syncThemeToAllWebViews();
        if (showToast) {
            toast(getString(dark ? R.string.toast_theme_dark : R.string.toast_theme_light));
        }
    }

    private void applyThemeToNativeUi() {
        Context tc = themedContext();
        int bg = ContextCompat.getColor(tc, R.color.bg);
        int chatBg = ContextCompat.getColor(tc, R.color.chat_bg);
        int railBg = ContextCompat.getColor(tc, R.color.rail_bg);
        int drawerBg = ContextCompat.getColor(tc, R.color.drawer_bg);
        int splashBg = ContextCompat.getColor(tc, R.color.splash_bg);
        int textPrimary = ContextCompat.getColor(tc, R.color.text_primary);
        int textMuted = ContextCompat.getColor(tc, R.color.text_muted);
        int textDim = ContextCompat.getColor(tc, R.color.text_dim);
        int iconPrimary = ContextCompat.getColor(tc, R.color.icon_primary);
        int iconSearch = ContextCompat.getColor(tc, R.color.icon_search);
        int blue = ContextCompat.getColor(tc, R.color.blue);
        int fabText = ContextCompat.getColor(tc, R.color.fab_text);

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
            WindowInsetsControllerCompat insets =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            if (insets != null) {
                insets.setAppearanceLightStatusBars(!darkTheme);
                insets.setAppearanceLightNavigationBars(!darkTheme);
            }
        }

        if (rootFrame != null) rootFrame.setBackgroundColor(bg);
        if (drawerLayout != null) drawerLayout.setBackgroundColor(bg);
        if (rail != null) rail.setBackgroundColor(railBg);
        if (drawerContent != null) drawerContent.setBackgroundColor(drawerBg);
        if (mainContentArea != null) mainContentArea.setBackgroundColor(chatBg);
        if (webContainer != null) webContainer.setBackgroundColor(chatBg);
        if (emptyState != null) emptyState.setBackgroundColor(chatBg);
        if (errorOverlay != null) errorOverlay.setBackgroundColor(chatBg);
        if (splash != null) splash.setBackgroundColor(splashBg);

        int toggleIcon = darkTheme ? R.drawable.ic_sun : R.drawable.ic_moon;
        String toggleCd = getString(darkTheme ? R.string.cd_theme_to_light : R.string.cd_theme_to_dark);
        ColorStateList iconPrimaryTint = ColorStateList.valueOf(iconPrimary);
        ColorStateList iconSearchTint = ColorStateList.valueOf(iconSearch);
        ColorStateList blueTint = ColorStateList.valueOf(blue);

        if (btnRailExpand != null) {
            btnRailExpand.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_btn));
            btnRailExpand.setImageTintList(iconPrimaryTint);
        }
        if (btnThemeToggle != null) {
            btnThemeToggle.setImageResource(toggleIcon);
            btnThemeToggle.setContentDescription(toggleCd);
            btnThemeToggle.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_btn));
            btnThemeToggle.setImageTintList(iconPrimaryTint);
        }
        if (btnFullscreen != null) {
            btnFullscreen.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_btn));
            btnFullscreen.setImageTintList(iconPrimaryTint);
        }
        if (btnRailAdd != null) {
            btnRailAdd.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_add));
            btnRailAdd.setImageTintList(blueTint);
        }
        if (btnDrawerThemeToggle != null) {
            btnDrawerThemeToggle.setImageResource(toggleIcon);
            btnDrawerThemeToggle.setContentDescription(toggleCd);
            btnDrawerThemeToggle.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_btn));
            btnDrawerThemeToggle.setImageTintList(iconPrimaryTint);
        }
        if (btnCollapse != null) {
            btnCollapse.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rail_btn));
            btnCollapse.setImageTintList(iconPrimaryTint);
        }
        if (drawerTitle != null) drawerTitle.setTextColor(textPrimary);
        if (accCount != null) accCount.setTextColor(textMuted);
        if (searchBox != null) searchBox.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_search));
        if (searchIcon != null) searchIcon.setImageTintList(iconSearchTint);
        if (searchInput != null) {
            searchInput.setTextColor(textPrimary);
            searchInput.setHintTextColor(textDim);
        }
        if (drawerEmpty instanceof TextView) ((TextView) drawerEmpty).setTextColor(textMuted);
        if (btnAddAccount != null) {
            btnAddAccount.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_addcard));
        }
        if (addPlusIcon != null) addPlusIcon.setImageTintList(blueTint);
        if (addAccountTitle != null) addAccountTitle.setTextColor(textPrimary);
        if (addAccountSub != null) addAccountSub.setTextColor(textMuted);
        if (shieldIcon != null) shieldIcon.setImageTintList(iconSearchTint);
        if (drawerFootnote != null) drawerFootnote.setTextColor(textMuted);

        if (emptyTile != null) emptyTile.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_mtile));
        if (emptyTitle != null) emptyTitle.setTextColor(textPrimary);
        if (emptySub != null) emptySub.setTextColor(textMuted);
        if (splashTile != null) splashTile.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_mtile));
        if (splashName != null) splashName.setTextColor(textPrimary);
        if (splashSub != null) splashSub.setTextColor(textMuted);
        if (errorTile != null) errorTile.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_mtile));
        if (errorTitle != null) errorTitle.setTextColor(textPrimary);
        if (errorMsg != null) errorMsg.setTextColor(textMuted);
        if (errorHint != null) errorHint.setTextColor(textMuted);

        if (btnBackToMuse != null) {
            btnBackToMuse.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_fab_muse));
        }
        if (iconBackToMuse != null) iconBackToMuse.setImageTintList(blueTint);
        if (textBackToMuse != null) textBackToMuse.setTextColor(fabText);
        if (downloadBanner != null) {
            downloadBanner.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_download_banner));
        }
        if (textDownloadTitle != null) textDownloadTitle.setTextColor(fabText);
        if (textDownloadSaved != null) textDownloadSaved.setTextColor(textMuted);
        if (downloadProgressBar != null) {
            downloadProgressBar.setProgressDrawable(
                    ContextCompat.getDrawable(tc, R.drawable.bg_download_progress));
        }
        int btnGrayText = ContextCompat.getColor(tc, R.color.btn_gray_text);
        if (downloadBadge != null && downloadBadgeIcon != null) {
            boolean active = bannerDownloadToken != null;
            downloadBadge.setBackground(ContextCompat.getDrawable(tc,
                    active ? R.drawable.bg_download_badge_active : R.drawable.bg_download_badge_done));
            downloadBadgeIcon.setImageTintList(ColorStateList.valueOf(active ? blue : Color.WHITE));
        }
        if (btnOpenDownload != null) {
            boolean active = bannerDownloadToken != null;
            btnOpenDownload.setBackground(ContextCompat.getDrawable(tc,
                    active ? R.drawable.bg_pill_btn_muted : R.drawable.bg_pill_btn_primary));
            btnOpenDownload.setTextColor(active ? btnGrayText : Color.WHITE);
        }
        if (downloadDirCard != null) {
            downloadDirCard.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_card));
        }
        if (downloadDirTitle != null) downloadDirTitle.setTextColor(textPrimary);
        if (downloadDirPath != null) downloadDirPath.setTextColor(textMuted);
        if (btnPickDownloadDir != null) {
            btnPickDownloadDir.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_editbtn));
            btnPickDownloadDir.setTextColor(btnGrayText);
        }
        if (btnResetDownloadDir != null) {
            btnResetDownloadDir.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_editbtn));
            btnResetDownloadDir.setTextColor(btnGrayText);
        }
        refreshUpdateCardUi();

        renderAccounts();
    }

    private void syncThemeCookie(long accountId) {
        if (profileStore == null) return;
        try {
            Profile p = profileStore.getProfile(profileName(accountId));
            if (p == null) return;
            CookieManager cm = p.getCookieManager();
            String existing = cm.getCookie(START_URL);
            cm.setCookie(START_URL, ThemeCookieHelper.buildClearHostThemeCookieHeader());
            cm.setCookie(START_URL, ThemeCookieHelper.buildThemeCookieHeader(existing, darkTheme));
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    private void syncThemeToAllWebViews() {
        Configuration cfg = nightModeConfiguration();
        int chatBg = ContextCompat.getColor(themedContext(), R.color.chat_bg);
        String js = ThemeCookieHelper.buildDomThemeSyncScript(darkTheme);
        // 仅同步当前存活 WebView 的 Cookie 与 DOM（休眠账号打开时会自动同步主题 Cookie，免主线程全量刷盘）
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            syncThemeCookie(entry.getKey());
            WebView wv = entry.getValue();
            if (wv == null) continue;
            wv.setBackgroundColor(chatBg);
            try {
                wv.dispatchConfigurationChanged(cfg);
            } catch (Exception ignored) {
            }
            String url = wv.getUrl();
            if (url == null || MuseUrlHelper.isMusePage(url)) {
                wv.evaluateJavascript(js, null);
            }
        }
    }

    // ---------- 外部页面悬浮返回 Muse 按钮 ----------

    private void setupBackToMuseButton() {
        if (btnBackToMuse == null) return;
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        btnBackToMuse.setOnClickListener(v -> returnToMuse());
        btnBackToMuse.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fabDownRawX = event.getRawX();
                    fabDownRawY = event.getRawY();
                    fabStartTransX = v.getTranslationX();
                    fabStartTransY = v.getTranslationY();
                    fabDragging = false;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - fabDownRawX;
                    float dy = event.getRawY() - fabDownRawY;
                    if (!fabDragging && Math.hypot(dx, dy) > touchSlop) {
                        fabDragging = true;
                        v.setPressed(false);
                    }
                    if (fabDragging && mainContentArea != null) {
                        float nextX = fabStartTransX + dx;
                        float nextY = fabStartTransY + dy;
                        int margin = dp(8);
                        float minTransX = -(v.getLeft() - margin);
                        float maxTransX = (mainContentArea.getWidth() - v.getRight()) - margin;
                        float minTransY = -(v.getTop() - margin);
                        float maxTransY = (mainContentArea.getHeight() - v.getBottom()) - margin;
                        if (minTransX > maxTransX) {
                            float tmp = minTransX;
                            minTransX = maxTransX;
                            maxTransX = tmp;
                        }
                        if (minTransY > maxTransY) {
                            float tmp = minTransY;
                            minTransY = maxTransY;
                            maxTransY = tmp;
                        }
                        v.setTranslationX(Math.max(minTransX, Math.min(maxTransX, nextX)));
                        v.setTranslationY(Math.max(minTransY, Math.min(maxTransY, nextY)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (!fabDragging) {
                        v.performClick();
                    }
                    fabDragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    fabDragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void updateBackToMuseButton() {
        if (btnBackToMuse == null) return;
        String url = currentWebView != null ? currentWebView.getUrl() : null;
        boolean show = currentWebView != null
                && !store.all().isEmpty()
                && MuseUrlHelper.isExternalPage(url);
        btnBackToMuse.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void returnToMuse() {
        if (currentWebView == null) return;
        long curId = store.getCurrentId();
        if (curId >= 0) webErrored.put((int) curId, false);
        hideErrorOverlay();

        WebBackForwardList list = currentWebView.copyBackForwardList();
        int step = 0;
        if (list != null && list.getSize() > 0) {
            List<String> urls = new ArrayList<>(list.getSize());
            for (int i = 0; i < list.getSize(); i++) {
                WebHistoryItem item = list.getItemAtIndex(i);
                urls.add(item != null ? item.getUrl() : null);
            }
            step = MuseUrlHelper.findLastMuseHistoryStep(urls, list.getCurrentIndex());
        }
        if (step < 0 && currentWebView.canGoBackOrForward(step)) {
            currentWebView.goBackOrForward(step);
        } else {
            currentWebView.loadUrl(START_URL);
        }
        btnBackToMuse.setVisibility(View.GONE);
    }

    // ---------- 全屏模式 ----------

    private void enterFullscreen() {
        if (fullscreen) return;
        fullscreen = true;
        store.setFullscreen(true);
        rail.setVisibility(View.GONE);
        edgeSwipe.setVisibility(View.VISIBLE);
        // 锁定抽屉，避免左缘手势先被抽屉拦截
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.closeDrawer(drawerContent);
    }

    private void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        store.setFullscreen(false);
        rail.setVisibility(View.VISIBLE);
        edgeSwipe.setVisibility(View.GONE);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    // ---------- WebView（每账号独立实例） ----------

    private WebView getOrCreateWebView(final Account a) {
        WebView wv = webViews.get(a.id);
        if (wv != null) return wv;

        wv = new WebView(this);
        wv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wv.setBackgroundColor(ContextCompat.getColor(themedContext(), R.color.chat_bg));

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 支持 window.open / target="_blank"（空间与外链打开依赖）
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // 允许语音通话与空间媒体直接播放，不被自动播放策略拦截
        s.setMediaPlaybackRequiresUserGesture(false);
        // muse.ai 原生支持日夜主题，禁用算法强行反色避免画面发灰/失真
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }
        // 去掉 WebView 标识（; wv / Version/4.0），否则 muse.ai 会把页面重定向到 Play 商店
        String ua = s.getUserAgentString();
        if (ua != null) {
            String chromeUa = ua.replace("; wv", "");
            chromeUa = chromeUa.replaceAll("\\s*Version/[\\d.]+", "");
            s.setUserAgentString(chromeUa);
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (webErrored.get((int) a.id, false)) {
                    // 新一次加载开始：清错误标记，失败会由 onReceivedError 重新置位
                    webErrored.put((int) a.id, false);
                }
                if (a.id == store.getCurrentId()) {
                    updateBackToMuseButton();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (a.id == store.getCurrentId()) {
                    updateBackToMuseButton();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                webLoaded.put((int) a.id, true);
                Long st = loadStartAt.get((int) a.id);
                if (st != null) {
                    Log.i("MusePerf", "load end id=" + a.id + " cost="
                            + (SystemClock.uptimeMillis() - st) + "ms errored="
                            + webErrored.get((int) a.id, false));
                    loadStartAt.remove((int) a.id);
                }
                if (MuseUrlHelper.isMusePage(url)) {
                    view.evaluateJavascript(ThemeCookieHelper.buildDomThemeSyncScript(darkTheme), null);
                }
                view.evaluateJavascript(MediaDownloadHelper.buildRevokeObjectUrlGuardScript(), null);
                if (a.id != store.getCurrentId()) return;
                if (webErrored.get((int) a.id, false)) {
                    showErrorOverlay(null); // 错误页已渲染，保持遮罩等用户重试
                } else {
                    hideErrorOverlay();
                }
                updateBackToMuseButton();
                long elapsed = SystemClock.uptimeMillis() - splashShownAt;
                webContainer.postDelayed(hideSplashRunnable,
                        Math.max(0, MIN_SPLASH_MS - elapsed));
                if (MuseUrlHelper.isMusePage(url)) {
                    refreshLogged(false);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame()) return;
                webErrored.put((int) a.id, true);
                Log.i("MusePerf", "load error id=" + a.id
                        + " desc=" + error.getDescription());
                if (a.id == store.getCurrentId()) {
                    showErrorOverlay(error.getDescription() != null
                            ? error.getDescription().toString() : null);
                    updateBackToMuseButton();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // M2: 渲染进程崩溃时销毁并重建该账号的 WebView，返回 true 拦截，避免拖垮整个应用。
                // 账号的 Profile 数据不受影响，重建后仍是同一隔离环境（登录态保留）。
                Long crashedId = null;
                for (Map.Entry<Long, WebView> en : webViews.snapshot().entrySet()) {
                    if (en.getValue() == view) {
                        crashedId = en.getKey();
                        break;
                    }
                }
                webViews.remove(crashedId != null ? crashedId : -1L);
                if (crashedId != null) webLoaded.remove(crashedId.intValue());
                destroyWebView(view);

                boolean wasCurrent = crashedId != null
                        && crashedId.longValue() == store.getCurrentId();
                if (wasCurrent) {
                    currentWebView = null;
                    Account ca = store.findById(crashedId);
                    WebView nv = ca != null ? getOrCreateWebView(ca) : null;
                    if (nv != null) {
                        nv.setVisibility(View.VISIBLE);
                        currentWebView = nv;
                    }
                    updateBackToMuseButton();
                }
                if (crashedId != null) {
                    Log.i("MuseWebview", "renderer gone, id=" + crashedId + " rebuilt=" + wasCurrent);
                    toast(getString(R.string.toast_webview_recovered));
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                if (u == null) return false;
                // 放行 http/https 以及 about/blob/data/javascript（空间 iframe 与前端路由依赖）
                if (MuseUrlHelper.isWebViewInternalScheme(u.getScheme())) return false;
                if (!request.isForMainFrame()) return true;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                return handleShowFileChooser(callback, params);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                return handleCreateWindow(view, a.id, resultMsg);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingPermissionRequest == request) {
                        pendingPermissionRequest = null;
                        pendingPermissionResources = null;
                    }
                });
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (a.id == store.getCurrentId()) {
                    updatePageLoadProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterCustomFullscreenVideo(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitCustomFullscreenVideo();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (BuildConfig.DEBUG && consoleMessage != null) {
                    Log.d("MuseConsole", consoleMessage.messageLevel() + ": "
                            + consoleMessage.message() + " (" + consoleMessage.sourceId()
                            + ":" + consoleMessage.lineNumber() + ")");
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });

        wv.addJavascriptInterface(new MuseDownloadBridge(), "MuseDownload");
        final WebView downloadWebView = wv;
        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                handleWebDownload(downloadWebView, a.id, url, userAgent, contentDisposition, mimeType));
        wv.setOnLongClickListener(v -> handleWebLongClick(downloadWebView, a.id));

        // Profile 必须在任何页面加载前设置，且每个 WebView 只能设置一次
        try {
            WebViewCompat.setProfile(wv, profileName(a.id));
        } catch (Exception e) {
            wv.destroy();
            toast("创建隔离环境失败：" + e.getMessage());
            return null;
        }

        // 允许第三方 Cookie（muse.ai 空间 iframe 托管在 *.metaaiusercontent.com，默认禁用三方 Cookie 会导致空间打不开）
        try {
            Profile p = profileStore.getProfile(profileName(a.id));
            if (p != null) {
                CookieManager cm = p.getCookieManager();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(wv, true);
            }
        } catch (Exception ignored) {
        }
        syncThemeCookie(a.id);

        loadStartAt.put((int) a.id, SystemClock.uptimeMillis());
        Log.i("MusePerf", "load start id=" + a.id);
        wv.loadUrl(START_URL);
        webContainer.addView(wv);
        webViews.put(a.id, wv);
        return wv;
    }

    /**
     * 捕获 window.open / target="_blank"（空间与外链打开）：
     * 使用绑定同一账号 Profile 的临时桥接 WebView 获取目标 URL 后在当前 WebView 打开，
     * 既不丢隔离态，也不会让 window.open 静默失效。
     */
    private boolean handleCreateWindow(final WebView parentView, long accountId, Message resultMsg) {
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
            return false;
        }
        final WebView child = new WebView(this);
        WebSettings cs = child.getSettings();
        cs.setJavaScriptEnabled(true);
        try {
            WebViewCompat.setProfile(child, profileName(accountId));
        } catch (Exception e) {
            child.destroy();
            return false;
        }
        transientPopups.add(child);
        final boolean[] routed = {false};
        final Runnable cleanup = () -> destroyTransientPopup(child);
        mainHandler.postDelayed(cleanup, POPUP_BRIDGE_TIMEOUT_MS);

        final java.util.function.Consumer<Uri> routeTarget = uri -> {
            if (routed[0] || uri == null) return;
            String scheme = uri.getScheme();
            String urlStr = uri.toString();
            if ("about".equalsIgnoreCase(scheme) || urlStr.isEmpty()) return;
            routed[0] = true;
            mainHandler.removeCallbacks(cleanup);
            child.stopLoading();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                parentView.loadUrl(urlStr);
            } else if (!MuseUrlHelper.isWebViewInternalScheme(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
            }
            mainHandler.post(cleanup);
        };

        child.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                if (u != null) {
                    routeTarget.accept(u);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                    routeTarget.accept(Uri.parse(url));
                }
            }
        });

        ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
        resultMsg.sendToTarget();
        return true;
    }

    private void destroyTransientPopup(WebView popup) {
        if (popup == null) return;
        if (transientPopups.remove(popup)) {
            try {
                popup.stopLoading();
                popup.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 附件上传（修复 + 号上传失败） ----------

    private boolean handleShowFileChooser(ValueCallback<Uri[]> callback,
                                          WebChromeClient.FileChooserParams params) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        clearPendingCameraCapture();
        fileCallback = callback;

        Uri cameraUri = null;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraUri = createCameraOutputUri();
        }
        Intent intent = FileChooserHelper.buildPickerIntent(
                params, cameraUri, getString(R.string.chooser_pick_file));
        try {
            startActivityForResult(intent, REQ_FILE);
            return true;
        } catch (Exception primaryErr) {
            clearPendingCameraCapture();
            try {
                startActivityForResult(FileChooserHelper.buildFallbackDocumentIntent(params), REQ_FILE);
                return true;
            } catch (Exception fallbackErr) {
                fileCallback = null;
                toast(getString(R.string.toast_no_filechooser));
                return false;
            }
        }
    }

    private Uri createCameraOutputUri() {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = File.createTempFile("muse_upload_", ".jpg", dir);
            pendingCameraFile = file;
            pendingCameraUri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", file);
            return pendingCameraUri;
        } catch (IOException | IllegalArgumentException e) {
            clearPendingCameraCapture();
            return null;
        }
    }

    private void clearPendingCameraCapture() {
        if (pendingCameraFile != null && pendingCameraFile.exists() && pendingCameraFile.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            pendingCameraFile.delete();
        }
        pendingCameraFile = null;
        pendingCameraUri = null;
    }

    // ---------- 网页麦克风 / 摄像头权限（启动器有权限即可直接放行） ----------

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) return;
        String[] requested = request.getResources();
        if (requested == null || requested.length == 0) {
            request.deny();
            return;
        }
        List<String> supported = new ArrayList<>();
        for (String res : requested) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    || PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    || PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                supported.add(res);
            }
        }
        if (supported.isEmpty()) {
            request.deny();
            return;
        }
        final String[] grantCandidates = supported.toArray(new String[0]);
        String origin = request.getOrigin() != null ? request.getOrigin().toString() : null;

        if (MuseUrlHelper.isTrustedWebPermissionOrigin(origin)) {
            grantOrRequestAndroidPermissions(request, grantCandidates);
            return;
        }

        // 非 Muse 的第三方网页需要用户确认一次
        boolean wantsAudio = supported.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        boolean wantsVideo = supported.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        if (!wantsAudio && !wantsVideo) {
            request.grant(grantCandidates);
            return;
        }
        int msgRes = wantsAudio && wantsVideo ? R.string.perm_dialog_msg_both
                : wantsAudio ? R.string.perm_dialog_msg_mic : R.string.perm_dialog_msg_cam;
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_dialog_title)
                .setMessage(getString(msgRes, MuseUrlHelper.displayHost(origin)))
                .setPositiveButton(R.string.btn_allow,
                        (d, w) -> grantOrRequestAndroidPermissions(request, grantCandidates))
                .setNegativeButton(R.string.btn_deny, (d, w) -> request.deny())
                .setOnCancelListener(d -> request.deny())
                .show();
    }

    private void grantOrRequestAndroidPermissions(PermissionRequest request, String[] resources) {
        List<String> missingAndroidPerms = new ArrayList<>();
        for (String res : resources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                missingAndroidPerms.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                missingAndroidPerms.add(Manifest.permission.CAMERA);
            }
        }
        if (missingAndroidPerms.isEmpty()) {
            try {
                request.grant(resources);
            } catch (Exception ignored) {
            }
            return;
        }
        if (pendingPermissionRequest != null) {
            try {
                pendingPermissionRequest.deny();
            } catch (Exception ignored) {
            }
        }
        pendingPermissionRequest = request;
        pendingPermissionResources = resources;
        ActivityCompat.requestPermissions(this,
                missingAndroidPerms.toArray(new String[0]), REQ_WEB_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_WEB_PERMISSION && pendingPermissionRequest != null) {
            PermissionRequest req = pendingPermissionRequest;
            String[] candidates = pendingPermissionResources;
            pendingPermissionRequest = null;
            pendingPermissionResources = null;

            List<String> granted = new ArrayList<>();
            boolean wantedAudio = false;
            if (candidates != null) {
                for (String res : candidates) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        wantedAudio = true;
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            granted.add(res);
                        }
                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            granted.add(res);
                        }
                    } else {
                        granted.add(res);
                    }
                }
            }
            try {
                if (!granted.isEmpty()) {
                    req.grant(granted.toArray(new String[0]));
                } else {
                    req.deny();
                    toast(getString(wantedAudio ? R.string.toast_mic_denied : R.string.toast_perm_denied));
                }
            } catch (Exception ignored) {
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void showErrorOverlay(String msg) {
        if (msg != null && !msg.isEmpty()) {
            errorMsg.setText(msg);
        } else {
            errorMsg.setText(R.string.error_generic);
        }
        errorOverlay.setVisibility(View.VISIBLE);
    }

    private void hideErrorOverlay() {
        errorOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            boolean cameraReady = pendingCameraFile != null
                    && pendingCameraFile.exists()
                    && pendingCameraFile.length() > 0;
            Uri[] results = FileChooserHelper.extractUris(
                    resultCode, data, pendingCameraUri, cameraReady);
            if (!cameraReady) clearPendingCameraCapture();
            else {
                pendingCameraFile = null;
                pendingCameraUri = null;
            }
            ValueCallback<Uri[]> cb = fileCallback;
            fileCallback = null;
            cb.onReceiveValue(results);
            return;
        }
        if (requestCode == REQ_CONFIG && resultCode == RESULT_OK && store != null) {
            store.reload();
            Account cur = store.findById(store.getCurrentId());
            if (cur != null) ProxyHelper.apply(cur.proxy);
            // 头像图片可能已更新，失效该账号的内存缓存再重渲染
            long changedId = data != null ? data.getLongExtra("account_id", -1) : -1;
            if (changedId >= 0) AvatarStore.evict(changedId);
            renderAccounts();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onStart() {
        super.onStart();
        inBackground = false;
        cancelDelayedEviction();
        resumeWebViewTimersIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null || drawerLayout == null) return;
        inBackground = false;
        cancelDelayedEviction();
        resumeWebViewTimersIfNeeded();
        if (currentWebView != null) currentWebView.onResume();
        // 元数据刷新不能依赖 Cookie 状态发生变化。
        store.reload();
        checkStoreNotice();
        renderAccounts();
        refreshLogged(false);
        Account cur = store.findById(store.getCurrentId());
        ProxyHelper.apply(cur != null ? cur.proxy : "");
    }

    @Override
    protected void onPause() {
        if (currentWebView != null) currentWebView.onPause();
        // 只刷新存在存活 WebView 的账号 Cookie，避免遍历全部账号造成主线程无谓 IPC 与磁盘刷写
        try {
            if (profileStore != null) {
                for (Long aliveId : webViews.snapshot().keySet()) {
                    Profile p = profileStore.getProfile(profileName(aliveId));
                    if (p != null) p.getCookieManager().flush();
                }
            }
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        inBackground = true;
        if (!isChangingConfigurations()) {
            scheduleDelayedEviction();
        }
        // 退到后台且无正在进行的语音通话/文件下载时暂停 WebView 定时器，显著降低后台 CPU 与耗电
        if (currentWebView != null && !timersPaused
                && activeWebDownloads.isEmpty() && !hasActiveAudioRecording()) {
            try {
                currentWebView.pauseTimers();
                timersPaused = true;
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (ResourcePolicy.shouldTrimBitmapCache(level)) {
            AvatarStore.trimMemory();
            AvatarTile.trimMemory();
        }
        if (ResourcePolicy.shouldEvictInactiveWebViewsImmediately(level)) {
            cancelDelayedEviction();
            evictInactiveWebViews();
        } else if (ResourcePolicy.shouldScheduleDelayedEviction(level)) {
            scheduleDelayedEviction();
        }
    }

    private void scheduleDelayedEviction() {
        if (delayedEvictPending || isFinishing() || isDestroyed()) return;
        if (webViews == null || webViews.size() <= 1) return;
        delayedEvictPending = true;
        mainHandler.postDelayed(delayedEvictRunnable, ResourcePolicy.BACKGROUND_EVICT_DELAY_MS);
        Log.i("MuseWebview", "scheduled delayed eviction in "
                + (ResourcePolicy.BACKGROUND_EVICT_DELAY_MS / 1000L) + "s");
    }

    private void cancelDelayedEviction() {
        if (delayedEvictPending) {
            mainHandler.removeCallbacks(delayedEvictRunnable);
            delayedEvictPending = false;
        }
    }

    private void resumeWebViewTimersIfNeeded() {
        if (timersPaused && currentWebView != null) {
            try {
                currentWebView.resumeTimers();
            } catch (Exception ignored) {
            }
            timersPaused = false;
        }
    }

    private boolean hasActiveAudioRecording() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null && !am.getActiveRecordingConfigurations().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private int resolveWebViewCapacity() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return ResourcePolicy.recommendedWebViewCapacity(mi.totalMem, am.isLowRamDevice());
            }
        } catch (Exception ignored) {
        }
        return 2;
    }

    /** 回收全部隐藏态非当前 WebView（保留当前账号页与所有账号的 Profile 登录数据），立即释放渲染进程内存 */
    private void evictInactiveWebViews() {
        if (webViews == null) return;
        int evicted = 0;
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            WebView wv = entry.getValue();
            if (wv == null || wv == currentWebView) continue;
            long id = entry.getKey();
            WebView removed = webViews.remove(id);
            if (removed != null) {
                webLoaded.remove((int) id);
                destroyWebView(removed);
                evicted++;
            }
        }
        if (evicted > 0) {
            Log.i("MuseWebview", "trim evicted inactive count=" + evicted);
        }
    }

    private void destroyWebView(WebView view) {
        if (view == null) return;
        try {
            view.stopLoading();
            view.removeJavascriptInterface("MuseDownload");
            view.setWebChromeClient(null);
            view.setWebViewClient(new WebViewClient());
            view.setDownloadListener(null);
            view.setOnLongClickListener(null);
            if (webContainer != null) webContainer.removeView(view);
            view.loadUrl("about:blank");
            view.removeAllViews();
            view.destroy();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        exitCustomFullscreenVideo();
        if (activeContextDialog != null) {
            try {
                activeContextDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeContextDialog = null;
        }
        if (activeUpdateDialog != null) {
            try {
                activeUpdateDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeUpdateDialog = null;
        }
        if (activeUpdateCancelFlag != null) {
            activeUpdateCancelFlag.set(true);
            activeUpdateCancelFlag = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (webContainer != null) webContainer.removeCallbacks(hideSplashRunnable);
        probeQueue = null;
        for (WebView popup : new ArrayList<>(transientPopups)) {
            destroyTransientPopup(popup);
        }
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            WebView view = webViews.remove(entry.getKey());
            destroyWebView(view);
        }
        currentWebView = null;
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        clearPendingCameraCapture();
        if (pendingPermissionRequest != null) {
            try {
                pendingPermissionRequest.deny();
            } catch (Exception ignored) {
            }
            pendingPermissionRequest = null;
            pendingPermissionResources = null;
        }
        for (WebDownloadSession session : activeWebDownloads.values()) {
            if (session.pending != null) session.pending.abort();
        }
        activeWebDownloads.clear();
        downloadExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---------- 下载目录配置 + 媒体与文件下载 ----------

    private void openDownloadDirPicker() {
        if (pickDownloadDirLauncher == null) return;
        try {
            String current = store != null ? store.getDownloadTreeUri() : "";
            Uri initial = (current != null && !current.isEmpty()) ? Uri.parse(current) : null;
            pickDownloadDirLauncher.launch(initial);
        } catch (Exception e) {
            toast(getString(R.string.toast_download_dir_pick_failed));
        }
    }

    private void onDownloadDirPicked(Uri treeUri) {
        if (treeUri == null || store == null) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (Exception ignored) {
        }
        String old = store.getDownloadTreeUri();
        String next = treeUri.toString();
        if (old != null && !old.isEmpty() && !old.equals(next)) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(old), flags);
            } catch (Exception ignored) {
            }
        }
        store.setDownloadTreeUri(next);
        refreshDownloadDirUi();
        toast(getString(R.string.toast_download_dir_set,
                MediaDownloadHelper.formatTreeUriDisplay(next)));
    }

    private void resetDownloadDir() {
        if (store == null) return;
        String old = store.getDownloadTreeUri();
        if (old != null && !old.isEmpty()) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        Uri.parse(old),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        store.setDownloadTreeUri("");
        refreshDownloadDirUi();
        toast(getString(R.string.toast_download_dir_reset));
    }

    private void refreshDownloadDirUi() {
        if (downloadDirPath == null || store == null) return;
        String custom = store.getDownloadTreeUri();
        boolean hasCustom = custom != null && !custom.isEmpty();
        downloadDirPath.setText(hasCustom
                ? MediaDownloadHelper.formatTreeUriDisplay(custom)
                : getString(R.string.drawer_download_dir_default));
        if (btnResetDownloadDir != null) {
            btnResetDownloadDir.setVisibility(hasCustom ? View.VISIBLE : View.GONE);
        }
    }

    private static final class WebDownloadSession {
        final String token;
        final long accountId;
        final String url;
        final String userAgent;
        final String referer;
        final String contentDisposition;
        final String mimeType;
        final CookieManager cookieManager;
        final String proxy;
        final String customTreeUri;
        volatile String displayName;
        volatile boolean cancelled;
        volatile long downloadedBytes;
        volatile long totalBytes;
        volatile long lastProgressUiAt;
        MediaDownloadHelper.PendingMedia pending;

        WebDownloadSession(String token, long accountId, String url, String userAgent, String referer,
                           String contentDisposition, String mimeType,
                           CookieManager cookieManager, String proxy, String customTreeUri,
                           String displayName) {
            this.token = token;
            this.accountId = accountId;
            this.url = url;
            this.userAgent = userAgent;
            this.referer = referer;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.cookieManager = cookieManager;
            this.proxy = proxy;
            this.customTreeUri = customTreeUri;
            this.displayName = displayName;
        }
    }

    private void handleWebDownload(WebView view, long accountId, String url,
                                   String userAgent, String contentDisposition, String mimeType) {
        if (url == null || url.trim().isEmpty()) return;
        final String customTreeUri = store != null ? store.getDownloadTreeUri() : "";
        MediaDownloadHelper.Destination preview = MediaDownloadHelper.resolveDestination(
                url, contentDisposition, mimeType, null);

        // 1. data: URL 直接本地解码写入（优先用户配置目录，未配置走默认 MediaStore）
        if (url.regionMatches(true, 0, "data:", 0, 5)) {
            showDownloadProgressBanner(null, preview.fileName, 0L, 0L);
            downloadExecutor.execute(() -> {
                MediaDownloadHelper.PendingMedia pending = null;
                try {
                    MediaDownloadHelper.DataUrlPayload payload = MediaDownloadHelper.parseDataUrl(url);
                    if (payload == null || payload.bytes.length == 0) {
                        throw new IOException("Invalid data URL");
                    }
                    MediaDownloadHelper.Destination dest = MediaDownloadHelper.resolveDestination(
                            url, contentDisposition, payload.mimeType, null);
                    pending = MediaDownloadHelper.openPendingDownload(
                            getApplicationContext(), dest, customTreeUri);
                    pending.out.write(payload.bytes);
                    MediaDownloadHelper.SavedMedia saved = pending.commit();
                    runOnUiThread(() -> showDownloadSavedBanner(saved));
                } catch (Exception e) {
                    if (pending != null) pending.abort();
                    Log.w("MuseDownload", "data download failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        hideDownloadBannerAnimated();
                        toast(getString(R.string.toast_download_failed));
                    });
                }
            });
            return;
        }

        // 在主线程提取该账号的 Profile CookieManager 与代理（androidx ProfileStore 要求主线程访问）
        CookieManager accountCookies = null;
        String accountProxy = "";
        try {
            Profile p = profileStore != null ? profileStore.getProfile(profileName(accountId)) : null;
            if (p != null) accountCookies = p.getCookieManager();
            Account acct = store != null ? store.findById(accountId) : null;
            if (acct != null && acct.proxy != null) accountProxy = acct.proxy;
        } catch (Exception ignored) {
        }

        // 2. blob: 与 http(s): 优先在当前 WebView 内流式 fetch（复用账号独立 Profile Cookie 与代理）
        //    若 http(s): 遇跨域 CORS 失败，onFetchFailed 会自动无缝回退原生流式下载或公共 DownloadManager
        String token = UUID.randomUUID().toString();
        String referer = view != null ? view.getUrl() : START_URL;
        WebDownloadSession session = new WebDownloadSession(
                token, accountId, url, userAgent, referer, contentDisposition, mimeType,
                accountCookies, accountProxy, customTreeUri, preview.fileName);
        activeWebDownloads.put(token, session);
        showDownloadProgressBanner(token, preview.fileName, 0L, 0L);
        if (view != null) {
            view.evaluateJavascript(MediaDownloadHelper.buildWebFetchDownloadScript(token, url), null);
        } else {
            fallbackNativeDownload(session);
        }
    }

    private void notifyDownloadProgressThrottled(WebDownloadSession session, boolean force) {
        if (session == null || session.cancelled) return;
        long now = SystemClock.uptimeMillis();
        if (!force && (now - session.lastProgressUiAt) < 150L) return;
        session.lastProgressUiAt = now;
        final String token = session.token;
        final String name = session.displayName;
        final long done = session.downloadedBytes;
        final long total = session.totalBytes;
        runOnUiThread(() -> {
            if (!session.cancelled && token.equals(bannerDownloadToken)) {
                showDownloadProgressBanner(token, name, done, total);
            }
        });
    }

    private void cancelActiveDownload(String token) {
        if (token == null) return;
        if (UPDATE_DOWNLOAD_TOKEN.equals(token)) {
            if (activeUpdateCancelFlag != null) {
                activeUpdateCancelFlag.set(true);
                activeUpdateCancelFlag = null;
            }
            if (token.equals(bannerDownloadToken)) {
                bannerDownloadToken = null;
                bannerShowsApkReady = false;
                hideDownloadBannerAnimated();
            }
            toast(getString(R.string.toast_download_cancelled));
            return;
        }
        WebDownloadSession session = activeWebDownloads.remove(token);
        if (session != null) {
            session.cancelled = true;
            downloadExecutor.execute(() -> {
                if (session.pending != null) {
                    session.pending.abort();
                    session.pending = null;
                }
            });
        }
        if (token.equals(bannerDownloadToken)) {
            bannerDownloadToken = null;
            bannerShowsApkReady = false;
            hideDownloadBannerAnimated();
        }
        toast(getString(R.string.toast_download_cancelled));
    }

    private final class MuseDownloadBridge {
        @JavascriptInterface
        public boolean isCancelled(String token) {
            WebDownloadSession session = activeWebDownloads.get(token);
            return session == null || session.cancelled;
        }

        @JavascriptInterface
        public void onBegin(String token, String contentType, String contentDisposition, long length) {
            WebDownloadSession session = activeWebDownloads.get(token);
            if (session == null || session.cancelled) return;
            session.totalBytes = Math.max(0L, length);
            downloadExecutor.execute(() -> {
                if (session.cancelled || !activeWebDownloads.containsKey(token)) return;
                try {
                    String cd = (contentDisposition != null && !contentDisposition.isEmpty())
                            ? contentDisposition : session.contentDisposition;
                    String mime = (contentType != null && !contentType.isEmpty())
                            ? contentType : session.mimeType;
                    MediaDownloadHelper.Destination dest = MediaDownloadHelper.resolveDestination(
                            session.url, cd, mime, null);
                    session.displayName = dest.fileName;
                    session.pending = MediaDownloadHelper.openPendingDownload(
                            getApplicationContext(), dest, session.customTreeUri);
                    notifyDownloadProgressThrottled(session, true);
                } catch (Exception e) {
                    activeWebDownloads.remove(token);
                    Log.w("MuseDownload", "openPendingDownload failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (token.equals(bannerDownloadToken)) {
                            bannerDownloadToken = null;
                            hideDownloadBannerAnimated();
                        }
                        toast(getString(R.string.toast_download_failed));
                    });
                }
            });
        }

        @JavascriptInterface
        public void onChunk(String token, String base64Chunk) {
            WebDownloadSession session = activeWebDownloads.get(token);
            if (session == null || session.cancelled || base64Chunk == null) return;
            downloadExecutor.execute(() -> {
                if (session.cancelled || session.pending == null) return;
                try {
                    byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                    session.pending.out.write(bytes);
                    session.downloadedBytes += bytes.length;
                    notifyDownloadProgressThrottled(session, false);
                } catch (Exception e) {
                    session.pending.abort();
                    session.pending = null;
                    activeWebDownloads.remove(token);
                    Log.w("MuseDownload", "write chunk failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (token.equals(bannerDownloadToken)) {
                            bannerDownloadToken = null;
                            hideDownloadBannerAnimated();
                        }
                        toast(getString(R.string.toast_download_failed));
                    });
                }
            });
        }

        @JavascriptInterface
        public void onEnd(String token) {
            WebDownloadSession session = activeWebDownloads.remove(token);
            if (session == null || session.cancelled) return;
            downloadExecutor.execute(() -> {
                if (session.cancelled) {
                    if (session.pending != null) {
                        session.pending.abort();
                        session.pending = null;
                    }
                    return;
                }
                if (session.pending == null) return;
                try {
                    MediaDownloadHelper.SavedMedia saved = session.pending.commit();
                    session.pending = null;
                    runOnUiThread(() -> showDownloadSavedBanner(saved));
                } catch (Exception e) {
                    session.pending.abort();
                    session.pending = null;
                    Log.w("MuseDownload", "commit failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (token.equals(bannerDownloadToken)) {
                            bannerDownloadToken = null;
                            hideDownloadBannerAnimated();
                        }
                        toast(getString(R.string.toast_download_failed));
                    });
                }
            });
        }

        @JavascriptInterface
        public void onFetchFailed(String token, String reason) {
            WebDownloadSession session = activeWebDownloads.get(token);
            if (session == null || session.cancelled) {
                activeWebDownloads.remove(token);
                return;
            }
            downloadExecutor.execute(() -> {
                if (session.pending != null) {
                    session.pending.abort();
                    session.pending = null;
                }
                if (session.cancelled) {
                    activeWebDownloads.remove(token);
                    return;
                }
                if (session.url.regionMatches(true, 0, "http://", 0, 7)
                        || session.url.regionMatches(true, 0, "https://", 0, 8)) {
                    Log.i("MuseDownload", "web fetch failed (" + reason + "), fallback to native HTTP");
                    fallbackNativeDownload(session);
                } else {
                    activeWebDownloads.remove(token);
                    Log.w("MuseDownload", "blob fetch failed: " + reason);
                    runOnUiThread(() -> {
                        if (token.equals(bannerDownloadToken)) {
                            bannerDownloadToken = null;
                            hideDownloadBannerAnimated();
                        }
                        toast(getString(R.string.toast_download_failed));
                    });
                }
            });
        }
    }

    /** 兜底路径：先原生带代理+Cookie 流式写入 MediaStore，失败再回退系统 DownloadManager（公共 Download/Muse/） */
    private void fallbackNativeDownload(WebDownloadSession session) {
        downloadExecutor.execute(() -> {
            if (session.cancelled) {
                activeWebDownloads.remove(session.token);
                return;
            }
            session.downloadedBytes = 0L;
            session.totalBytes = 0L;
            try {
                MediaDownloadHelper.SavedMedia saved = MediaDownloadHelper.downloadHttpToMediaStore(
                        getApplicationContext(),
                        session.url,
                        session.userAgent,
                        session.referer,
                        session.contentDisposition,
                        session.mimeType,
                        session.cookieManager,
                        session.proxy,
                        session.customTreeUri,
                        new MediaDownloadHelper.DownloadProgressListener() {
                            @Override
                            public void onProgress(long downloadedBytes, long totalBytes) {
                                session.downloadedBytes = downloadedBytes;
                                session.totalBytes = Math.max(0L, totalBytes);
                                notifyDownloadProgressThrottled(session, downloadedBytes == 0L);
                            }

                            @Override
                            public boolean isCancelled() {
                                return session.cancelled;
                            }
                        });
                activeWebDownloads.remove(session.token);
                runOnUiThread(() -> showDownloadSavedBanner(saved));
                return;
            } catch (MediaDownloadHelper.DownloadCancelledException cancelled) {
                activeWebDownloads.remove(session.token);
                return;
            } catch (Exception nativeErr) {
                Log.w("MuseDownload", "native HTTP fallback failed: " + nativeErr.getMessage());
            }

            activeWebDownloads.remove(session.token);
            if (session.cancelled) return;
            // 最终兜底：系统 DownloadManager 明确落盘到公共 Download/Muse/（绝不进私有隐藏缓存）
            runOnUiThread(() -> {
                if (session.token.equals(bannerDownloadToken)) {
                    bannerDownloadToken = null;
                    hideDownloadBannerAnimated();
                }
                enqueuePublicDownloadManager(session, session.cookieManager);
            });
        });
    }

    private void enqueuePublicDownloadManager(WebDownloadSession session, CookieManager cm) {
        try {
            MediaDownloadHelper.Destination dest = MediaDownloadHelper.resolveDestination(
                    session.url, session.contentDisposition, session.mimeType, null);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(session.url));
            req.setTitle(dest.fileName);
            req.setDescription("Download/Muse/" + dest.fileName);
            req.setMimeType(dest.mimeType);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Muse/" + dest.fileName);
            if (session.userAgent != null && !session.userAgent.isEmpty()) {
                req.addRequestHeader("User-Agent", session.userAgent);
            }
            if (session.referer != null && !session.referer.isEmpty()) {
                req.addRequestHeader("Referer", session.referer);
            }
            if (cm != null) {
                String cookies = cm.getCookie(session.url);
                if (cookies != null && !cookies.isEmpty()) {
                    req.addRequestHeader("Cookie", cookies);
                }
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast(getString(R.string.toast_download_fallback_dm, dest.fileName));
        } catch (Exception e) {
            Log.w("MuseDownload", "DownloadManager fallback failed: " + e.getMessage());
            toast(getString(R.string.toast_download_failed));
        }
    }

    private void showDownloadProgressBanner(String token, String fileName,
                                            long downloadedBytes, long totalBytes) {
        if (isFinishing() || isDestroyed() || downloadBanner == null) return;
        mainHandler.removeCallbacks(hideDownloadBannerRunnable);
        bannerDownloadToken = token;
        bannerShowsApkReady = false;
        Context tc = themedContext();
        int blue = ContextCompat.getColor(tc, R.color.blue);
        int btnGrayText = ContextCompat.getColor(tc, R.color.btn_gray_text);

        if (downloadBadge != null && downloadBadgeIcon != null) {
            downloadBadge.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_download_badge_active));
            downloadBadgeIcon.setImageResource(R.drawable.ic_download);
            downloadBadgeIcon.setImageTintList(ColorStateList.valueOf(blue));
        }
        String title;
        if (totalBytes > 0) {
            title = getString(R.string.download_title_active_ratio,
                    MediaDownloadHelper.formatBytes(downloadedBytes),
                    MediaDownloadHelper.formatBytes(totalBytes));
        } else if (downloadedBytes > 0) {
            title = getString(R.string.download_title_active_bytes,
                    MediaDownloadHelper.formatBytes(downloadedBytes));
        } else {
            title = getString(R.string.download_title_active);
        }
        if (textDownloadTitle != null) textDownloadTitle.setText(title);
        if (textDownloadSaved != null) {
            textDownloadSaved.setText(fileName != null ? fileName : "");
        }
        if (downloadProgressBar != null) {
            downloadProgressBar.setVisibility(View.VISIBLE);
            int pct = MediaDownloadHelper.computeProgressPercent(downloadedBytes, totalBytes);
            if (pct >= 0) {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setProgress(pct, true);
            } else {
                downloadProgressBar.setIndeterminate(true);
            }
        }
        if (btnOpenDownload != null) {
            if (token != null) {
                btnOpenDownload.setVisibility(View.VISIBLE);
                btnOpenDownload.setText(R.string.btn_cancel);
                btnOpenDownload.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_pill_btn_muted));
                btnOpenDownload.setTextColor(btnGrayText);
            } else {
                btnOpenDownload.setVisibility(View.GONE);
            }
        }
        revealDownloadBannerAnimated();
    }

    private void showDownloadSavedBanner(MediaDownloadHelper.SavedMedia saved) {
        if (isFinishing() || isDestroyed() || saved == null) return;
        bannerDownloadToken = null;
        bannerShowsApkReady = false;
        lastSavedMedia = saved;
        Context tc = themedContext();
        String title;
        if (saved.customDirFallback) {
            title = getString(R.string.download_title_custom_fallback);
        } else if (!saved.usedCustomDir && (saved.destination.category == MediaDownloadHelper.Category.IMAGE
                || saved.destination.category == MediaDownloadHelper.Category.VIDEO)) {
            title = getString(R.string.download_title_saved_gallery);
        } else {
            title = getString(R.string.download_title_saved_file);
        }
        String subtitle = saved.destination.relativePath + "/" + saved.destination.fileName;

        if (downloadBanner != null && textDownloadSaved != null) {
            if (downloadBadge != null && downloadBadgeIcon != null) {
                downloadBadge.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_download_badge_done));
                downloadBadgeIcon.setImageResource(R.drawable.ic_check);
                downloadBadgeIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            }
            if (textDownloadTitle != null) textDownloadTitle.setText(title);
            textDownloadSaved.setText(subtitle);
            if (downloadProgressBar != null) {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setVisibility(View.GONE);
            }
            if (btnOpenDownload != null) {
                btnOpenDownload.setVisibility(View.VISIBLE);
                btnOpenDownload.setText(R.string.btn_open_download);
                btnOpenDownload.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_pill_btn_primary));
                btnOpenDownload.setTextColor(Color.WHITE);
            }
            revealDownloadBannerAnimated();
            mainHandler.removeCallbacks(hideDownloadBannerRunnable);
            mainHandler.postDelayed(hideDownloadBannerRunnable, 6000);
        } else {
            Toast.makeText(this, title + " · " + subtitle, Toast.LENGTH_LONG).show();
        }
    }

    private void revealDownloadBannerAnimated() {
        if (downloadBanner == null) return;
        downloadBanner.animate().cancel();
        if (downloadBanner.getVisibility() != View.VISIBLE) {
            downloadBanner.setAlpha(0f);
            downloadBanner.setTranslationY(-dp(10));
            downloadBanner.setVisibility(View.VISIBLE);
            downloadBanner.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start();
        } else {
            downloadBanner.setAlpha(1f);
            downloadBanner.setTranslationY(0f);
        }
    }

    private void hideDownloadBannerAnimated() {
        if (downloadBanner == null || downloadBanner.getVisibility() != View.VISIBLE) return;
        downloadBanner.animate().cancel();
        downloadBanner.animate()
                .alpha(0f)
                .translationY(-dp(10))
                .setDuration(160)
                .withEndAction(() -> {
                    if (downloadBanner != null) {
                        downloadBanner.setVisibility(View.GONE);
                        downloadBanner.setAlpha(1f);
                        downloadBanner.setTranslationY(0f);
                    }
                })
                .start();
    }

    private void openLastDownloadedMedia() {
        MediaDownloadHelper.SavedMedia saved = lastSavedMedia;
        if (saved == null) return;
        mainHandler.removeCallbacks(hideDownloadBannerRunnable);
        hideDownloadBannerAnimated();
        try {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(saved.uri, saved.destination.mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(viewIntent);
        } catch (Exception e) {
            toast(getString(R.string.toast_cannot_open_file, saved.destination.relativePath));
        }
    }

    // ---------- 顶部加载进度条 / 视频真全屏 / 长按图片与链接操作面板 ----------

    private void updatePageLoadProgress(int progress) {
        if (webLoadProgress == null) return;
        if (progress <= 0 || progress >= 100) {
            if (webLoadProgress.getVisibility() == View.VISIBLE) {
                webLoadProgress.setProgress(100, true);
                webLoadProgress.animate().cancel();
                webLoadProgress.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction(() -> {
                            if (webLoadProgress != null) {
                                webLoadProgress.setVisibility(View.GONE);
                                webLoadProgress.setProgress(0);
                                webLoadProgress.setAlpha(1f);
                            }
                        })
                        .start();
            }
            return;
        }
        webLoadProgress.animate().cancel();
        webLoadProgress.setAlpha(1f);
        if (webLoadProgress.getVisibility() != View.VISIBLE) {
            webLoadProgress.setVisibility(View.VISIBLE);
        }
        webLoadProgress.setProgress(Math.max(8, progress), true);
    }

    private void resetPageLoadProgress() {
        if (webLoadProgress == null) return;
        webLoadProgress.animate().cancel();
        webLoadProgress.setVisibility(View.GONE);
        webLoadProgress.setProgress(0);
        webLoadProgress.setAlpha(1f);
    }

    private void enterCustomFullscreenVideo(View view, WebChromeClient.CustomViewCallback callback) {
        if (view == null || fullscreenVideoContainer == null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        if (customFullscreenView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        customFullscreenView = view;
        customViewCallback = callback;
        fullscreenVideoContainer.removeAllViews();
        fullscreenVideoContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenVideoContainer.setVisibility(View.VISIBLE);
        fullscreenVideoContainer.setKeepScreenOn(true);
        setSystemBarsVisible(false);
    }

    private void exitCustomFullscreenVideo() {
        if (customFullscreenView == null) return;
        View view = customFullscreenView;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customFullscreenView = null;
        customViewCallback = null;
        if (fullscreenVideoContainer != null) {
            fullscreenVideoContainer.removeView(view);
            fullscreenVideoContainer.setVisibility(View.GONE);
            fullscreenVideoContainer.setKeepScreenOn(false);
        }
        setSystemBarsVisible(true);
        if (cb != null) {
            try {
                cb.onCustomViewHidden();
            } catch (Exception ignored) {
            }
        }
    }

    private void setSystemBarsVisible(boolean visible) {
        Window window = getWindow();
        if (window == null) return;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller == null) return;
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    private boolean handleWebLongClick(WebView view, long accountId) {
        if (view == null || isFinishing() || isDestroyed()) return false;
        WebView.HitTestResult hit = view.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        String extra = hit.getExtra();
        if (type == WebView.HitTestResult.IMAGE_TYPE) {
            if (extra == null || extra.trim().isEmpty()) return false;
            showWebContextMenu(view, accountId, extra.trim(), null);
            return true;
        }
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            if (extra == null || extra.trim().isEmpty()) return false;
            showWebContextMenu(view, accountId, null, extra.trim());
            return true;
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            final String fallbackImg = extra != null ? extra.trim() : null;
            Message msg = Message.obtain(new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(@NonNull Message m) {
                    Bundle data = m.getData();
                    String href = data != null ? data.getString("url") : null;
                    String src = data != null ? data.getString("src") : null;
                    String imgUrl = (src != null && !src.trim().isEmpty()) ? src.trim() : fallbackImg;
                    String linkUrl = (href != null && !href.trim().isEmpty()) ? href.trim() : null;
                    if (imgUrl != null || linkUrl != null) {
                        showWebContextMenu(view, accountId, imgUrl, linkUrl);
                    }
                }
            });
            view.requestFocusNodeHref(msg);
            return true;
        }
        return false;
    }

    private void showWebContextMenu(WebView view, long accountId, String imageUrl, String linkUrl) {
        if (isFinishing() || isDestroyed()) return;
        if (activeContextDialog != null) {
            try {
                activeContextDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeContextDialog = null;
        }
        Context tc = themedContext();
        View content = LayoutInflater.from(tc).inflate(R.layout.dialog_web_context_menu, null);
        TextView badge = content.findViewById(R.id.sheetBadge);
        TextView subtitle = content.findViewById(R.id.sheetSubtitle);
        LinearLayout actionsCard = content.findViewById(R.id.sheetActionsCard);
        TextView cancelBtn = content.findViewById(R.id.sheetCancel);

        boolean hasImage = imageUrl != null && !imageUrl.isEmpty();
        boolean hasLink = linkUrl != null && !linkUrl.isEmpty();
        badge.setText(hasImage ? R.string.sheet_badge_image : R.string.sheet_badge_link);
        String previewUrl = hasImage ? imageUrl : linkUrl;
        if (previewUrl != null && previewUrl.regionMatches(true, 0, "data:", 0, 5)) {
            subtitle.setText("data:image/...");
        } else {
            subtitle.setText(previewUrl != null ? previewUrl : "");
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        int blue = ContextCompat.getColor(tc, R.color.blue);
        int iconColor = ContextCompat.getColor(tc, R.color.icon_primary);
        int textColor = ContextCompat.getColor(tc, R.color.text_primary);
        int lineColor = ContextCompat.getColor(tc, R.color.line);

        List<View> rows = new ArrayList<>();
        if (hasImage) {
            rows.add(makeSheetActionRow(tc, R.drawable.ic_download, blue,
                    getString(R.string.sheet_action_save_image), textColor, true, v -> {
                        dialog.dismiss();
                        String ua = view.getSettings() != null ? view.getSettings().getUserAgentString() : null;
                        handleWebDownload(view, accountId, imageUrl, ua, null, "image/*");
                    }));
            rows.add(makeSheetActionRow(tc, R.drawable.ic_copy, iconColor,
                    getString(R.string.sheet_action_copy_image_url), textColor, false, v -> {
                        dialog.dismiss();
                        copyTextToClipboard(imageUrl);
                    }));
        }
        if (hasLink) {
            rows.add(makeSheetActionRow(tc, R.drawable.ic_copy, iconColor,
                    getString(R.string.sheet_action_copy_link), textColor, false, v -> {
                        dialog.dismiss();
                        copyTextToClipboard(linkUrl);
                    }));
            if (linkUrl.regionMatches(true, 0, "http://", 0, 7)
                    || linkUrl.regionMatches(true, 0, "https://", 0, 8)) {
                rows.add(makeSheetActionRow(tc, R.drawable.ic_open_external, iconColor,
                        getString(R.string.sheet_action_open_external), textColor, false, v -> {
                            dialog.dismiss();
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)));
                            } catch (Exception ignored) {
                            }
                        }));
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                View divider = new View(tc);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                dlp.leftMargin = dp(48);
                dlp.rightMargin = dp(14);
                divider.setLayoutParams(dlp);
                divider.setBackgroundColor(lineColor);
                actionsCard.addView(divider);
            }
            actionsCard.addView(rows.get(i));
        }

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (activeContextDialog == dialog) activeContextDialog = null;
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        activeContextDialog = dialog;
        dialog.show();
    }

    private View makeSheetActionRow(Context tc, int iconRes, int iconTint, String label,
                                    int textColor, boolean bold, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(tc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_row));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(onClick);

        ImageView iv = new ImageView(tc);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        iv.setLayoutParams(ilp);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(iconTint));
        row.addView(iv);

        TextView tv = new TextView(tc);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        tv.setLayoutParams(tlp);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(14.5f);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tv);
        return row;
    }

    private void copyTextToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Muse", text));
                toast(getString(R.string.toast_copied));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- 应用内在线检查更新与一键安装（方案 A） ----------

    private void refreshUpdateCardUi() {
        if (updateCard == null) return;
        Context tc = themedContext();
        int textPrimary = ContextCompat.getColor(tc, R.color.text_primary);
        int textMuted = ContextCompat.getColor(tc, R.color.text_muted);
        int blue = ContextCompat.getColor(tc, R.color.blue);
        int btnGrayText = ContextCompat.getColor(tc, R.color.btn_gray_text);

        updateCard.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_card));
        if (updateCardTitle != null) updateCardTitle.setTextColor(textPrimary);
        if (updateBadgeDot != null) {
            updateBadgeDot.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_update_dot));
            updateBadgeDot.setVisibility(availableUpdate != null ? View.VISIBLE : View.GONE);
        }
        if (updateVersionSub != null) {
            if (availableUpdate != null) {
                updateVersionSub.setText(getString(R.string.drawer_update_available, availableUpdate.versionName));
                updateVersionSub.setTextColor(blue);
            } else {
                updateVersionSub.setText(getString(R.string.drawer_update_current, BuildConfig.VERSION_NAME));
                updateVersionSub.setTextColor(textMuted);
            }
        }
        if (btnCheckUpdate != null) {
            if (checkingUpdate) {
                btnCheckUpdate.setText(R.string.drawer_update_checking);
                btnCheckUpdate.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_editbtn));
                btnCheckUpdate.setTextColor(textMuted);
                btnCheckUpdate.setEnabled(false);
            } else if (availableUpdate != null) {
                btnCheckUpdate.setText(R.string.drawer_update_go);
                btnCheckUpdate.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_editbtn_ok));
                btnCheckUpdate.setTextColor(Color.WHITE);
                btnCheckUpdate.setEnabled(true);
            } else {
                btnCheckUpdate.setText(R.string.drawer_update_check);
                btnCheckUpdate.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_editbtn));
                btnCheckUpdate.setTextColor(btnGrayText);
                btnCheckUpdate.setEnabled(true);
            }
        }
    }

    private String currentAccountProxy() {
        if (store == null) return "";
        Account cur = store.findById(store.getCurrentId());
        return (cur != null && cur.proxy != null) ? cur.proxy.trim() : "";
    }

    private void checkForUpdates(boolean userInitiated) {
        if (checkingUpdate || isFinishing() || isDestroyed()) return;
        checkingUpdate = true;
        refreshUpdateCardUi();
        final String proxy = currentAccountProxy();
        downloadExecutor.execute(() -> {
            try {
                AppUpdateHelper.ReleaseInfo info = AppUpdateHelper.fetchLatestRelease(proxy);
                if (store != null) store.setLastUpdateCheckAt(System.currentTimeMillis());
                boolean newer = AppUpdateHelper.isNewerVersion(info.versionName, BuildConfig.VERSION_NAME);
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (newer) {
                        availableUpdate = info;
                        refreshUpdateCardUi();
                        String skipped = store != null ? store.getSkippedUpdateTag() : "";
                        if (userInitiated || !info.tagName.equalsIgnoreCase(skipped)) {
                            showUpdateDialog(info);
                        }
                    } else {
                        availableUpdate = null;
                        refreshUpdateCardUi();
                        if (userInitiated) {
                            toast(getString(R.string.toast_update_latest, BuildConfig.VERSION_NAME));
                        }
                    }
                });
            } catch (Exception e) {
                Log.w("MuseUpdate", "check update failed: " + e.getMessage());
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    refreshUpdateCardUi();
                    if (userInitiated && !isFinishing() && !isDestroyed()) {
                        toast(getString(R.string.toast_update_check_failed));
                    }
                });
            }
        });
    }

    private void showUpdateDialog(AppUpdateHelper.ReleaseInfo info) {
        if (info == null || isFinishing() || isDestroyed()) return;
        if (activeUpdateDialog != null) {
            try {
                activeUpdateDialog.dismiss();
            } catch (Exception ignored) {
            }
            activeUpdateDialog = null;
        }
        Context tc = themedContext();
        View content = LayoutInflater.from(tc).inflate(R.layout.dialog_app_update, null);
        TextView verBadge = content.findViewById(R.id.updateDialogVersion);
        TextView notesView = content.findViewById(R.id.updateDialogNotes);
        View notesScroll = content.findViewById(R.id.updateDialogNotesScroll);
        TextView btnNow = content.findViewById(R.id.btnUpdateNow);
        TextView btnLater = content.findViewById(R.id.btnUpdateLater);
        TextView btnSkip = content.findViewById(R.id.btnUpdateSkip);
        TextView btnBrowser = content.findViewById(R.id.btnUpdateBrowser);

        if (info.apkSizeBytes > 0) {
            verBadge.setText(getString(R.string.update_dialog_version_size_fmt,
                    BuildConfig.VERSION_NAME, info.versionName,
                    MediaDownloadHelper.formatBytes(info.apkSizeBytes)));
        } else {
            verBadge.setText(getString(R.string.update_dialog_version_fmt,
                    BuildConfig.VERSION_NAME, info.versionName));
        }
        String notes = (info.notes != null && !info.notes.isEmpty())
                ? info.notes : getString(R.string.update_notes_default);
        notesView.setText(notes);
        // 限制更新日志卡片最大高度，确保小屏不溢出
        notesView.post(() -> {
            int maxH = dp(210);
            if (notesScroll.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = notesScroll.getLayoutParams();
                lp.height = maxH;
                notesScroll.setLayoutParams(lp);
            }
        });

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        btnNow.setOnClickListener(v -> {
            dialog.dismiss();
            if (drawerLayout != null && drawerLayout.isDrawerOpen(drawerContent)) {
                drawerLayout.closeDrawer(drawerContent);
            }
            startApkUpdateDownload(info);
        });
        btnLater.setOnClickListener(v -> dialog.dismiss());
        btnSkip.setOnClickListener(v -> {
            if (store != null) store.setSkippedUpdateTag(info.tagName);
            dialog.dismiss();
        });
        btnBrowser.setOnClickListener(v -> {
            dialog.dismiss();
            openExternalUrl(info.htmlUrl);
        });
        dialog.setOnDismissListener(d -> {
            if (activeUpdateDialog == dialog) activeUpdateDialog = null;
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            int screenW = getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(dp(336), Math.max(dp(260), screenW - dp(40)));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.48f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        activeUpdateDialog = dialog;
        dialog.show();
    }

    private void startApkUpdateDownload(AppUpdateHelper.ReleaseInfo info) {
        if (info == null) return;
        if (info.apkUrl == null || info.apkUrl.isEmpty()) {
            toast(getString(R.string.toast_update_no_apk));
            openExternalUrl(info.htmlUrl);
            return;
        }
        if (activeUpdateCancelFlag != null) {
            activeUpdateCancelFlag.set(true);
        }
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeUpdateCancelFlag = cancelFlag;
        final String fileName = (info.apkName != null && !info.apkName.isEmpty())
                ? info.apkName : ("muse-launcher-" + info.tagName + ".apk");
        final File destFile = new File(new File(getCacheDir(), "updates"), fileName);
        final String proxy = currentAccountProxy();
        final long[] lastUiAt = {0L};

        showDownloadProgressBanner(UPDATE_DOWNLOAD_TOKEN, fileName, 0L, info.apkSizeBytes);
        downloadExecutor.execute(() -> {
            try {
                File apk = AppUpdateHelper.downloadApkToFile(
                        info.apkUrl, destFile, proxy,
                        new MediaDownloadHelper.DownloadProgressListener() {
                            @Override
                            public void onProgress(long downloadedBytes, long totalBytes) {
                                if (cancelFlag.get()) return;
                                long now = SystemClock.uptimeMillis();
                                if (downloadedBytes > 0 && (now - lastUiAt[0]) < 150L) return;
                                lastUiAt[0] = now;
                                long displayTotal = totalBytes > 0 ? totalBytes : info.apkSizeBytes;
                                runOnUiThread(() -> {
                                    if (!cancelFlag.get() && UPDATE_DOWNLOAD_TOKEN.equals(bannerDownloadToken)) {
                                        showDownloadProgressBanner(
                                                UPDATE_DOWNLOAD_TOKEN, fileName, downloadedBytes, displayTotal);
                                    }
                                });
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelFlag.get();
                            }
                        });
                runOnUiThread(() -> {
                    if (activeUpdateCancelFlag == cancelFlag) activeUpdateCancelFlag = null;
                    if (cancelFlag.get() || isFinishing() || isDestroyed()) return;
                    pendingInstallApk = apk;
                    showUpdateReadyBanner(apk);
                    requestInstallApk(apk);
                });
            } catch (MediaDownloadHelper.DownloadCancelledException ignored) {
                runOnUiThread(() -> {
                    if (activeUpdateCancelFlag == cancelFlag) activeUpdateCancelFlag = null;
                });
            } catch (Exception e) {
                Log.w("MuseUpdate", "APK download failed: " + e.getMessage());
                runOnUiThread(() -> {
                    if (activeUpdateCancelFlag == cancelFlag) activeUpdateCancelFlag = null;
                    if (UPDATE_DOWNLOAD_TOKEN.equals(bannerDownloadToken)) {
                        bannerDownloadToken = null;
                        bannerShowsApkReady = false;
                        hideDownloadBannerAnimated();
                    }
                    if (!isFinishing() && !isDestroyed()) {
                        toast(getString(R.string.toast_update_download_failed));
                    }
                });
            }
        });
    }

    private void showUpdateReadyBanner(File apkFile) {
        if (isFinishing() || isDestroyed() || downloadBanner == null || apkFile == null) return;
        bannerDownloadToken = null;
        bannerShowsApkReady = true;
        Context tc = themedContext();
        if (downloadBadge != null && downloadBadgeIcon != null) {
            downloadBadge.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_download_badge_done));
            downloadBadgeIcon.setImageResource(R.drawable.ic_check);
            downloadBadgeIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        }
        if (textDownloadTitle != null) textDownloadTitle.setText(R.string.update_banner_ready);
        if (textDownloadSaved != null) textDownloadSaved.setText(apkFile.getName());
        if (downloadProgressBar != null) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setVisibility(View.GONE);
        }
        if (btnOpenDownload != null) {
            btnOpenDownload.setVisibility(View.VISIBLE);
            btnOpenDownload.setText(R.string.update_btn_install);
            btnOpenDownload.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_pill_btn_primary));
            btnOpenDownload.setTextColor(Color.WHITE);
        }
        revealDownloadBannerAnimated();
        mainHandler.removeCallbacks(hideDownloadBannerRunnable);
        mainHandler.postDelayed(hideDownloadBannerRunnable, 8000);
    }

    private void requestInstallApk(File apkFile) {
        if (apkFile == null || !apkFile.exists() || isFinishing() || isDestroyed()) return;
        pendingInstallApk = apkFile;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast(getString(R.string.toast_update_install_permission));
            try {
                Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                installPermissionLauncher.launch(permIntent);
            } catch (Exception e) {
                toast(getString(R.string.toast_update_install_failed));
            }
            return;
        }
        launchApkInstaller(apkFile);
    }

    private void launchApkInstaller(File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, BuildConfig.APPLICATION_ID + ".fileprovider", apkFile);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            Log.w("MuseUpdate", "launch installer failed: " + e.getMessage());
            toast(getString(R.string.toast_update_install_failed));
        }
    }

    private void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (customFullscreenView != null) {
            exitCustomFullscreenVideo();
        } else if (drawerLayout == null) {
            finish();
        } else if (fullscreen) {
            exitFullscreen();
        } else if (drawerLayout.isDrawerOpen(drawerContent)) {
            drawerLayout.closeDrawer(drawerContent);
        } else if (currentWebView != null && currentWebView.canGoBack()) {
            currentWebView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    // ---------- 账号操作 ----------

    private void openConfig(long id) {
        openConfig(id, null, false, false);
    }

    private void openConfig(long id, String proxyPreset, boolean autoTest, boolean autoSave) {
        Intent i = new Intent(this, AccountConfigActivity.class);
        i.putExtra("account_id", id);
        if (proxyPreset != null) {
            i.putExtra("proxy_preset", proxyPreset);
            i.putExtra("proxy_autotest", autoTest);
            i.putExtra("proxy_save", autoSave);
        }
        startActivityForResult(i, REQ_CONFIG);
    }

    private void addAccount() {
        // 新环境继承当前账号的代理：国内直连 muse.ai 不通，空代理的新环境必然卡在加载（问题 2）
        Account prev = store.findById(store.getCurrentId());
        Account a = store.addDefault(prev != null ? prev.proxy : null);
        editId = -1;
        confirmDelId = -1;
        switchTo(a.id, true);
        toast(getString(R.string.toast_created, a.name));
    }

    private boolean switchTo(long id, boolean animate) {
        Account a = store.findById(id);
        if (a == null) return false;
        Account previous = store.findById(store.getCurrentId());
        ProxyHelper.apply(a.proxy);
        WebView target = getOrCreateWebView(a);
        if (target == null) {
            ProxyHelper.apply(previous != null ? previous.proxy : "");
            return false;
        }
        // 目标准备成功后才发布选中账号，避免失败时画面/代理/账号互相错位。
        store.setCurrent(id);
        renderAccounts();
        if (currentWebView != null && currentWebView != target) currentWebView.onPause();

        target.setVisibility(View.VISIBLE);
        target.onResume();
        currentWebView = target;
        for (Map.Entry<Long, WebView> en : webViews.snapshot().entrySet()) {
            if (en.getValue() != target) en.getValue().setVisibility(View.GONE);
        }
        // M4: LRU 驱逐超出上限的旧账号页面（Profile 数据保留，仅页面状态丢弃）
        for (long evictedId : webViews.touchAndEvict(a.id, target)) {
            WebView old = webViews.remove(evictedId);
            if (old != null) {
                webLoaded.remove((int) evictedId);
                destroyWebView(old);
                Log.i("MuseWebview", "lru evict id=" + evictedId);
            }
        }

        // 目标页面若处于错误态：自动重新加载，避免停留在无法恢复的错误页（问题 1）
        if (webErrored.get((int) a.id, false)) {
            webErrored.put((int) a.id, false);
            loadStartAt.put((int) a.id, SystemClock.uptimeMillis());
            Log.i("MusePerf", "load reload-on-switch id=" + a.id);
            target.reload();
        }
        hideErrorOverlay();
        resetPageLoadProgress();
        updateBackToMuseButton();

        if (animate) showSplashFor(a);
        drawerLayout.closeDrawer(drawerContent);
        return true;
    }

    private void commitRename(long id) {
        Account a = store.findById(id);
        editId = -1;
        if (a == null) {
            renderAccounts();
            return;
        }
        String name = renameInput != null ? renameInput.getText().toString().trim() : "";
        if (name.length() > 16) name = name.substring(0, 16);
        if (name.isEmpty() || name.equals(a.name)) {
            renderAccounts();
            return;
        }
        if (!store.rename(id, name)) {
            renderAccounts();
            toast(getString(R.string.toast_config_save_failed));
            return;
        }
        // 已固定的桌面快捷方式同步新名称（图标不变，仅刷新 label）
        ShortcutsHelper.refresh(this, a, null);
        renderAccounts();
        toast(getString(R.string.toast_renamed, name));
    }

    private void deleteAccount(long id) {
        Account a = store.findById(id);
        if (a == null) return;
        if (!store.remove(id)) {
            toast(getString(R.string.toast_config_save_failed));
            return;
        }
        try {
            Profile p = profileStore.getProfile(profileName(id));
            if (p != null) {
                p.getCookieManager().removeAllCookies(null);
                p.getCookieManager().flush();
                p.getWebStorage().deleteAllData();
            }
            profileStore.deleteProfile(profileName(id));
        } catch (Exception ignored) {
        }
        // 销毁该账号的 WebView 实例
        WebView wv = webViews.remove(id);
        if (wv != null) {
            if (wv == currentWebView) currentWebView = null;
            webLoaded.remove((int) id);
            destroyWebView(wv);
        }
        // 同步清理：头像图片文件 + 桌面上残留的快捷方式（置灰失效）
        AvatarStore.clear(this, id);
        ShortcutsHelper.disable(this, id);
        editId = -1;
        confirmDelId = -1;
        renderAccounts();
        toast(getString(R.string.toast_deleted));
        long cur = store.getCurrentId();
        if (cur >= 0) switchTo(cur, true);
        else updateBackToMuseButton();
    }

    private void refreshLogged(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && lastLoggedProbeAt > 0
                && (now - lastLoggedProbeAt) < ResourcePolicy.LOGGED_PROBE_THROTTLE_MS) {
            return;
        }
        if (!probeRunning.compareAndSet(false, true)) {
            probeDirty.set(true); // 已有一轮在跑，结束后补一轮
            return;
        }
        lastLoggedProbeAt = now;
        probeQueue = new ArrayList<>(store.all());
        probeIndex = 0;
        probeChanged = false;
        probeStep();
    }

    private void probeStep() {
        if (probeQueue == null || probeIndex >= probeQueue.size()) {
            probeQueue = null;
            probeRunning.set(false);
            if (probeDirty.compareAndSet(true, false)) refreshLogged(true);
            return;
        }
        int end = Math.min(probeIndex + PROBE_CHUNK, probeQueue.size());
        for (int i = probeIndex; i < end; i++) {
            Account a = probeQueue.get(i);
            try {
                Profile p = profileStore.getProfile(profileName(a.id));
                // 不能用 hasCookies()：匿名访问也会落下 Cloudflare/Meta 埋点 cookie，一律误判已登录。
                // 只认 muse.ai 的登录会话 cookie hatch_sess。
                String cookies = p != null
                        ? p.getCookieManager().getCookie("https://muse.ai/") : null;
                boolean logged = cookies != null && cookies.contains(AUTH_COOKIE_MARKER);
                if (logged != a.logged) {
                    a.logged = logged;
                    probeChanged = true;
                }
            } catch (Exception ignored) {
            }
        }
        probeIndex = end;
        if (probeChanged && editId == -1 && confirmDelId == -1) renderAccounts();
        mainHandler.postDelayed(probeStep, 16); // 让出一帧再继续下一块
    }

    private String profileName(long id) {
        return "acct_" + id;
    }

    // ---------- 渲染 ----------

    private void renderAccounts() {
        if (railAvatars == null || accountList == null) return;
        Context tc = themedContext();
        railAvatars.removeAllViews();
        accountList.removeAllViews();
        String q = query();
        int shown = 0;
        for (Account a : store.all()) {
            if (!matches(a, q)) continue;
            shown++;
            railAvatars.addView(makeRailAvatar(tc, a));
            accountList.addView(makeDrawerRow(tc, a));
        }
        accCount.setText(getString(R.string.accounts_count_fmt, store.all().size()));
        emptyState.setVisibility(store.all().isEmpty() ? View.VISIBLE : View.GONE);
        drawerEmpty.setVisibility(!q.isEmpty() && shown == 0 ? View.VISIBLE : View.GONE);
        updateBackToMuseButton();
    }

    private String query() {
        return searchInput != null ? searchInput.getText().toString().trim() : "";
    }

    /** 按 名称 + 标签 过滤（忽略大小写，空 query 全匹配） */
    private boolean matches(Account a, String q) {
        if (q.isEmpty()) return true;
        String key = q.toLowerCase();
        if (a.name.toLowerCase().contains(key)) return true;
        for (String t : a.tags) {
            if (t.toLowerCase().contains(key)) return true;
        }
        return false;
    }

    private void showSplashFor(Account a) {
        splashName.setText(a.name);
        splash.setVisibility(View.VISIBLE);
        splashShownAt = SystemClock.uptimeMillis();
        webContainer.removeCallbacks(hideSplashRunnable);
        // 已加载过的账号不会再次触发 onPageFinished，直接短延时收起
        Boolean loaded = webLoaded.get((int) a.id);
        if (loaded != null && loaded) {
            webContainer.postDelayed(hideSplashRunnable, 450);
        }
        // 兜底：加载异常时也不会卡在过渡层
        webContainer.postDelayed(hideSplashRunnable, SPLASH_TIMEOUT_MS);
    }

    private void hideSplash() {
        webContainer.removeCallbacks(hideSplashRunnable);
        splash.setVisibility(View.GONE);
    }

    private View makeRailAvatar(Context tc, Account a) {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        wrap.setLayoutParams(lp);
        wrap.setBackground(ContextCompat.getDrawable(tc, a.id == store.getCurrentId()
                ? R.drawable.bg_rail_active : R.drawable.bg_rail_btn));

        View tile = makeTile(tc, a, dp(38), 20f);
        wrap.addView(tile, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));

        View dot = new View(this);
        dot.setBackground(ContextCompat.getDrawable(tc,
                a.logged ? R.drawable.bg_dot_on : R.drawable.bg_dot_off));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(10), dp(10),
                Gravity.BOTTOM | Gravity.END);
        dlp.rightMargin = dp(1);
        dlp.bottomMargin = dp(1);
        wrap.addView(dot, dlp);

        wrap.setOnClickListener(v -> switchTo(a.id, true));
        return wrap;
    }

    private View makeDrawerRow(Context tc, Account a) {
        int colorText = ContextCompat.getColor(tc, R.color.text_primary);
        int colorMuted = ContextCompat.getColor(tc, R.color.text_muted);
        int colorDim = ContextCompat.getColor(tc, R.color.text_dim);
        int colorIcon = ContextCompat.getColor(tc, R.color.icon_gray);
        int colorGreen = ContextCompat.getColor(tc, R.color.green);
        int colorDotOff = ContextCompat.getColor(tc, R.color.dot_off);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        // 删除确认态
        if (confirmDelId == a.id) {
            row.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_row));
            TextView msg = new TextView(this);
            msg.setText(getString(R.string.confirm_delete_fmt, a.name));
            msg.setTextColor(colorText);
            msg.setTextSize(12.5f);
            msg.setLineSpacing(dp(2), 1f);
            row.addView(msg, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(makeTextBtn(tc, getString(R.string.btn_delete), true,
                    v -> deleteAccount(a.id)));
            row.addView(makeTextBtn(tc, getString(R.string.btn_cancel), false,
                    v -> {
                        confirmDelId = -1;
                        renderAccounts();
                    }));
            return row;
        }

        // 重命名编辑态
        if (editId == a.id) {
            row.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_row));
            row.addView(makeTile(tc, a, dp(40), 20f));

            EditText input = new EditText(this);
            input.setText(a.name);
            input.setTextColor(colorText);
            input.setHintTextColor(colorDim);
            input.setHint(R.string.rename_hint);
            input.setTextSize(13.5f);
            input.setBackground(ContextCompat.getDrawable(tc, R.drawable.bg_rename));
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setPadding(dp(10), dp(7), dp(10), dp(7));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.leftMargin = dp(8);
            input.setLayoutParams(ilp);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitRename(a.id);
                    return true;
                }
                return false;
            });
            row.addView(input);
            renameInput = input;

            row.addView(makeIconBtn(tc, R.drawable.ic_check, 0xFFFFFFFF, R.drawable.bg_editbtn_ok,
                    getString(R.string.cd_rename), v -> commitRename(a.id)));
            row.addView(makeIconBtn(tc, R.drawable.ic_close, colorIcon, R.drawable.bg_editbtn,
                    getString(R.string.btn_cancel), v -> {
                        editId = -1;
                        renderAccounts();
                    }));
            row.post(() -> {
                input.requestFocus();
                input.selectAll();
            });
            return row;
        }

        // 常规态
        row.setBackground(ContextCompat.getDrawable(tc, a.id == store.getCurrentId()
                ? R.drawable.bg_row_active : R.drawable.bg_row));
        row.addView(makeTile(tc, a, dp(40), 20f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(11);
        info.setLayoutParams(ilp);

        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextColor(colorText);
        name.setTextSize(14.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        String status = getString(a.logged ? R.string.status_logged : R.string.status_not_logged);
        SpannableString ss = new SpannableString("●  " + status);
        ss.setSpan(new ForegroundColorSpan(a.logged ? colorGreen : colorDotOff),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView sub = new TextView(this);
        sub.setText(ss);
        sub.setTextColor(colorMuted);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(3), 0, 0);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sub);

        row.addView(info);
        row.addView(makeIconBtn(tc, R.drawable.ic_settings, colorIcon, R.drawable.bg_rail_btn,
                getString(R.string.cd_config), v -> openConfig(a.id)));
        row.addView(makeIconBtn(tc, R.drawable.ic_pencil, colorIcon, R.drawable.bg_rail_btn,
                getString(R.string.cd_rename), v -> {
                    editId = a.id;
                    confirmDelId = -1;
                    renderAccounts();
                }));
        row.addView(makeIconBtn(tc, R.drawable.ic_trash, colorIcon, R.drawable.bg_rail_btn,
                getString(R.string.cd_delete), v -> {
                    confirmDelId = a.id;
                    editId = -1;
                    renderAccounts();
                }));

        row.setOnClickListener(v -> switchTo(a.id, true));
        return row;
    }

    /** 账号头像磁贴：图片头像（圆角位图）优先，否则文字磁贴（按字宽自动缩放，双字不换行不裁切） */
    private View makeTile(Context tc, Account a, int sizePx, float textSp) {
        return AvatarTile.create(tc, sizePx, textSp, a);
    }

    private ImageButton makeIconBtn(Context tc, int icon, int tint, int bg, String cd,
                                    View.OnClickListener onClick) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setBackground(ContextCompat.getDrawable(tc, bg));
        b.setContentDescription(cd);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView makeTextBtn(Context tc, String text, boolean danger,
                                 View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(danger ? 0xFFFFFFFF : ContextCompat.getColor(tc, R.color.btn_gray_text));
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ContextCompat.getDrawable(tc,
                danger ? R.drawable.bg_delbtn_ok : R.drawable.bg_editbtn));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 其他 ----------

    private void showWebViewUpgradeDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.webview_need_update_title)
                .setMessage(R.string.webview_need_update_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.webview_update_btn, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.webview")));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.google.android.webview")));
                        } catch (Exception ignored) {
                        }
                    }
                    finish();
                })
                .setNegativeButton(R.string.btn_exit, (d, w) -> finish())
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** M3: 账号存储损坏/自愈提示（takeLoadNotice 取走即清，不重复弹） */
    private void checkStoreNotice() {
        String n = store.takeLoadNotice();
        if ("recovered".equals(n)) {
            toast(getString(R.string.toast_store_recovered));
        } else if ("corrupt".equals(n)) {
            toast(getString(R.string.toast_store_corrupt));
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
