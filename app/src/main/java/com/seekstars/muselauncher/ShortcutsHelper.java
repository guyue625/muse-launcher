package com.seekstars.muselauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.Collections;

/** 每账号 pinned shortcut；独立令牌允许 Release 安全打开账号，不开放调试操作。 */
final class ShortcutsHelper {
    static final String EXTRA_ACCOUNT = "shortcut_account";
    static final String EXTRA_TOKEN = "shortcut_token";

    private ShortcutsHelper() {
    }

    static Intent launchIntent(Context c, Account a) {
        return new Intent(c, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ACCOUNT, a.id)
                .putExtra(EXTRA_TOKEN, a.shortcutToken);
    }

    static Account resolve(AccountsStore store, Intent i) {
        if (!isShortcutIntent(i) || store == null) return null;
        Object id = i.getExtras().get(EXTRA_ACCOUNT);
        Object token = i.getExtras().get(EXTRA_TOKEN);
        if (!(id instanceof Long) || !(token instanceof String)) return null;
        Account a = store.findById((Long) id);
        return a != null && a.shortcutToken != null && !a.shortcutToken.isEmpty()
                && a.shortcutToken.equals(token) ? a : null;
    }

    static boolean isShortcutIntent(Intent i) {
        return i != null && i.hasExtra(EXTRA_ACCOUNT);
    }

    /** true 仅代表系统接受请求；是否固定由用户确认，已有 ID 必须显式更新。 */
    static boolean pin(Context c, Account a, Bitmap icon) {
        if (a.shortcutToken == null || a.shortcutToken.isEmpty()) return false;
        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return false;
            if (!refresh(c, a, icon)) return false;
            Intent callback = new Intent(c, ShortcutPinnedReceiver.class)
                    .setAction(c.getPackageName() + ".SHORTCUT_PINNED." + a.id)
                    .putExtra(EXTRA_ACCOUNT, a.id);
            PendingIntent pending = PendingIntent.getBroadcast(c, 0, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            return ShortcutManagerCompat.requestPinShortcut(c, info(c, a, icon),
                    pending.getIntentSender());
        } catch (RuntimeException e) {
            Log.w("MuseShortcut", "pin failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static void disable(Context c, long id) {
        try {
            ShortcutManagerCompat.disableShortcuts(c, Collections.singletonList("acct_" + id),
                    c.getString(R.string.toast_shortcut_invalid));
        } catch (RuntimeException e) {
            Log.w("MuseShortcut", "disable failed: " + e.getClass().getSimpleName());
        }
    }

    /** 未固定过直接跳过；null 图标仅更新名称；失败返回 false，不用再次 pin 假装更新。 */
    static boolean refresh(Context c, Account a, Bitmap icon) {
        try {
            ShortcutManager manager = c.getSystemService(ShortcutManager.class);
            if (manager == null) return true;
            String id = "acct_" + a.id;
            for (ShortcutInfo pinned : manager.getPinnedShortcuts()) {
                if (!id.equals(pinned.getId())) continue;
                if (!pinned.isEnabled())
                    manager.enableShortcuts(Collections.singletonList(id));
                return ShortcutManagerCompat.updateShortcuts(c,
                        Collections.singletonList(info(c, a, icon)));
            }
            return true;
        } catch (RuntimeException e) {
            Log.w("MuseShortcut", "update failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** 用户晚确认时，用最新配置刷新；确认期间已删除账号则禁用残留图标。 */
    static void onPinned(Context c, long id) {
        Account a = new AccountsStore(c).findById(id);
        if (a == null || a.shortcutToken == null) disable(c, id);
        else refresh(c, a, AvatarStore.shortcutIcon(c, a));
    }

    private static ShortcutInfoCompat info(Context c, Account a, Bitmap icon) {
        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(c, "acct_" + a.id)
                .setShortLabel(a.name).setLongLabel(a.name).setIntent(launchIntent(c, a));
        if (icon != null) builder.setIcon(IconCompat.createWithBitmap(icon));
        return builder.build();
    }
}
