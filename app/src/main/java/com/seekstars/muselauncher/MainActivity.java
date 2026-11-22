package com.seekstars.muselauncher;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
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

    /** 每个账号一个独立 WebView（Profile 只能在 WebView 初始化前设置一次） */
    private static final int MAX_ALIVE_WEBVIEWS = 4; // LRU：最多同时保留的账号页面数（含当前）
    private final LruAccountRegistry<WebView> webViews = new LruAccountRegistry<>(MAX_ALIVE_WEBVIEWS);
    private final SparseArray<Boolean> webLoaded = new SparseArray<>();
    /** 主框架加载出错标记（错误遮罩依据），重试成功后清除 */
    private final SparseArray<Boolean> webErrored = new SparseArray<>();
    /** 加载耗时统计起点 */
    private final SparseArray<Long> loadStartAt = new SparseArray<>();
    /** 临时用于捕获 window.open / target="_blank" 的桥接 WebView，防止泄漏 */
    private final Set<WebView> transientPopups = new HashSet<>();
    private WebView currentWebView;

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

    /** 基于当前日夜模式创建显式配置上下文，确保 uiMode 变化后颜色与 Drawable 立即准确解析 */
    private Context themedContext() {
        return createConfigurationContext(nightModeConfiguration());
    }

    private void setDarkTheme(boolean dark, boolean showToast) {
        if (darkTheme == dark) return;
        darkTheme = dark;
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
        for (Account a : store.all()) {
            syncThemeCookie(a.id);
        }
        String js = ThemeCookieHelper.buildDomThemeSyncScript(darkTheme);
        for (WebView wv : webViews.snapshot().values()) {
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
                refreshLogged();
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
                webContainer.removeView(view);
                view.destroy();

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
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (BuildConfig.DEBUG && consoleMessage != null) {
                    Log.d("MuseConsole", consoleMessage.messageLevel() + ": "
                            + consoleMessage.message() + " (" + consoleMessage.sourceId()
                            + ":" + consoleMessage.lineNumber() + ")");
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });

        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(req);
                toast(getString(R.string.toast_download_started));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        });

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
    protected void onResume() {
        super.onResume();
        if (store == null || drawerLayout == null) return;
        if (currentWebView != null) currentWebView.onResume();
        // 元数据刷新不能依赖 Cookie 状态发生变化。
        store.reload();
        checkStoreNotice();
        renderAccounts();
        refreshLogged();
        Account cur = store.findById(store.getCurrentId());
        ProxyHelper.apply(cur != null ? cur.proxy : "");
    }

    @Override
    protected void onPause() {
        if (currentWebView != null) currentWebView.onPause();
        try {
            for (Account a : store.all()) {
                Profile p = profileStore.getProfile(profileName(a.id));
                if (p != null) p.getCookieManager().flush();
            }
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (webContainer != null) webContainer.removeCallbacks(hideSplashRunnable);
        probeQueue = null;
        for (WebView popup : new ArrayList<>(transientPopups)) {
            destroyTransientPopup(popup);
        }
        for (Map.Entry<Long, WebView> entry : webViews.snapshot().entrySet()) {
            WebView view = webViews.remove(entry.getKey());
            if (view != null) {
                if (webContainer != null) webContainer.removeView(view);
                view.destroy();
            }
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
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout == null) {
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
                webContainer.removeView(old);
                old.destroy();
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
            webContainer.removeView(wv);
            wv.destroy();
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

    private void refreshLogged() {
        if (!probeRunning.compareAndSet(false, true)) {
            probeDirty.set(true); // 已有一轮在跑，结束后补一轮
            return;
        }
        probeQueue = new ArrayList<>(store.all());
        probeIndex = 0;
        probeChanged = false;
        probeStep();
    }

    private void probeStep() {
        if (probeQueue == null || probeIndex >= probeQueue.size()) {
            probeQueue = null;
            probeRunning.set(false);
            if (probeDirty.compareAndSet(true, false)) refreshLogged();
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
