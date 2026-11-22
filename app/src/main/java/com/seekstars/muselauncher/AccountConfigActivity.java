package com.seekstars.muselauncher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 账号配置页：头像（文字/图片） / 名称 / 代理 / 标签，代理测活，桌面快捷方式 */
public class AccountConfigActivity extends AppCompatActivity {

    private static final String TEST_URL = "https://www.gstatic.com/generate_204";
    private static final long TEST_TIMEOUT_MS = 10000;
    /** Clash 默认端口：7890 混合(HTTP+SOCKS) / 7891 纯 HTTP / 7892 纯 SOCKS */
    private static final int[] CLASH_PORTS = {7890, 7891, 7892};
    private static final int PROBE_TIMEOUT_MS = 400;

    private AccountsStore store;
    private Account account;

    private android.widget.FrameLayout tileFrame;
    private TextView avatarStatus;
    private TextView avatarClearBtn;
    private EditText avatarInput;
    private EditText nameInput;
    private EditText proxyInput;
    private EditText tagsInput;
    private TextView detectBtn;
    private TextView testBtn;
    private TextView testStatus;

    /** 头像图片草稿：选择后仅驻内存预览，点保存才写盘（失败/取消不污染正在使用的配置） */
    private byte[] pendingPng;
    private Bitmap pendingPreview;
    private boolean pendingClear;
    private Uri importingUri;
    private Thread imageThread;
    private int imageGeneration;
    private boolean importing;

    private ActivityResultLauncher<String[]> pickImage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private WebView testWebView;
    private boolean testing = false;
    private boolean detecting = false;
    /** proxy_save 调试模式：测活通过后自动入库（免触摸配置代理） */
    private boolean pendingSave = false;
    /** 测活时代理 URL 内嵌的账密（供 407 挑战应答） */
    private String[] testCreds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        long id = getIntent().getLongExtra("account_id", -1L);
        store = new AccountsStore(this);
        account = store.findById(id);
        if (account == null) {
            finish();
            return;
        }

        tileFrame = findViewById(R.id.cfgTile);
        avatarStatus = findViewById(R.id.cfgAvatarStatus);
        avatarClearBtn = findViewById(R.id.cfgAvatarClear);
        avatarInput = findViewById(R.id.cfgAvatar);
        nameInput = findViewById(R.id.cfgName);
        proxyInput = findViewById(R.id.cfgProxy);
        tagsInput = findViewById(R.id.cfgTags);
        detectBtn = findViewById(R.id.cfgProxyDetect);
        testBtn = findViewById(R.id.cfgProxyTest);
        testStatus = findViewById(R.id.cfgProxyStatus);

        findViewById(R.id.cfgBack).setOnClickListener(v -> finish());
        findViewById(R.id.cfgSave).setOnClickListener(v -> save());
        testBtn.setOnClickListener(v -> testProxy());
        detectBtn.setOnClickListener(v -> detectProxy());

