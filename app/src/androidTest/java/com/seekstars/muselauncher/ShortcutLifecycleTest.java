package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewFeature;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ShortcutLifecycleTest {
    private Context context;
    private Account first;
    private Account second;

    @Before
    public void setUp() throws Exception {
        context = AndroidTestSupport.targetContext();
        Assume.assumeTrue("Activity tests require WebView multi-profile support",
                AndroidTestSupport.onMain(() ->
                        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)));
        AccountsStore seed = new AccountsStore(context);
        first = seed.addDefault();
        second = seed.addDefault();
        first.name = "启动回归甲";
        first.avatar = "甲甲";
        first.shortcutToken = "test-" + first.id;
        second.name = "启动回归乙";
        second.avatar = "乙乙";
        second.shortcutToken = "test-" + second.id;
        seed.setCurrent(first.id);
        seed.setFullscreen(false);
        seed.save();
    }

    @After
    public void tearDown() {
        if (context == null || first == null) return;
        AccountsStore cleanup = new AccountsStore(context);
        cleanup.remove(first.id);
        cleanup.remove(second.id);
        cleanup.setFullscreen(false);
        AvatarStore.clear(context, first.id);
        AvatarStore.clear(context, second.id);
    }

    /**
     * 真实冷启动由宿主侧 E2E（am start + 快捷方式 extra）与 PinnedShortcutRuntimeTest
     * 的桌面点击覆盖：ActivityScenario 对 FLAG_ACTIVITY_NEW_TASK 的快捷方式 intent
     * 观察不到生命周期（PRE_ON_CREATE 超时），属测试工具限制而非应用行为。
     */
    @Test
    public void invalidShortcutIntent_fallsBackToUsableMainScreen() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            scenario.onActivity(a -> a.onNewIntent(ShortcutsHelper.launchIntent(context, second)
                    .putExtra(ShortcutsHelper.EXTRA_TOKEN, "wrong")));
            scenario.onActivity(a -> {
                assertEquals(first.id, new AccountsStore(context).getCurrentId());
                assertTrue("Invalid shortcut must not leave the UI with no browser or empty state",
                        ((ViewGroup) a.findViewById(R.id.webContainer)).getChildCount() > 0);
                assertEquals(View.VISIBLE, a.findViewById(R.id.rail).getVisibility());
            });
        }
    }

    @Test
    public void validShortcutWarmLaunch_usesLatestPersistedToken() throws Exception {
        AccountsStore seed = new AccountsStore(context);
        seed.findById(second.id).shortcutToken = null;
        seed.save();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            AccountsStore configStore = new AccountsStore(context);
            Account fresh = configStore.findById(second.id);
            fresh.shortcutToken = "new-token-created-in-config";
            configStore.save();
            Intent shortcut = ShortcutsHelper.launchIntent(context, fresh);
            scenario.onActivity(a -> a.onNewIntent(shortcut));
            scenario.onActivity(a -> assertCurrentFullscreen(a, second.id));
        }
    }

    @Test
    public void invalidShortcutIntent_wrongTokenDoesNotSwitch() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            scenario.onActivity(a -> a.onNewIntent(ShortcutsHelper.launchIntent(context, second)
                    .putExtra(ShortcutsHelper.EXTRA_TOKEN, "wrong")));
            scenario.onActivity(a ->
                    assertEquals(first.id, new AccountsStore(context).getCurrentId()));
        }
    }

    @Test
    public void missingOrWrongToken_isRejectedByResolver() {
        AccountsStore store = new AccountsStore(context);
        Intent missing = new Intent(context, MainActivity.class)
                .putExtra(ShortcutsHelper.EXTRA_ACCOUNT, second.id);
        assertNull(ShortcutsHelper.resolve(store, missing));
        Intent wrong = ShortcutsHelper.launchIntent(context, second)
                .putExtra(ShortcutsHelper.EXTRA_TOKEN, "bad-token");
        assertNull(ShortcutsHelper.resolve(store, wrong));
        assertNotNull(ShortcutsHelper.resolve(store, ShortcutsHelper.launchIntent(context, second)));
        store.remove(second.id);
        assertNull(ShortcutsHelper.resolve(store, ShortcutsHelper.launchIntent(context, second)));
    }

    @Test
    public void activityRecreation_doesNotReplayConsumedShortcut() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            scenario.onActivity(a -> {
                a.onNewIntent(ShortcutsHelper.launchIntent(context, first)); // 快捷方式 → 全屏
                a.onBackPressed();                                           // 退出全屏
                invoke(a, "switchTo", new Class<?>[]{long.class, boolean.class}, second.id, false);
            });
            scenario.recreate();
            scenario.onActivity(a -> {
                assertEquals(second.id, new AccountsStore(context).getCurrentId());
                assertEquals(View.VISIBLE, a.findViewById(R.id.rail).getVisibility());
            });
        }
    }

    @Test
    public void resume_rerendersChangedUnloggedNameAndAvatar() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            AccountsStore other = new AccountsStore(context);
            other.findById(first.id).name = "未登录改名";
            other.findById(first.id).avatar = "新名";
            other.save();
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(a -> {
                assertTrue(hasText(a.findViewById(R.id.accountList), "未登录改名"));
                assertTrue(hasText(a.findViewById(R.id.railAvatars), "新名"));
            });
        }
    }

    @Test
    public void configurationSave_returnRendersLatestMetadata() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(normalIntent())) {
            AccountsStore config = new AccountsStore(context);
            config.findById(first.id).name = "配置保存后";
            config.findById(first.id).avatar = "配置";
            config.save();
            scenario.onActivity(a -> a.onActivityResult(10002, Activity.RESULT_OK,
                    new Intent().putExtra("account_id", first.id)));
            scenario.onActivity(a -> {
                assertTrue(hasText(a.findViewById(R.id.accountList), "配置保存后"));
                assertTrue(hasText(a.findViewById(R.id.railAvatars), "配置"));
            });
        }
    }

    @Test
    public void configAvatarInput_doesNotSplitCompositeEmoji() {
        Intent intent = new Intent(context, AccountConfigActivity.class)
                .putExtra("account_id", first.id);
        try (ActivityScenario<AccountConfigActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(a -> {
                String emoji = "👨‍👩‍👧‍👦👍🏽";
                ((EditText) a.findViewById(R.id.cfgAvatar)).setText(emoji);
                invoke(a, "persist", new Class<?>[0]);
                assertEquals(emoji, new AccountsStore(context).findById(first.id).avatar);
            });
        }
    }

    @Test
    public void releaseIgnoresDebugMutationExtras() {
        Assume.assumeFalse("This check targets Release", BuildConfig.DEBUG);
        AccountsStore before = new AccountsStore(context);
        int count = before.all().size();
        Intent debug = normalIntent().putExtra("add_account", true)
                .putExtra("del_account", first.id).putExtra("start_account", second.id);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(debug)) {
            scenario.onActivity(a -> {
                AccountsStore actual = new AccountsStore(context);
                assertEquals(count, actual.all().size());
                assertNotNull(actual.findById(first.id));
                assertEquals(first.id, actual.getCurrentId());
            });
        }
    }

    private Intent normalIntent() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN);
    }

    private void assertCurrentFullscreen(MainActivity a, long id) {
        assertEquals(id, new AccountsStore(context).getCurrentId());
        assertEquals(View.GONE, a.findViewById(R.id.rail).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.edgeSwipe).getVisibility());
        assertTrue(new AccountsStore(context).isFullscreen());
        assertTrue(((ViewGroup) a.findViewById(R.id.webContainer)).getChildCount() > 0);
    }

    static boolean hasText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private void invoke(Object a, String method, Class<?>[] types, Object... args) {
        try {
            AndroidTestSupport.invoke(a, method, types, args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
