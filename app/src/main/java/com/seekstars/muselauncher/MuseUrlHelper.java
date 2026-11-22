package com.seekstars.muselauncher;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Muse 页面 URL 判定与历史栈导航辅助（纯 Java，便于 JVM 单元测试）。
 */
public final class MuseUrlHelper {

    private MuseUrlHelper() {
    }

    /** 是否属于 muse.ai 主站或子域页面（http / https） */
    public static boolean isMusePage(String url) {
        String host = extractHttpHost(url);
        return host != null && isHostOrSubdomain(host, "muse.ai");
    }

    /**
     * 是否已跳转到非 muse.ai 的外部网页（需要显示「返回 Muse」悬浮按钮）。
     * 空串、about:blank、非 http(s) 协议不视为外部网页。
     */
    public static boolean isExternalPage(String url) {
        String host = extractHttpHost(url);
        if (host == null) return false;
        return !isHostOrSubdomain(host, "muse.ai");
    }

    /**
     * WebView 权限请求（麦克风 / 摄像头）可自动信任的 Muse 相关来源。
     * 启动器自身持有系统权限时，这些来源直接放行，不再弹浏览器站点权限拦截。
     */
    public static boolean isTrustedWebPermissionOrigin(String origin) {
        String host = extractHttpHost(origin);
        if (host == null) return false;
        return isHostOrSubdomain(host, "muse.ai")
                || isHostOrSubdomain(host, "meta.ai")
                || isHostOrSubdomain(host, "metaaiusercontent.com")
                || isHostOrSubdomain(host, "ecto1usercontent.com");
    }

    /** WebView 内部应直接处理的 scheme，不能被 shouldOverrideUrlLoading 拦截 */
    public static boolean isWebViewInternalScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        String s = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(s)
                || "https".equals(s)
                || "about".equals(s)
                || "blob".equals(s)
                || "data".equals(s)
                || "javascript".equals(s);
    }

    /**
     * 从历史栈当前索引向前查找最近一条 muse.ai 记录，返回传给 goBackOrForward 的负数步长。
     * 若历史栈中没有 muse.ai 记录，返回 0（调用方应直接 loadUrl(START_URL)）。
     */
    public static int findLastMuseHistoryStep(List<String> historyUrls, int currentIndex) {
        if (historyUrls == null || historyUrls.isEmpty()) return 0;
        int start = Math.min(currentIndex - 1, historyUrls.size() - 1);
        for (int i = start; i >= 0; i--) {
            if (isMusePage(historyUrls.get(i))) {
                return i - currentIndex;
            }
        }
        return 0;
    }

    /** 提取 URL 的展示主机名（供第三方站点权限确认弹窗显示） */
    public static String displayHost(String origin) {
        String host = extractHttpHost(origin);
        return host != null ? host : (origin == null ? "" : origin);
    }

    static String extractHttpHost(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return null;
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            String ls = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(ls) && !"https".equals(ls)) return null;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;
            String lower = host.toLowerCase(Locale.ROOT);
            while (lower.endsWith(".")) {
                lower = lower.substring(0, lower.length() - 1);
            }
            return lower.isEmpty() ? null : lower;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
