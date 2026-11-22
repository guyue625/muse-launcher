package com.seekstars.muselauncher;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号元数据持久化。各账号的登录数据由 WebView Profile 负责持久化。
 *
 * 防损设计（M3）：
 * - 每次成功保存前，把上一次的 list 值滚动备份到 list_prev；
 * - list 解析失败时：原始值留底到 list_corrupt（可人工找回），并尝试从 list_prev 自愈；
 * - 单条账号数据损坏只跳过该条，不影响其余；
 * - loadNotice 通过 takeLoadNotice() 上抛给 UI 提示用户。
 */
public class AccountsStore {
    private static final String PREFS = "accounts";
    private static final String KEY_FULLSCREEN = "fullscreen";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_DOWNLOAD_TREE_URI = "download_tree_uri";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check_ms";
    private static final String KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag";

    private final KeyValueStore prefs;
    private final List<Account> accounts = new ArrayList<>();
    private long nextId = 1;
    private long currentId = -1;
    private String loadNotice;

    public AccountsStore(Context ctx) {
        this(new SharedPreferencesStore(ctx, PREFS));
    }

    /** 测试用：注入任意键值实现 */
    public AccountsStore(KeyValueStore store) {
        prefs = store;
        load();
    }

    public synchronized void reload() {
        load();
    }

    private void load() {
        loadNotice = null;
        accounts.clear();
        List<Account> parsed = AccountJsonCodec.fromJsonOrNull(prefs.getString("list", "[]"));
        if (parsed == null) {
            String raw = prefs.getString("list", "");
            if (raw != null && !raw.isEmpty() && !"[]".equals(raw)) {
                // 原始数据留底，避免被后续保存彻底覆盖，人工可找回
                prefs.putString("list_corrupt", raw);
                loadNotice = "corrupt";
            }
            // 尝试从上一次保存前的备份自愈
            List<Account> recovered = AccountJsonCodec.fromJsonOrNull(prefs.getString("list_prev", null));
            if (recovered != null && !recovered.isEmpty()) {
                accounts.addAll(recovered);
                loadNotice = "recovered";
                // 只写回 list 主键。此时 nextId/currentId 还没从磁盘读入，
                // 绝不能走完整 save()（会用字段默认值 nextId=1 覆盖真实值，造成 id 冲突）
                String json = AccountJsonCodec.toJson(accounts);
                if (json != null) prefs.putString("list", json);
            }
        } else {
            accounts.addAll(parsed);
        }
        nextId = prefs.getLong("nextId", 1);
        currentId = prefs.getLong("currentId", -1);
        if (accounts.isEmpty()) {
            currentId = -1;
        } else if (findById(currentId) == null) {
            currentId = accounts.get(0).id;
        }
    }

    public synchronized boolean save() {
        try {
            String json = AccountJsonCodec.toJson(accounts);
            if (json == null) return false;
            Map<String, Object> vals = new HashMap<>();
            String prevList = prefs.getString("list", null);
            if (prevList != null) vals.put("list_prev", prevList);
            vals.put("list", json);
            vals.put("nextId", nextId);
            vals.put("currentId", currentId);
            if (prefs.commitAll(vals)) return true;
        } catch (Exception ignored) {
        }
        load(); // 不发布未保存的账号配置或快捷方式令牌
        return false;
    }

    /**
     * 取走本次加载的结果提示：null=正常，"recovered"=已从备份自愈，"corrupt"=损坏已留底。
     * 取走后清空，避免重复提示。
     */
    public synchronized String takeLoadNotice() {
        String n = loadNotice;
        loadNotice = null;
        return n;
    }

    public synchronized List<Account> all() {
        return accounts;
    }

    public synchronized Account findById(long id) {
        for (Account a : accounts) {
            if (a.id == id) return a;
        }
        return null;
    }

    public synchronized long getCurrentId() {
        return currentId;
    }

    /** 新建账号，默认名 "Muse N"（N 与账号 id 一致）。 */
    public synchronized Account addDefault() {
        Account a = new Account(nextId, "Muse " + nextId);
        nextId++;
        accounts.add(a);
        currentId = a.id;
        save();
        return a;
    }

    /** 新建账号并继承指定代理（国内直连 muse.ai 不通，新环境沿用可用网络路径），返回新账号。 */
    public synchronized Account addDefault(String inheritProxy) {
        Account a = addDefault();
        if (inheritProxy != null && !inheritProxy.isEmpty()) {
            a.proxy = inheritProxy;
            save();
        }
        return a;
    }

    public synchronized boolean rename(long id, String name) {
        Account a = findById(id);
        if (a == null) return false;
        a.name = name;
        return save();
    }

    public synchronized boolean remove(long id) {
        Account a = findById(id);
        if (a != null) accounts.remove(a);
        if (currentId == id) {
            currentId = accounts.isEmpty() ? -1 : accounts.get(0).id;
        }
        return save();
    }

    public synchronized void setCurrent(long id) {
        if (currentId == id) return; // 无变化不写盘
        currentId = id;
        // M4: 只写 currentId 独立键，不再全量序列化账号列表（账号多时切换零开销）
        prefs.putLong("currentId", id);
    }

    public boolean isFullscreen() {
        return prefs.getBoolean(KEY_FULLSCREEN, false);
    }

    public void setFullscreen(boolean v) {
        prefs.putBoolean(KEY_FULLSCREEN, v);
    }

    /** 默认夜间模式，切到日间存 "light"，切回夜间存 "dark" */
    public boolean isDarkTheme() {
        return !"light".equals(prefs.getString(KEY_THEME, "dark"));
    }

    public void setDarkTheme(boolean dark) {
        prefs.putString(KEY_THEME, dark ? "dark" : "light");
    }

    /** 自定义下载目录（SAF Tree URI）；空串表示使用默认目录（相册 / Download/Muse） */
    public String getDownloadTreeUri() {
        String v = prefs.getString(KEY_DOWNLOAD_TREE_URI, "");
        return v == null ? "" : v;
    }

    public void setDownloadTreeUri(String treeUri) {
        prefs.putString(KEY_DOWNLOAD_TREE_URI, treeUri == null ? "" : treeUri.trim());
    }

    public long getLastUpdateCheckAt() {
        return prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public void setLastUpdateCheckAt(long timestampMs) {
        prefs.putLong(KEY_LAST_UPDATE_CHECK, Math.max(0L, timestampMs));
    }

    public String getSkippedUpdateTag() {
        String v = prefs.getString(KEY_SKIPPED_UPDATE_TAG, "");
        return v == null ? "" : v;
    }

    public void setSkippedUpdateTag(String tag) {
        prefs.putString(KEY_SKIPPED_UPDATE_TAG, tag == null ? "" : tag.trim());
    }
}