        // 头像图片：系统文件选择器（位图 + SVG），解码裁方后仅存草稿，保存时才写盘
        pickImage = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) onImagePicked(uri);
        });
        findViewById(R.id.cfgAvatarPick).setOnClickListener(v ->
                pickImage.launch(new String[]{"image/*", "image/svg+xml"}));
        avatarClearBtn.setOnClickListener(v -> {
            cancelImageImport();
            pendingPng = null;
            pendingPreview = null;
            pendingClear = true;
            updateTile();
        });
        findViewById(R.id.cfgShortcut).setOnClickListener(v -> createShortcut());

        avatarInput.setText(account.avatar);
        nameInput.setText(account.name);
        proxyInput.setText(account.proxy);
        tagsInput.setText(TextUtils.join(", ", account.tags));
        if (savedInstanceState != null) {
            pendingPng = savedInstanceState.getByteArray("avatar_png");
            pendingPreview = pendingPng != null
                    ? BitmapFactory.decodeByteArray(pendingPng, 0, pendingPng.length) : null;
            pendingClear = savedInstanceState.getBoolean("avatar_clear");
            String uri = savedInstanceState.getString("avatar_import_uri");
            if (uri != null) avatarInput.post(() -> onImagePicked(Uri.parse(uri)));
        }
        updateTile();
        avatarInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateTile();
            }
        });

        if (!ProxyHelper.supported()) {
            testStatus.setText(R.string.config_proxy_unsupported);
            testBtn.setEnabled(false);
            detectBtn.setEnabled(false);
        }

        // 调试入口：预填代理 + 自动测活/自动保存（真机无法 adb 触摸，用于验证与配置代理）
        String preset = getIntent().getStringExtra("proxy_preset");
        if (preset != null) {
            proxyInput.setText(preset);
            if (getIntent().getBooleanExtra("proxy_save", false)) {
                pendingSave = true;
                proxyInput.post(this::testProxy);
            } else if (getIntent().getBooleanExtra("proxy_autotest", false)) {
                proxyInput.post(this::testProxy);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putByteArray("avatar_png", pendingPng);
        state.putBoolean("avatar_clear", pendingClear);
        if (importingUri != null) state.putString("avatar_import_uri", importingUri.toString());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        cancelImageImport();
        if (testWebView != null) {
            testWebView.destroy();
            testWebView = null;
        }
        handler.removeCallbacksAndMessages(null);
        // 恢复当前账号的代理（测活时临时改过全局代理）
        Account cur = store.findById(store.getCurrentId());
        ProxyHelper.apply(cur != null ? cur.proxy : "");
        super.onDestroy();
    }

    /** 预览磁贴：图片头像（新选草稿优先）优先，否则文字实时预览（按字宽自动缩放） */
    private void updateTile() {
        Bitmap bmp = pendingPreview;
        if (bmp == null && !pendingClear && account.hasImage) {
            bmp = AvatarStore.load(this, account.id);
        }
        tileFrame.removeAllViews();
        if (bmp != null) {
            tileFrame.addView(AvatarTile.image(this, dp(64), bmp));
        } else {
            tileFrame.addView(AvatarTile.text(this, dp(64), 32f,
                    avatarInput.getText().toString()));
        }
        boolean hasImg = bmp != null;
        avatarStatus.setVisibility(hasImg ? View.VISIBLE : View.GONE);
        avatarClearBtn.setVisibility(hasImg ? View.VISIBLE : View.GONE);
    }

    /** 异步结果按代次发布；清除、退出或新选择不会被旧解码结果覆盖。 */
    private void onImagePicked(Uri uri) {
        cancelImageImport();
        importing = true;
        importingUri = uri;
        int generation = imageGeneration;
        updateSaveButtons();
        imageThread = new Thread(() -> {
            byte[] png = AvatarStore.readAndConvert(getApplicationContext(), uri);
            Bitmap preview = png != null
                    ? BitmapFactory.decodeByteArray(png, 0, png.length) : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != imageGeneration) return;
                importing = false;
                importingUri = null;
                imageThread = null;
                updateSaveButtons();
                if (preview == null) {
                    Toast.makeText(this, R.string.toast_avatar_invalid,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingPng = png;
                pendingPreview = preview;
                pendingClear = false;
                updateTile();
            });
        }, "avatar-convert");
        imageThread.start();
    }

    private void cancelImageImport() {
        imageGeneration++;
        if (imageThread != null) imageThread.interrupt();
        imageThread = null;
        importingUri = null;
        importing = false;
        updateSaveButtons();
    }

    private void updateSaveButtons() {
        View save = findViewById(R.id.cfgSave);
        View shortcut = findViewById(R.id.cfgShortcut);
        if (save != null) save.setEnabled(!importing);
        if (shortcut != null) shortcut.setEnabled(!importing);
    }

    /** 创建桌面快捷方式：若有未保存的名称/头像草稿则先自动保存，确保桌面图标与当前预览一致 */
    private void createShortcut() {
        if (importing) return;
        if (hasUnsavedChanges()) {
            if (!persist()) return;
        } else if (!refreshAccount()) {
            return;
        }
        if (account.shortcutToken == null) {
            account.shortcutToken = UUID.randomUUID().toString();
            if (!store.save()) {
                Toast.makeText(this, R.string.toast_config_save_failed, Toast.LENGTH_SHORT).show();
                refreshAccount();
                return;
            }
        }
        boolean ok = ShortcutsHelper.pin(this, account, AvatarStore.shortcutIcon(this, account));
        Toast.makeText(this,
                ok ? R.string.toast_shortcut_requested : R.string.toast_shortcut_unsupported,
                Toast.LENGTH_SHORT).show();
    }

    private boolean hasUnsavedChanges() {
        if (pendingPng != null || pendingClear) return true;
        if (account == null) return false;
        String avatar = AvatarTile.normalize(avatarInput.getText().toString());
        if (!TextUtils.equals(avatar, account.avatar)) return true;
        String name = nameInput.getText().toString().trim();
        if (!name.isEmpty()) {
            String clipped = name.length() > 16 ? name.substring(0, 16) : name;
            if (!TextUtils.equals(clipped, account.name)) return true;
        }
        String proxy = proxyInput.getText().toString().trim();
        if (!TextUtils.equals(proxy, account.proxy != null ? account.proxy : "")) return true;
        String tags = TextUtils.join(", ", account.tags);
        return !TextUtils.equals(tagsInput.getText().toString().trim(), tags);
    }

    /**
     * 写盘前重载磁盘最新数据并重取本账号引用：本页持有独立 store 快照，
     * 直接整量 save 会用陈旧数据覆盖主界面期间的改动（甚至复活已删除账号）。
     * 返回 false 表示账号已被删除，直接放弃本次写入。
     */
    private boolean refreshAccount() {
        store.reload();
        Account fresh = store.findById(account.id);
        if (fresh == null) {
            finish();
            return false;
        }
        account = fresh;
        return true;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void save() {
        if (persist()) finish();
    }

    /** 图片先原子写入，元数据失败时恢复旧图片；只有两者成功才清除草稿。 */
    private boolean persist() {
        if (importing || !refreshAccount()) return false;
        byte[] previousImage = null;
        if (pendingPng != null) {
            previousImage = AvatarStore.savedBytes(this, account.id);
            if (account.hasImage && previousImage == null
                    || !AvatarStore.persist(this, account.id, pendingPng)) {
                Toast.makeText(this, R.string.toast_avatar_save_failed, Toast.LENGTH_SHORT).show();
                return false;
            }
            account.hasImage = true;
        } else if (pendingClear) {
            account.hasImage = false;
        }
        account.avatar = AvatarTile.normalize(avatarInput.getText().toString());
        String name = nameInput.getText().toString().trim();
        if (!name.isEmpty()) account.name = name.length() > 16 ? name.substring(0, 16) : name;
        account.proxy = proxyInput.getText().toString().trim();
        List<String> tags = new ArrayList<>();
        for (String t : tagsInput.getText().toString().split("[,，]")) {
            String tag = t.trim();
            if (!tag.isEmpty()) tags.add(tag.length() > 8 ? tag.substring(0, 8) : tag);
        }
        account.tags = tags;
        if (!store.save()) {
            if (pendingPng != null) {
                if (previousImage != null) AvatarStore.persist(this, account.id, previousImage);
                else AvatarStore.clear(this, account.id);
            }
            Toast.makeText(this, R.string.toast_config_save_failed, Toast.LENGTH_SHORT).show();
            refreshAccount();
            return false;
        }
        if (pendingClear) AvatarStore.clear(this, account.id);
        pendingPng = null;
        pendingPreview = null;
        pendingClear = false;
        setResult(RESULT_OK, new Intent().putExtra("account_id", account.id));
        ShortcutsHelper.refresh(this, account, AvatarStore.shortcutIcon(this, account));
        updateTile();
        return true;
    }

    // ---------- 代理测活 ----------

    private void testProxy() {
        if (testing) return;
        final String proxy = proxyInput.getText().toString().trim();
        if (proxy.isEmpty()) {
            testStatus.setText(R.string.config_proxy_empty);
            return;
        }
        testing = true;
        testBtn.setEnabled(false);
        testBtn.setText(R.string.config_proxy_testing);
        testStatus.setText("");

        // 临时应用被测代理
        ProxyHelper.apply(proxy);
        testCreds = parseProxyCreds(proxy);
        final long start = SystemClock.uptimeMillis();

        testWebView = new WebView(this);
        testWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                finishTest(true, null, SystemClock.uptimeMillis() - start);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    finishTest(false, String.valueOf(error.getDescription()), 0);
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView v, HttpAuthHandler handler,
                                                  String host, String realm) {
                // 代理 407 挑战：用 URL 内嵌账密应答（ProxyConfig 不支持内嵌凭证时的兜底）
                if (testCreds != null) {
                    handler.proceed(testCreds[0], testCreds[1]);
                } else {
                    handler.cancel();
                }
            }
        });
        timeoutRunnable = () -> finishTest(false, null, 0);
        handler.postDelayed(timeoutRunnable, TEST_TIMEOUT_MS);
        testWebView.loadUrl(TEST_URL);
    }

    /** 从代理地址解析 [user, pass]，无凭证返回 null */
    private static String[] parseProxyCreds(String p) {
        int at = p.indexOf('@');
        if (at < 0) return null;
        String head = p.substring(0, at);
        int si = head.indexOf("://");
        String creds = si >= 0 ? head.substring(si + 3) : head;
        if (creds.isEmpty()) return null;
        int c = creds.indexOf(':');
        if (c < 0) return new String[]{creds, ""};
        return new String[]{creds.substring(0, c), creds.substring(c + 1)};
    }

    // ---------- Clash 端口探测 ----------

    private void detectProxy() {
        if (detecting) return;
        detecting = true;
        detectBtn.setEnabled(false);
        testStatus.setText(R.string.config_proxy_detecting);
        new Thread(() -> {
            int port = probeLocalProxy();
            runOnUiThread(() -> {
                detecting = false;
                detectBtn.setEnabled(true);
                if (port > 0) {
                    proxyInput.setText(proxyUrlForPort(port));
                    testStatus.setText(getString(R.string.config_proxy_detect_found, port));
                } else {
                    testStatus.setText(R.string.config_proxy_detect_none);
                }
            });
        }).start();
    }

    /** 探测本机 Clash 默认端口，返回首个在监听的端口，未找到返回 0 */
    private static int probeLocalProxy() {
        for (int p : CLASH_PORTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", p), PROBE_TIMEOUT_MS);
                return p;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String proxyUrlForPort(int port) {
        // 7892 是纯 SOCKS，其余按 HTTP 混合端口处理
        return (port == 7892 ? "socks5://" : "http://") + "127.0.0.1:" + port;
    }

    private void finishTest(boolean ok, String err, long ms) {
        if (!testing) return;
        testing = false;
        handler.removeCallbacks(timeoutRunnable);
        if (testWebView != null) {
            testWebView.destroy();
            testWebView = null;
        }
        testBtn.setEnabled(true);
        testBtn.setText(R.string.config_proxy_test);
        if (ok) {
            testStatus.setText(getString(R.string.config_proxy_ok) + "（" + ms + " ms）");
            if (pendingSave) {
                pendingSave = false;
                persist();
                testStatus.setText(getString(R.string.config_proxy_saved)
                        + " · " + getString(R.string.config_proxy_ok) + "（" + ms + " ms）");
            }
        } else if (err == null) {
            testStatus.setText(R.string.config_proxy_timeout);
        } else {
            testStatus.setText(getString(R.string.config_proxy_fail) + err);
        }
    }
}
