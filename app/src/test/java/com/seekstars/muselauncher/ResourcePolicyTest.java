package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class ResourcePolicyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    public void recommendedWebViewCapacity_scalesWithMemory() {
        assertEquals(2, ResourcePolicy.recommendedWebViewCapacity(4L * GB, false));
        assertEquals(2, ResourcePolicy.recommendedWebViewCapacity(8L * GB, true));
        assertEquals(2, ResourcePolicy.recommendedWebViewCapacity(0L, false));
        assertEquals(3, ResourcePolicy.recommendedWebViewCapacity(6L * GB, false));
        assertEquals(3, ResourcePolicy.recommendedWebViewCapacity(16L * GB, false));
    }

    @Test
    public void trimLevels_matchExpectedThresholds() {
        // ComponentCallbacks2 constants:
        // TRIM_MEMORY_RUNNING_MODERATE=5, RUNNING_LOW=10, RUNNING_CRITICAL=15,
        // UI_HIDDEN=20, BACKGROUND=40, MODERATE=60, COMPLETE=80
        assertFalse(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(5));
        assertTrue(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(10));
        assertTrue(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(15));
        // UI_HIDDEN 走延迟回收，不立刻销毁
        assertFalse(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(20));
        assertTrue(ResourcePolicy.shouldScheduleDelayedEviction(20));
        assertFalse(ResourcePolicy.shouldScheduleDelayedEviction(10));
        assertFalse(ResourcePolicy.shouldScheduleDelayedEviction(40));
        // 真正的后台内存压力仍立即回收
        assertTrue(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(40));
        assertTrue(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(60));
        assertTrue(ResourcePolicy.shouldEvictInactiveWebViewsImmediately(80));

        assertFalse(ResourcePolicy.shouldTrimBitmapCache(0));
        assertTrue(ResourcePolicy.shouldTrimBitmapCache(5));
        assertTrue(ResourcePolicy.shouldTrimBitmapCache(10));
        assertFalse(ResourcePolicy.shouldTrimBitmapCache(20));
        assertTrue(ResourcePolicy.shouldTrimBitmapCache(40));
    }

    @Test
    public void cleanupStaleCacheFiles_deletesOnlyExpiredFiles() throws Exception {
        File dir = tmp.newFolder("captures");
        long now = 10_000_000L;
        File stale = new File(dir, "muse_upload_old.jpg");
        File fresh = new File(dir, "muse_upload_new.jpg");
        assertTrue(stale.createNewFile());
        assertTrue(fresh.createNewFile());
        assertTrue(stale.setLastModified(now - ResourcePolicy.CAPTURE_CACHE_MAX_AGE_MS - 1000L));
        assertTrue(fresh.setLastModified(now - 5000L));

        int deleted = ResourcePolicy.cleanupStaleCacheFiles(dir, now, ResourcePolicy.CAPTURE_CACHE_MAX_AGE_MS);
        assertEquals(1, deleted);
        assertFalse(stale.exists());
        assertTrue(fresh.exists());
    }
}
