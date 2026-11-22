package com.seekstars.muselauncher;

import android.content.ComponentCallbacks2;

import java.io.File;

/**
 * 资源占用策略（纯 Java，便于 JVM 单元测试）：
 * - 按设备物理内存动态决定常驻 WebView 上限，避免多账号场景吃爆内存与 GPU 表面；
 * - 退到后台后采用延迟回收（默认 3 分钟），短时切去其他应用再返回依然多账号秒切；
 * - 若系统提前报内存紧张，则立即回收非当前 WebView 与可重建缓存，当前正在使用的页面始终保留；
 * - 定期清理拍照上传残留的临时文件，防止缓存目录无上限膨胀。
 */
public final class ResourcePolicy {

    private static final long GB = 1024L * 1024L * 1024L;
    /** 连续登录态探测最小间隔（毫秒），避免短时间内 onResume + onPageFinished 重复扫 ProfileStore */
    public static final long LOGGED_PROBE_THROTTLE_MS = 2000L;
    /** 退到后台后延迟回收非当前 WebView 的等待时长（3 分钟） */
    public static final long BACKGROUND_EVICT_DELAY_MS = 3L * 60L * 1000L;
    /** 拍照上传临时文件最大保留时长（1 小时） */
    public static final long CAPTURE_CACHE_MAX_AGE_MS = 60L * 60L * 1000L;

    private ResourcePolicy() {
    }

    /**
     * 按设备内存决定同时驻留的 WebView 实例上限（含当前前台账号）：
     * - 低内存设备或总内存 < 6 GB：最多保留 2 个（当前 + 1 个热切换）
     * - 6 GB 及以上设备：最多保留 3 个（当前 + 2 个热切换，较固定 4 个少占约 150–300 MB 渲染内存）
     */
    public static int recommendedWebViewCapacity(long totalMemBytes, boolean lowRamDevice) {
        if (lowRamDevice || totalMemBytes <= 0 || totalMemBytes < 6L * GB) {
            return 2;
        }
        return 3;
    }

    /**
     * 系统明确报内存压力（RUNNING_LOW / RUNNING_CRITICAL / BACKGROUND / MODERATE / COMPLETE）时，
     * 立即回收非当前 WebView；单纯退到后台（UI_HIDDEN）不立刻回收，改走延迟回收。
     */
    public static boolean shouldEvictInactiveWebViewsImmediately(int trimLevel) {
        return (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && trimLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                || trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
    }

    /** 退到后台（UI_HIDDEN）时安排延迟回收，避免短时切去微信等应用回来后切号重刷 */
    public static boolean shouldScheduleDelayedEviction(int trimLevel) {
        return trimLevel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
    }

    /** 任意内存紧张信号均清空可重建的头像位图与字号内存缓存；单纯 UI_HIDDEN 延后到延迟回收时一并清理 */
    public static boolean shouldTrimBitmapCache(int trimLevel) {
        return (trimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
                && trimLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                || trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
    }

    /** 清理超过 maxAgeMs 的缓存临时文件，返回实际删除文件数（仅处理顶层普通文件） */
    public static int cleanupStaleCacheFiles(File dir, long nowMs, long maxAgeMs) {
        if (dir == null || !dir.isDirectory() || maxAgeMs < 0) return 0;
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return 0;
        int deleted = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            long age = nowMs - f.lastModified();
            if (age >= maxAgeMs && f.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
