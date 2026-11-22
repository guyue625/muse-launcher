package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateHelperTest {

    @Test
    public void isNewerVersion_handlesSemanticAndMultiDigitVersions() {
        assertTrue(AppUpdateHelper.isNewerVersion("v1.4", "1.3"));
        assertTrue(AppUpdateHelper.isNewerVersion("1.10", "1.9"));
        assertTrue(AppUpdateHelper.isNewerVersion("1.3.1", "1.3"));
        assertTrue(AppUpdateHelper.isNewerVersion("2.0", "1.9.9"));

        assertFalse(AppUpdateHelper.isNewerVersion("v1.3", "1.3"));
        assertFalse(AppUpdateHelper.isNewerVersion("1.3.0", "1.3"));
        assertFalse(AppUpdateHelper.isNewerVersion("1.2", "1.3"));
        assertFalse(AppUpdateHelper.isNewerVersion("", "1.3"));
        assertFalse(AppUpdateHelper.isNewerVersion("invalid", "1.3"));
    }

    @Test
    public void parseReleaseJson_prefersReleaseApkOverDebugAndIgnoresAndroidTest() {
        String json = "{"
                + "\"tag_name\":\"v1.4\","
                + "\"name\":\"Muse Launcher v1.4\","
                + "\"html_url\":\"https://github.com/seekstars306/muse-launcher/releases/tag/v1.4\","
                + "\"body\":\"### 新增\\n- **在线升级**：支持应用内一键下载安装\\n- 修复 `blob:` 下载路径\","
                + "\"assets\":["
                + "{\"name\":\"app-debug-androidTest.apk\",\"size\":100,\"browser_download_url\":\"https://example.com/test.apk\"},"
                + "{\"name\":\"app-debug.apk\",\"size\":7800000,\"browser_download_url\":\"https://example.com/debug.apk\"},"
                + "{\"name\":\"muse-launcher-v1.4.apk\",\"size\":1420000,\"browser_download_url\":\"https://example.com/release.apk\"}"
                + "]}";
        AppUpdateHelper.ReleaseInfo info = AppUpdateHelper.parseReleaseJson(json);
        assertNotNull(info);
        assertEquals("v1.4", info.tagName);
        assertEquals("1.4", info.versionName);
        assertEquals("muse-launcher-v1.4.apk", info.apkName);
        assertEquals("https://example.com/release.apk", info.apkUrl);
        assertEquals(1420000L, info.apkSizeBytes);
        assertTrue(info.notes.contains("• 在线升级：支持应用内一键下载安装"));
        assertTrue(info.notes.contains("• 修复 blob: 下载路径"));
    }

    @Test
    public void parseReleaseJson_rejectsDraftAndInvalidJson() {
        assertNull(AppUpdateHelper.parseReleaseJson("{\"draft\":true,\"tag_name\":\"v1.5\"}"));
        assertNull(AppUpdateHelper.parseReleaseJson("not-json"));
        assertNull(AppUpdateHelper.parseReleaseJson(""));
    }

    @Test
    public void shouldAutoCheck_respects24HourCooldown() {
        long now = 100_000_000L;
        assertTrue(AppUpdateHelper.shouldAutoCheck(0L, now));
        assertFalse(AppUpdateHelper.shouldAutoCheck(now - 3600_000L, now));
        assertTrue(AppUpdateHelper.shouldAutoCheck(now - AppUpdateHelper.AUTO_CHECK_INTERVAL_MS, now));
    }

    @Test
    public void zipMagic_detectsValidApkHeader() {
        assertTrue(AppUpdateHelper.isZipMagic(new byte[]{0x50, 0x4B, 0x03, 0x04}, 4));
        assertFalse(AppUpdateHelper.isZipMagic(new byte[]{'<', 'h', 't', 'm'}, 4));
        assertFalse(AppUpdateHelper.isZipMagic(new byte[]{0x50, 0x4B}, 2));
    }
}
