package com.seekstars.muselauncher;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 轻量级应用内在线升级工具（零常驻后台，直接对接 GitHub Releases）：
 * - 支持自动复刻当前账号代理访问 GitHub API 与下载 Release APK，代理失败自动回退直连；
 * - 语义化版本号比较，优先选择正式版 .apk 资产；
 * - 流式分块落盘到 cache/updates/，校验 APK ZIP 头，支持实时进度与随时取消。
 */
public final class AppUpdateHelper {

    public static final String RELEASES_API_URL =
            "https://api.github.com/repos/seekstars306/muse-launcher/releases/latest";
    public static final String RELEASES_WEB_URL =
            "https://github.com/seekstars306/muse-launcher/releases/latest";
    /** 自动静默检查更新的最小间隔（24 小时） */
    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private AppUpdateHelper() {
    }

    public static final class ReleaseInfo {
        public final String tagName;
        public final String versionName;
        public final String title;
        public final String notes;
        public final String apkUrl;
        public final String apkName;
        public final long apkSizeBytes;
        public final String htmlUrl;

        public ReleaseInfo(String tagName, String versionName, String title, String notes,
                           String apkUrl, String apkName, long apkSizeBytes, String htmlUrl) {
            this.tagName = tagName;
            this.versionName = versionName;
            this.title = title;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.apkSizeBytes = apkSizeBytes;
            this.htmlUrl = htmlUrl;
        }
    }

    public static boolean shouldAutoCheck(long lastCheckAtMs, long nowMs) {
        if (lastCheckAtMs <= 0L) return true;
        return (nowMs - lastCheckAtMs) >= AUTO_CHECK_INTERVAL_MS;
    }

    /** 去掉前导 v/V 与首尾空白 */
    public static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    /**
     * 比较远端版本是否严格高于当前版本（支持如 1.3 vs 1.4、1.9 vs 1.10、1.3 vs 1.3.1、v1.4 vs 1.4）。
     * 忽略 -beta/-rc 等后缀仅按数字主次修订段比较；非法输入返回 false。
     */
    public static boolean isNewerVersion(String remoteRaw, String currentRaw) {
        int[] r = parseVersionParts(remoteRaw);
        int[] c = parseVersionParts(currentRaw);
        if (r == null || c == null) return false;
        int len = Math.max(r.length, c.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (rv != cv) return rv > cv;
        }
        return false;
    }

    private static int[] parseVersionParts(String raw) {
        String s = normalizeVersion(raw);
        if (s.isEmpty()) return null;
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        if (s.isEmpty()) return null;
        String[] segs = s.split("\\.");
        int[] nums = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i].trim();
            if (seg.isEmpty() || !seg.matches("\\d+")) return null;
            try {
                nums[i] = Integer.parseInt(seg);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return nums;
    }

