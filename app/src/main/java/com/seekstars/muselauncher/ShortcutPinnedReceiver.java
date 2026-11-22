package com.seekstars.muselauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 固定确认回调只做配置同步，不启动页面，也不接受外部应用广播。 */
public final class ShortcutPinnedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        long id = intent.getLongExtra(ShortcutsHelper.EXTRA_ACCOUNT, -1);
        if (id < 0) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                ShortcutsHelper.onPinned(context.getApplicationContext(), id);
            } finally {
                result.finish();
            }
        }, "shortcut-sync").start();
    }
}
