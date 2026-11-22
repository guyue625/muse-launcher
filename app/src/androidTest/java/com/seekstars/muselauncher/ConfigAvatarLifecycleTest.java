package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class ConfigAvatarLifecycleTest {
    private Context context;
    private Account account;
    private Uri source;

    @Before
    public void setUp() throws Exception {
        context = AndroidTestSupport.targetContext();
        AccountsStore seed = new AccountsStore(context);
        account = seed.addDefault();
        source = AndroidTestSupport.source(context, "lifecycle-" + account.id + ".png",
                AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 600, 300));
    }

    @After
    public void tearDown() {
        new AccountsStore(context).remove(account.id);
        AvatarStore.clear(context, account.id);
        new File(source.getPath()).delete();
    }

    @Test
    public void selectionAndCancel_doesNotPersistImage() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            pick(scenario);
            awaitImage(scenario);
            assertFalse(new AccountsStore(context).findById(account.id).hasImage);
            assertFalse(AvatarStore.file(context, account.id).exists());
            scenario.onActivity(a -> a.findViewById(R.id.cfgBack).performClick());
        }
        assertFalse(AvatarStore.file(context, account.id).exists());
    }

    @Test
    public void selectedImageDraft_survivesActivityRecreation() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            pick(scenario);
            awaitImage(scenario);
            scenario.recreate();
            awaitImage(scenario);
            scenario.onActivity(a -> a.findViewById(R.id.cfgSave).performClick());
        }
        assertTrue(new AccountsStore(context).findById(account.id).hasImage);
        assertEquals(Color.BLUE, AvatarStore.load(context, account.id).getPixel(128, 128));
    }

    @Test
    public void clearImageDraftAndCancel_keepsSavedImage() throws Exception {
        Account saved = new AccountsStore(context).findById(account.id);
        saved.hasImage = true;
        AccountsStore store = new AccountsStore(context);
        store.findById(account.id).hasImage = true;
        assertTrue(store.save());
        assertTrue(AvatarStore.persist(context, account.id,
                AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256)));
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            scenario.onActivity(a -> {
                a.findViewById(R.id.cfgAvatarClear).performClick();
                a.findViewById(R.id.cfgBack).performClick();
            });
        }
        assertTrue(new AccountsStore(context).findById(account.id).hasImage);
        assertNotNull(AvatarStore.load(context, account.id));
    }

    @Test
    public void clearImageAndSave_returnsToTextAvatar() throws Exception {
        AccountsStore store = new AccountsStore(context);
        store.findById(account.id).hasImage = true;
        assertTrue(store.save());
        assertTrue(AvatarStore.persist(context, account.id,
                AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256)));
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            scenario.onActivity(a -> {
                a.findViewById(R.id.cfgAvatarClear).performClick();
                ((EditText) a.findViewById(R.id.cfgAvatar)).setText("中文");
                a.findViewById(R.id.cfgSave).performClick();
            });
        }
        Account after = new AccountsStore(context).findById(account.id);
        assertFalse(after.hasImage);
        assertEquals("中文", after.avatar);
        assertFalse(AvatarStore.file(context, account.id).exists());
    }

    @Test
    public void failedImageFileWrite_keepsActivityAndDraftForRetry() throws Exception {
        File dir = AvatarStore.file(context, account.id).getParentFile();
        assertTrue(dir.isDirectory() || dir.mkdirs());
        byte[] oldPng = AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256);
        assertTrue(AvatarStore.persist(context, account.id, oldPng));
        // 父目录只读：startWrite 建立临时文件必然失败，确定性触发写入失败
        assertTrue(dir.setWritable(false));
        try {
            try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
                pick(scenario);
                awaitImage(scenario);
                scenario.onActivity(a -> {
                    a.findViewById(R.id.cfgSave).performClick();
                    assertFalse(a.isFinishing());
                    assertTrue(isImage(a));
                });
                assertFalse(new AccountsStore(context).findById(account.id).hasImage);
                org.junit.Assert.assertArrayEquals(oldPng,
                        AvatarStore.savedBytes(context, account.id));
                assertTrue(dir.setWritable(true));
                scenario.onActivity(a -> a.findViewById(R.id.cfgSave).performClick());
            }
            assertTrue(new AccountsStore(context).findById(account.id).hasImage);
        } finally {
            dir.setWritable(true);
        }
    }

    @Test
    public void deletedAccount_cannotBeResurrectedByConfigSave() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            new AccountsStore(context).remove(account.id);
            scenario.onActivity(a -> a.findViewById(R.id.cfgSave).performClick());
        }
        org.junit.Assert.assertNull(new AccountsStore(context).findById(account.id));
        assertFalse(AvatarStore.file(context, account.id).exists());
    }

    @Test
    public void tokenSaveFailure_doesNotRequestPinOrPublishToken() throws Exception {
        try (ActivityScenario<AccountConfigActivity> scenario = launch()) {
            scenario.onActivity(a -> {
                AccountsStore failing = new AccountsStore(new KeyValueStore() {
                    final KeyValueStore delegate = new SharedPreferencesStore(context, "accounts");
                    public String getString(String k, String d) { return delegate.getString(k, d); }
                    public long getLong(String k, long d) { return delegate.getLong(k, d); }
                    public boolean getBoolean(String k, boolean d) { return delegate.getBoolean(k, d); }
                    public void putAll(Map<String, Object> v) { throw new IllegalStateException("disk full"); }
                    public void putString(String k, String v) { delegate.putString(k, v); }
                    public void putLong(String k, long v) { delegate.putLong(k, v); }
                    public void putBoolean(String k, boolean v) { delegate.putBoolean(k, v); }
                });
                try {
                    Field field = AccountConfigActivity.class.getDeclaredField("store");
                    field.setAccessible(true);
                    field.set(a, failing);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                a.findViewById(R.id.cfgShortcut).performClick();
                assertFalse(a.isFinishing());
                org.junit.Assert.assertNull(new AccountsStore(context)
                        .findById(account.id).shortcutToken);
            });
        }
    }

    private ActivityScenario<AccountConfigActivity> launch() {
        return ActivityScenario.launch(new Intent(context, AccountConfigActivity.class)
                .putExtra("account_id", account.id));
    }

    private void pick(ActivityScenario<AccountConfigActivity> scenario) {
        scenario.onActivity(a -> {
            try {
                AndroidTestSupport.invoke(a, "onImagePicked", new Class<?>[]{Uri.class}, source);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private boolean isImage(AccountConfigActivity a) {
        ViewGroup preview = a.findViewById(R.id.cfgTile);
        return preview.getChildCount() > 0 && preview.getChildAt(0) instanceof ImageView;
    }

    private void awaitImage(ActivityScenario<AccountConfigActivity> scenario) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(a -> ready.set(isImage(a) && a.findViewById(R.id.cfgSave).isEnabled()));
            if (ready.get()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Image draft did not finish decoding");
    }
}
