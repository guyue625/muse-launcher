package com.seekstars.muselauncher;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Iterator;

/**
 * 同步 Muse 网页端主题状态：
 * - muse.ai 在 Domain=.muse.ai 下写入名为 `theme` 的 JSON Cookie（如 {"mode":"dark","accent":"blue"}），
 *   并依赖 `document.documentElement.classList` 上的 `dark` / `light` class 驱动全部样式。
 * - 更新 `theme` Cookie 时保留用户已有的其他字段（style / accent / chatTheme / chatThemeCache / locale 等），只更新 mode，
 *   并清理可能残留的 host-only 同名 Cookie，避免浏览器中出现双份 `theme` Cookie 导致重载时读到旧值。
 */
public final class ThemeCookieHelper {

    public static final String THEME_COOKIE_NAME = "theme";

    private ThemeCookieHelper() {
    }

    /** 根据当前 Cookie 头生成更新后的 `Set-Cookie` 值（Domain=.muse.ai，仅覆盖 mode，保留其余字段） */
    public static String buildThemeCookieHeader(String existingCookies, boolean dark) {
        String mode = dark ? "dark" : "light";
        JSONObject obj = parseExistingThemeJson(existingCookies);
        try {
            obj.put("mode", mode);
        } catch (Exception ignored) {
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(obj.toString(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encoded = obj.toString();
        }
        return THEME_COOKIE_NAME + "=" + encoded
                + "; Domain=.muse.ai; Path=/; Max-Age=31536000; SameSite=Lax; Secure";
    }

    /** 清理可能存在的 host-only `theme` Cookie，避免与 Domain=.muse.ai 同名冲突 */
    public static String buildClearHostThemeCookieHeader() {
        return THEME_COOKIE_NAME + "=; Path=/; Max-Age=0; SameSite=Lax; Secure";
    }

    /** 实时切换已打开 muse.ai 页面的日夜模式脚本（无需刷新页面，保留聊天输入与通话） */
    public static String buildDomThemeSyncScript(boolean dark) {
        String mode = dark ? "dark" : "light";
        return "(function(mode){try{"
                + "var r=document.documentElement;"
                + "if(r){r.classList.remove('light','dark');r.classList.add(mode);r.style.colorScheme=mode;}"
                + "var host=(location.hostname||'').toLowerCase();"
                + "if(host==='muse.ai'||host.endsWith('.muse.ai')){"
                + "var obj={};"
                + "var parts=(document.cookie||'').split(';');"
                + "for(var i=0;i<parts.length;i++){"
                + "var p=parts[i].trim();"
                + "if(p.indexOf('theme=')===0){"
                + "try{var raw=decodeURIComponent(p.substring(6));"
                + "if(raw&&raw.charAt(0)==='{'){var parsed=JSON.parse(raw);"
                + "if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed)){"
                + "var ks=Object.keys(parsed);for(var k=0;k<ks.length;k++)obj[ks[k]]=parsed[ks[k]];}}}catch(e){}"
                + "}}"
                + "obj.mode=mode;"
                + "document.cookie='theme=; Path=/; Max-Age=0; SameSite=Lax; Secure';"
                + "document.cookie='theme='+encodeURIComponent(JSON.stringify(obj))"
                + "+'; Domain=.muse.ai; Path=/; Max-Age=31536000; SameSite=Lax; Secure';"
                + "}"
                + "}catch(e){}})('" + mode + "');";
    }

    static JSONObject parseExistingThemeJson(String existingCookies) {
        JSONObject merged = new JSONObject();
        if (existingCookies == null || existingCookies.isEmpty()) return merged;
        String prefix = THEME_COOKIE_NAME + "=";
        for (String part : existingCookies.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.startsWith(prefix)) continue;
            JSONObject parsed = parseJsonObject(trimmed.substring(prefix.length()));
            if (parsed == null) continue;
            Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    merged.put(k, parsed.get(k));
                } catch (Exception ignored) {
                }
            }
        }
        return merged;
    }

    private static JSONObject parseJsonObject(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) return null;
        try {
            String decoded = URLDecoder.decode(rawValue, "UTF-8");
            if (decoded.startsWith("{")) {
                return new JSONObject(decoded);
            }
        } catch (Exception ignored) {
        }
        if (rawValue.startsWith("{")) {
            try {
                return new JSONObject(rawValue);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
