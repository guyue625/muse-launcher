package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.webkit.WebViewFeature;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class PinnedShortcutRuntimeTest {
    private Context context;
    private Account account;
    private ShortcutManager shortcuts;
    private UiDevice device;

    @Before
    public void setUp() throws Exception {
        context = AndroidTestSupport.targetContext();
        shortcuts = context.getSystemService(ShortcutManager.class);
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Assume.assumeTrue("This test needs a pinnable Android launcher",
                shortcuts != null && shortcuts.isRequestPinShortcutSupported());
        AccountsStore store = new AccountsStore(context);
        account = store.addDefault();
        account.name = "桌面回归" + account.id;
        account.shortcutToken = "pin-test-" + account.id;
        assertTrue(store.save());
        device.wakeUp();
        device.executeShellCommand("wm dismiss-keyguard");
    }

    @After
    public void tearDown() {
        if (account == null) return;
        new AccountsStore(context).remove(account.id);
        AvatarStore.clear(context, account.id);
        ShortcutsHelper.disable(context, account.id);
        device.pressHome();
    }

    @Test
    public void pinConfirm_createsSystemShortcutWithCurrentLabelAndIcon() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = config()) {
            scenario.onActivity(a -> a.findViewById(R.id.cfgShortcut).performClick());
            acceptPin();
            ShortcutInfo pinned = awaitPinned();
            assertEquals(account.name, pinned.getShortLabel().toString());
            assertTrue(pinned.isEnabled());
            assertEquals(account.id, pinned.getIntent().getLongExtra(ShortcutsHelper.EXTRA_ACCOUNT, -1));
        }
    }

    @Test
    public void repeatedPin_explicitlyRefreshesExistingLabel() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = config()) {
            scenario.onActivity(a -> a.findViewById(R.id.cfgShortcut).performClick());
            acceptPin();
            awaitPinned();
            AccountsStore updated = new AccountsStore(context);
            Account fresh = updated.findById(account.id);
            fresh.name = "重复更新" + account.id;
            assertTrue(updated.save());
            scenario.onActivity(a -> assertTrue(ShortcutsHelper.pin(a, fresh,
                    AvatarStore.shortcutIcon(a, fresh))));
            acceptPinIfShown();
            assertEquals(fresh.name, awaitPinned().getShortLabel().toString());
        }
    }

    @Test
    public void latePinConfirmation_usesLatestMetadata() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = config()) {
            scenario.onActivity(a -> a.findViewById(R.id.cfgShortcut).performClick());
            waitPinDialog();
            AccountsStore updated = new AccountsStore(context);
            updated.findById(account.id).name = "确认后新名" + account.id;
            assertTrue(updated.save());
            acceptPin();
            long end = android.os.SystemClock.uptimeMillis() + 15000;
            ShortcutInfo pinned;
            do {
                pinned = awaitPinned();
                if (pinned.getShortLabel().toString().startsWith("确认后新名")) break;
                Thread.sleep(100);
            } while (android.os.SystemClock.uptimeMillis() < end);
            assertEquals("确认后新名" + account.id, pinned.getShortLabel().toString());
        }
    }

    @Test
    public void latePinConfirmation_afterAccountDeletionIsDisabled() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = config()) {
            scenario.onActivity(a -> a.findViewById(R.id.cfgShortcut).performClick());
            waitPinDialog();
            assertTrue(new AccountsStore(context).remove(account.id));
            acceptPin();
            long end = android.os.SystemClock.uptimeMillis() + 15000;
            ShortcutInfo pinned;
            do {
                pinned = awaitPinned();
                if (!pinned.isEnabled()) break;
                Thread.sleep(100);
            } while (android.os.SystemClock.uptimeMillis() < end);
            assertFalse(pinned.isEnabled());
        }
    }

    @Test
    public void launcherTap_fromConfigWithoutReturningMainOpensCorrectAccountFullscreen() throws Exception {
        Assume.assumeTrue(AndroidTestSupport.onMain(() ->
                WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)));
        AccountsStore initial = new AccountsStore(context);
        Account other = initial.addDefault();
        try {
            try (ActivityScenario<AccountConfigActivity> scenario = config()) {
                scenario.onActivity(a -> a.findViewById(R.id.cfgShortcut).performClick());
                acceptPin();
                awaitPinned();
                device.pressHome();
                UiObject2 icon = device.wait(Until.findObject(By.text(account.name)), 30000);
                assertNotNull("The pinned account icon must appear on the launcher", icon);
                icon.click();
                assertTrue(device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 20000));
                // switchTo/enterFullscreen 会持久化选择与全屏状态，轮询即可验证直达行为
                long end = android.os.SystemClock.uptimeMillis() + 30000;
                while (android.os.SystemClock.uptimeMillis() < end) {
                    AccountsStore now = new AccountsStore(context);
                    if (now.getCurrentId() == account.id && now.isFullscreen()) break;
                    Thread.sleep(500);
                }
                AccountsStore now = new AccountsStore(context);
                assertEquals(account.id, now.getCurrentId());
                assertTrue(now.isFullscreen());
            }
        } finally {
            new AccountsStore(context).remove(other.id);
        }
    }

    private ActivityScenario<AccountConfigActivity> config() {
        return ActivityScenario.launch(new Intent(context, AccountConfigActivity.class)
                .putExtra("account_id", account.id));
    }

    private ShortcutInfo awaitPinned() throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + 15000;
        do {
            for (ShortcutInfo info : shortcuts.getPinnedShortcuts()) {
                if (info.getId().equals("acct_" + account.id)) return info;
            }
            Thread.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < end);
        throw new AssertionError("System did not pin account " + account.id);
    }

    private UiObject2 waitPinDialog() {
        UiObject2 button = device.wait(Until.findObject(By.res(
                "com.google.android.apps.nexuslauncher", "add_button")), 25000);
        if (button == null) button = device.wait(Until.findObject(By.text(Pattern.compile(
                "(?i)(add to home screen|add automatically|添加到主屏幕|自动添加|添加)"))), 10000);
        assertNotNull("Launcher pin confirmation must be visible", button);
        return button;
    }

    private void acceptPin() {
        waitPinDialog().click();
    }

    private void acceptPinIfShown() {
        UiObject2 button = device.wait(Until.findObject(By.res(
                "com.google.android.apps.nexuslauncher", "add_button")), 1500);
        if (button != null) button.click();
    }
}
