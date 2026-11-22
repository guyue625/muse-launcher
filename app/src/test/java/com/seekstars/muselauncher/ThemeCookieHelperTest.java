package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URLDecoder;

public class ThemeCookieHelperTest {

    @Test
    public void buildThemeCookieHeader_noExistingCookie() throws Exception {
        String darkHeader = ThemeCookieHelper.buildThemeCookieHeader(null, true);
        assertTrue(darkHeader.startsWith("theme="));
        assertTrue(darkHeader.contains("Domain=.muse.ai"));
        assertTrue(darkHeader.contains("Path=/"));
        assertTrue(darkHeader.contains("Secure"));
        JSONObject parsed = decodeThemeFromSetCookie(darkHeader);
        assertEquals("dark", parsed.getString("mode"));

        String lightHeader = ThemeCookieHelper.buildThemeCookieHeader("", false);
        assertEquals("light", decodeThemeFromSetCookie(lightHeader).getString("mode"));
    }

    @Test
    public void buildThemeCookieHeader_mergesDuplicateThemeCookies() throws Exception {
        String dup = "theme=%7B%22accent%22%3A%22blue%22%7D; theme=%7B%22style%22%3A%22dense%22%2C%22mode%22%3A%22dark%22%7D";
        String updated = ThemeCookieHelper.buildThemeCookieHeader(dup, false);
        JSONObject parsed = decodeThemeFromSetCookie(updated);
        assertEquals("light", parsed.getString("mode"));
        assertEquals("blue", parsed.getString("accent"));
        assertEquals("dense", parsed.getString("style"));
        assertTrue(ThemeCookieHelper.buildClearHostThemeCookieHeader().contains("Max-Age=0"));
    }

    @Test
    public void buildThemeCookieHeader_preservesOtherMuseSettings() throws Exception {
        String existing = "hatch_sess=secret; theme=%7B%22mode%22%3A%22dark%22%2C%22accent%22%3A%22green%22%2C%22style%22%3A%22airy%22%7D; wd=400x800";
        String updated = ThemeCookieHelper.buildThemeCookieHeader(existing, false);
        JSONObject parsed = decodeThemeFromSetCookie(updated);
        assertEquals("light", parsed.getString("mode"));
        assertEquals("green", parsed.getString("accent"));
        assertEquals("airy", parsed.getString("style"));
    }

    @Test
    public void buildThemeCookieHeader_malformedExistingFallsBackSafely() throws Exception {
        String updated = ThemeCookieHelper.buildThemeCookieHeader("theme=not-json-at-all", true);
        JSONObject parsed = decodeThemeFromSetCookie(updated);
        assertEquals("dark", parsed.getString("mode"));
    }

    @Test
    public void buildDomThemeSyncScript_targetsRequestedMode() {
        String darkJs = ThemeCookieHelper.buildDomThemeSyncScript(true);
        assertTrue(darkJs.contains("('dark')"));
        assertTrue(darkJs.contains("classList.remove('light','dark')"));
        assertTrue(darkJs.contains("colorScheme=mode"));

        String lightJs = ThemeCookieHelper.buildDomThemeSyncScript(false);
        assertTrue(lightJs.contains("('light')"));
    }

    private static JSONObject decodeThemeFromSetCookie(String setCookie) throws Exception {
        String firstPart = setCookie.split(";")[0];
        String val = firstPart.substring("theme=".length());
        return new JSONObject(URLDecoder.decode(val, "UTF-8"));
    }
}