    /** 将 GitHub Release Markdown 正文精简整理为适合原生弹窗阅读的清晰纯文本 */
    public static String cleanReleaseNotes(String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) return "";
        String[] lines = rawBody.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (sb.length() > 0 && !prevBlank) {
                    sb.append('\n');
                    prevBlank = true;
                }
                continue;
            }
            if (trimmed.startsWith("---") || trimmed.startsWith("___")) continue;
            // 清理 Markdown 标题与粗体/反引号
            trimmed = trimmed.replaceAll("^#{1,6}\\s+", "");
            trimmed = trimmed.replace("**", "").replace("__", "").replace("`", "");
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                trimmed = "• " + trimmed.substring(2).trim();
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(trimmed);
            prevBlank = false;
        }
        return sb.toString().trim();
    }

    /** 解析 GitHub Releases `/releases/latest` JSON 响应，自动挑选最合适的正式版 APK */
    public static ReleaseInfo parseReleaseJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optBoolean("draft", false)) return null;
            String tag = root.optString("tag_name", "").trim();
            String ver = normalizeVersion(tag);
            if (ver.isEmpty()) return null;
            String title = root.optString("name", "").trim();
            if (title.isEmpty()) title = tag;
            String notes = cleanReleaseNotes(root.optString("body", ""));
            String htmlUrl = root.optString("html_url", "").trim();
            if (htmlUrl.isEmpty()) htmlUrl = RELEASES_WEB_URL;

            String bestUrl = "";
            String bestName = "";
            long bestSize = 0L;
            int bestScore = -1;
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "").trim();
                    String url = asset.optString("browser_download_url", "").trim();
                    if (name.isEmpty() || url.isEmpty()) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".apk")) continue;
                    if (lower.contains("androidtest")) continue;
                    int score = 10;
                    if (lower.contains("release")) score += 5;
                    if (lower.contains("debug")) score -= 6;
                    if (score > bestScore) {
                        bestScore = score;
                        bestUrl = url;
                        bestName = name;
                        bestSize = Math.max(0L, asset.optLong("size", 0L));
                    }
                }
            }
            return new ReleaseInfo(tag, ver, title, notes, bestUrl, bestName, bestSize, htmlUrl);
        } catch (Exception e) {
            return null;
        }
    }

    /** 拉取最新 Release 元数据（优先使用当前账号代理，代理不通时自动回退直连） */
    public static ReleaseInfo fetchLatestRelease(String proxyUrl) throws IOException {
        IOException firstErr = null;
        if (proxyUrl != null && !proxyUrl.trim().isEmpty()) {
            try {
                return fetchLatestReleaseOnce(proxyUrl.trim());
            } catch (IOException e) {
                firstErr = e;
            }
        }
        try {
            return fetchLatestReleaseOnce(null);
        } catch (IOException directErr) {
            throw firstErr != null ? firstErr : directErr;
        }
    }

    private static ReleaseInfo fetchLatestReleaseOnce(String proxyUrl) throws IOException {
        HttpURLConnection conn = openConnectionWithRedirects(
                RELEASES_API_URL, proxyUrl, "application/vnd.github+json", 10000, 12000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 512 * 1024) {
                    throw new IOException("Release metadata too large");
                }
            }
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            ReleaseInfo info = parseReleaseJson(json);
            if (info == null) throw new IOException("Invalid release JSON");
            return info;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 流式下载 Release APK 到指定文件（先写 .part 再原子重命名），校验 APK ZIP 魔数，
     * 优先走账号代理，连接失败自动回退直连；支持实时进度与用户取消。
     */
    public static File downloadApkToFile(
            String apkUrl,
            File destFile,
            String proxyUrl,
            MediaDownloadHelper.DownloadProgressListener listener) throws IOException {
        if (listener != null && listener.isCancelled()) {
            throw new MediaDownloadHelper.DownloadCancelledException();
        }
        if (proxyUrl != null && !proxyUrl.trim().isEmpty()) {
            try {
                return downloadApkOnce(apkUrl, destFile, proxyUrl.trim(), listener);
            } catch (MediaDownloadHelper.DownloadCancelledException e) {
                throw e;
            } catch (IOException proxyErr) {
                if (listener != null && listener.isCancelled()) {
                    throw new MediaDownloadHelper.DownloadCancelledException();
                }
                // 代理通道失败时回退直连重试一次
                try {
                    return downloadApkOnce(apkUrl, destFile, null, listener);
                } catch (IOException directErr) {
                    throw proxyErr;
                }
            }
        }
        return downloadApkOnce(apkUrl, destFile, null, listener);
    }

    private static File downloadApkOnce(
            String apkUrl,
            File destFile,
            String proxyUrl,
            MediaDownloadHelper.DownloadProgressListener listener) throws IOException {
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create updates dir");
        }
        File partFile = new File(destFile.getParentFile(), destFile.getName() + ".part");
        //noinspection ResultOfMethodCallIgnored
        partFile.delete();

        HttpURLConnection conn = openConnectionWithRedirects(
                apkUrl, proxyUrl, "application/octet-stream", 15000, 30000);
        try {
            long expectedTotal = conn.getContentLengthLong();
            if (listener != null) listener.onProgress(0L, expectedTotal);

            long written = 0L;
            byte[] header = new byte[4];
            int headerRead = 0;
            byte[] buf = new byte[65536];
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(partFile)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        throw new MediaDownloadHelper.DownloadCancelledException();
                    }
                    if (headerRead < 4) {
                        int take = Math.min(4 - headerRead, n);
                        System.arraycopy(buf, 0, header, headerRead, take);
                        headerRead += take;
                    }
                    out.write(buf, 0, n);
                    written += n;
                    if (listener != null) listener.onProgress(written, expectedTotal);
                }
                out.flush();
            }
            if (written <= 0 || !isZipMagic(header, headerRead)) {
                throw new IOException("Downloaded file is not a valid APK");
            }
            //noinspection ResultOfMethodCallIgnored
            if (destFile.exists()) destFile.delete();
            if (!partFile.renameTo(destFile)) {
                throw new IOException("Failed to finalize APK file");
            }
            return destFile;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    static boolean isZipMagic(byte[] header, int len) {
        return len >= 4
                && header[0] == 0x50
                && header[1] == 0x4B
                && header[2] == 0x03
                && header[3] == 0x04;
    }

    private static HttpURLConnection openConnectionWithRedirects(
            String urlStr, String proxyUrl, String accept,
            int connectTimeoutMs, int readTimeoutMs) throws IOException {
        MediaDownloadHelper.ProxySpec proxySpec = MediaDownloadHelper.parseProxy(proxyUrl);
        if (proxySpec != null && proxySpec.user != null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(proxySpec.user, proxySpec.pass.toCharArray());
                    }
                    return null;
                }
            });
        }

        String currentUrl = urlStr;
        for (int redirect = 0; redirect < 8; redirect++) {
            URL u = new URL(currentUrl);
            HttpURLConnection conn;
            if (proxySpec != null) {
                Proxy proxy = new Proxy(proxySpec.type,
                        InetSocketAddress.createUnresolved(proxySpec.host, proxySpec.port));
                conn = (HttpURLConnection) u.openConnection(proxy);
                if (proxySpec.type == Proxy.Type.HTTP && proxySpec.user != null) {
                    String cred = proxySpec.user + ":" + proxySpec.pass;
                    String encoded = Base64.encodeToString(
                            cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                    conn.setRequestProperty("Proxy-Authorization", "Basic " + encoded);
                }
            } else {
                conn = (HttpURLConnection) u.openConnection();
            }
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", "MuseLauncher/" + BuildConfig.VERSION_NAME);
            if (accept != null && !accept.isEmpty()) {
                conn.setRequestProperty("Accept", accept);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Redirect without Location");
                }
                currentUrl = new URL(u, loc).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }
}
