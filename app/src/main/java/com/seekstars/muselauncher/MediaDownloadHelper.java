package com.seekstars.muselauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebView 媒体与文件下载辅助：
 * - 图片保存到系统相册（Pictures/Muse）
 * - 视频保存到系统相册（Movies/Muse）
 * - 其他文件保存到公共下载目录（Download/Muse）
 * 全部通过 Android 10+ MediaStore（IS_PENDING 原子发布）落盘，无需存储权限，相册与文件管理器立即可见。
 */
public final class MediaDownloadHelper {

    public enum Category {
        IMAGE,
        VIDEO,
        DOWNLOAD
    }

    public static final class Destination {
        public final Category category;
        public final String relativePath;
        public final String mimeType;
        public final String fileName;

        public Destination(Category category, String relativePath, String mimeType, String fileName) {
            this.category = category;
            this.relativePath = relativePath;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }
    }

    public static final class DataUrlPayload {
        public final String mimeType;
        public final byte[] bytes;

        public DataUrlPayload(String mimeType, byte[] bytes) {
            this.mimeType = mimeType;
            this.bytes = bytes;
        }
    }

    public static final class SavedMedia {
        public final Uri uri;
        public final Destination destination;
        public final boolean usedCustomDir;
        public final boolean customDirFallback;

        public SavedMedia(Uri uri, Destination destination,
                          boolean usedCustomDir, boolean customDirFallback) {
            this.uri = uri;
            this.destination = destination;
            this.usedCustomDir = usedCustomDir;
            this.customDirFallback = customDirFallback;
        }
    }

    public static final class PendingMedia {
        private final Context appContext;
        private final ContentResolver resolver;
        public final Uri uri;
        public final OutputStream out;
        public final Destination destination;
        private final boolean isSaf;
        private final boolean customDirFallback;
        private final String scanFilePath;
        private boolean finished;

        PendingMedia(Context appContext, ContentResolver resolver, Uri uri, OutputStream out,
                     Destination destination, boolean isSaf, boolean customDirFallback,
                     String scanFilePath) {
            this.appContext = appContext;
            this.resolver = resolver;
            this.uri = uri;
            this.out = out;
            this.destination = destination;
            this.isSaf = isSaf;
            this.customDirFallback = customDirFallback;
            this.scanFilePath = scanFilePath;
        }

        public SavedMedia commit() throws IOException {
            if (finished) return new SavedMedia(uri, destination, isSaf, customDirFallback);
            finished = true;
            try {
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (!isSaf) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            } else if (scanFilePath != null && !scanFilePath.isEmpty()) {
                try {
                    MediaScannerConnection.scanFile(
                            appContext, new String[]{scanFilePath},
                            new String[]{destination.mimeType}, null);
                } catch (Exception ignored) {
                }
            }
            return new SavedMedia(uri, destination, isSaf, customDirFallback);
        }

        public void abort() {
            if (finished) return;
            finished = true;
            try {
                out.close();
            } catch (IOException ignored) {
            }
            try {
                if (isSaf) {
                    DocumentsContract.deleteDocument(resolver, uri);
                } else {
                    resolver.delete(uri, null, null);
                }
            } catch (Exception ignored) {
            }
        }
    }

    static final class ProxySpec {
        final Proxy.Type type;
        final String host;
        final int port;
        final String user;
        final String pass;

        ProxySpec(Proxy.Type type, String host, int port, String user, String pass) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }
    }

    private static final Pattern CD_FILENAME_STAR = Pattern.compile(
            "filename\\*\\s*=\\s*(?:UTF-8|utf-8)?''([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_FILENAME_QUOTED = Pattern.compile(
            "filename\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern CD_FILENAME_PLAIN = Pattern.compile(
            "filename\\s*=\\s*([^;\\s\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> EXT_TO_MIME = new HashMap<>();
    private static final Map<String, String> MIME_TO_EXT = new HashMap<>();

    static {
        registerMime("jpg", "image/jpeg");
        registerMime("jpeg", "image/jpeg");
        registerMime("png", "image/png");
        registerMime("webp", "image/webp");
        registerMime("gif", "image/gif");
        registerMime("bmp", "image/bmp");
        registerMime("heic", "image/heic");
        registerMime("heif", "image/heif");
        registerMime("avif", "image/avif");
        registerMime("svg", "image/svg+xml");

        registerMime("mp4", "video/mp4");
        registerMime("m4v", "video/mp4");
        registerMime("webm", "video/webm");
        registerMime("mov", "video/quicktime");
        registerMime("mkv", "video/x-matroska");
        registerMime("3gp", "video/3gpp");

        registerMime("mp3", "audio/mpeg");
        registerMime("wav", "audio/wav");
        registerMime("m4a", "audio/mp4");
        registerMime("ogg", "audio/ogg");
        registerMime("flac", "audio/flac");

        registerMime("pdf", "application/pdf");
        registerMime("zip", "application/zip");
        registerMime("txt", "text/plain");
        registerMime("md", "text/markdown");
        registerMime("csv", "text/csv");
        registerMime("json", "application/json");
        registerMime("html", "text/html");
        registerMime("htm", "text/html");
        registerMime("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        registerMime("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        registerMime("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

        // 优先后缀（覆盖上面可能后注册的别名）
        MIME_TO_EXT.put("image/jpeg", "jpg");
        MIME_TO_EXT.put("video/mp4", "mp4");
        MIME_TO_EXT.put("text/html", "html");
    }

    private static void registerMime(String ext, String mime) {
        EXT_TO_MIME.put(ext, mime);
        if (!MIME_TO_EXT.containsKey(mime)) {
            MIME_TO_EXT.put(mime, ext);
        }
    }

    private MediaDownloadHelper() {
    }

    /** 根据 URL、Content-Disposition、MIME 推导最终保存位置（目录 + 归一化 MIME + 合法文件名） */
    public static Destination resolveDestination(String url, String contentDisposition,
                                                 String rawMimeType, String defaultStem) {
        String rawName = extractFileName(url, contentDisposition);
        String mime = resolveMimeType(url, rawName, rawMimeType);
        String stem = (defaultStem != null && !defaultStem.isEmpty())
                ? defaultStem : defaultTimestampStem();
        String finalName = ensureExtension(sanitizeFileName(rawName, stem), mime);
        Category cat = classifyCategory(mime);
        String relPath;
        if (cat == Category.IMAGE) {
            relPath = "Pictures/Muse";
        } else if (cat == Category.VIDEO) {
            relPath = "Movies/Muse";
        } else {
            relPath = "Download/Muse";
        }
        return new Destination(cat, relPath, mime, finalName);
    }

    public static Category classifyCategory(String mimeType) {
        if (mimeType == null) return Category.DOWNLOAD;
        String m = cleanMime(mimeType);
        // SVG 不适合塞进系统相册 MediaStore.Images，放 Download/Muse 更稳妥
        if (m.startsWith("image/") && !"image/svg+xml".equals(m)) return Category.IMAGE;
        if (m.startsWith("video/")) return Category.VIDEO;
        return Category.DOWNLOAD;
    }

    public static String resolveMimeType(String url, String fileName, String rawMimeType) {
        String m = cleanMime(rawMimeType);
        if (!isGenericMime(m)) return m;

        if (url != null && url.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = url.indexOf(',');
            if (comma > 5) {
                String meta = url.substring(5, comma);
                int semi = meta.indexOf(';');
                String dataMime = cleanMime(semi >= 0 ? meta.substring(0, semi) : meta);
                if (!isGenericMime(dataMime)) return dataMime;
            }
        }

        String ext = extensionOf(fileName);
        if (ext != null) {
            String mapped = EXT_TO_MIME.get(ext);
            if (mapped != null) return mapped;
        }
        if (url != null) {
            String pathName = extractUrlPathName(url);
            String pathExt = extensionOf(pathName);
            if (pathExt != null) {
                String mapped = EXT_TO_MIME.get(pathExt);
                if (mapped != null) return mapped;
            }
        }
        return "application/octet-stream";
    }

    static String extractFileName(String url, String contentDisposition) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            Matcher mStar = CD_FILENAME_STAR.matcher(contentDisposition);
            if (mStar.find()) {
                String decoded = urlDecodeSafe(mStar.group(1).trim());
                if (!decoded.isEmpty()) return decoded;
            }
            Matcher mQuoted = CD_FILENAME_QUOTED.matcher(contentDisposition);
            if (mQuoted.find()) {
                String val = urlDecodeSafe(mQuoted.group(1).trim());
                if (!val.isEmpty()) return val;
            }
            Matcher mPlain = CD_FILENAME_PLAIN.matcher(contentDisposition);
            if (mPlain.find()) {
                String val = urlDecodeSafe(mPlain.group(1).trim());
                if (!val.isEmpty()) return val;
            }
        }
        return extractUrlPathName(url);
    }

    private static String extractUrlPathName(String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.regionMatches(true, 0, "blob:", 0, 5)
                || url.regionMatches(true, 0, "data:", 0, 5)) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            String path = u.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) return null;
            int slash = path.lastIndexOf('/');
            String seg = slash >= 0 ? path.substring(slash + 1) : path;
            seg = urlDecodeSafe(seg.trim());
            if (seg.isEmpty() || !seg.contains(".")) return null;
            return seg;
        } catch (Exception e) {
            return null;
        }
    }

    static String sanitizeFileName(String raw, String fallbackStem) {
        if (raw == null || raw.trim().isEmpty()) return fallbackStem;
        String s = raw.trim();
        // 只保留最后一段路径，去掉目录穿越与非法字符
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[\\x00-\\x1F:*?\"<>|]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty() || "download".equalsIgnoreCase(s) || "download.bin".equalsIgnoreCase(s)) {
            return fallbackStem;
        }
        if (s.length() > 100) {
            String ext = extensionOf(s);
            if (ext != null && ext.length() <= 10) {
                s = s.substring(0, 100 - ext.length() - 1) + "." + ext;
            } else {
                s = s.substring(0, 100);
            }
        }
        return s;
    }

    static String ensureExtension(String fileName, String mimeType) {
        String desiredExt = MIME_TO_EXT.get(cleanMime(mimeType));
        String currentExt = extensionOf(fileName);
        if (desiredExt == null) return fileName;
        if (currentExt == null) return fileName + "." + desiredExt;
        if ("bin".equals(currentExt) || "tmp".equals(currentExt)) {
            int dot = fileName.lastIndexOf('.');
            return fileName.substring(0, dot + 1) + desiredExt;
        }
        return fileName;
    }

    static String extensionOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.length() > 10 || !ext.matches("[a-z0-9]+")) return null;
        return ext;
    }

    private static String cleanMime(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        return s;
    }

    private static boolean isGenericMime(String mime) {
        return mime == null || mime.isEmpty()
                || "application/octet-stream".equals(mime)
                || "binary/octet-stream".equals(mime)
                || "*/*".equals(mime);
    }

    private static String urlDecodeSafe(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String defaultTimestampStem() {
        return "Muse_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** 解析 data: URL（支持 base64 与 URL 编码） */
    public static DataUrlPayload parseDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.regionMatches(true, 0, "data:", 0, 5)) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 5) return null;
        String meta = dataUrl.substring(5, comma);
        String body = dataUrl.substring(comma + 1);
        boolean isBase64 = meta.toLowerCase(Locale.ROOT).endsWith(";base64");
        String rawMime = isBase64 ? meta.substring(0, meta.length() - 7) : meta;
        String mime = cleanMime(rawMime);
        if (mime.isEmpty()) mime = "application/octet-stream";
        try {
            byte[] bytes = isBase64
                    ? java.util.Base64.getMimeDecoder().decode(body)
                    : urlDecodeSafe(body).getBytes(StandardCharsets.UTF_8);
            return new DataUrlPayload(mime, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 延迟 15 秒执行 URL.revokeObjectURL，防止网页在 a.click() 后立刻销毁 blob URL
     * 导致 Android onDownloadStart 异步 fetch(blobUrl) 时报 ERR_FILE_NOT_FOUND。
     */
    public static String buildRevokeObjectUrlGuardScript() {
        return "(function(){try{"
                + "if(window.__museRevokeGuarded)return;"
                + "window.__museRevokeGuarded=true;"
                + "if(typeof URL==='undefined'||typeof URL.revokeObjectURL!=='function')return;"
                + "var orig=URL.revokeObjectURL.bind(URL);"
                + "URL.revokeObjectURL=function(u){"
                + "setTimeout(function(){try{orig(u);}catch(e){}},15000);"
                + "};"
                + "}catch(e){}})();";
    }

    /**
     * 在 WebView 内以当前账号 Cookie 与代理上下文流式拉取资源（支持 blob:/http:/https:）：
     * 优先使用 ReadableStream 边读边按 ~240 KB 分块回传给原生 @JavascriptInterface，
     * 大视频下载峰值内存仅约 2 MB，避免整包 arrayBuffer() 撑爆内存。
     */
    /** 用户主动取消下载时抛出的受检异常，用于区分取消与网络失败 */
    public static final class DownloadCancelledException extends IOException {
        public DownloadCancelledException() {
            super("Download cancelled");
        }
    }

    /** 原生 HTTP 下载进度与取消监听 */
    public interface DownloadProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);

        boolean isCancelled();
    }

    /** 格式化字节数为简洁可读文本（如 320 KB / 12.4 MB） */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** 计算下载进度百分比（未知总长返回 -1，有效范围 0..100） */
    public static int computeProgressPercent(long downloadedBytes, long totalBytes) {
        if (totalBytes <= 0) return -1;
        if (downloadedBytes <= 0) return 0;
        if (downloadedBytes >= totalBytes) return 100;
        return (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes);
    }

    public static String buildWebFetchDownloadScript(String token, String url) {
        String quotedToken = jsQuote(token);
        String quotedUrl = jsQuote(url);
        return "(function(token,url){"
                + "var b=window.MuseDownload;"
                + "if(!b)return;"
                + "var CHUNK=245760;"
                + "function cancelled(){try{return !!(b.isCancelled&&b.isCancelled(token));}catch(e){return false;}}"
                + "function emitSlice(u8){"
                + "for(var off=0;off<u8.length;off+=CHUNK){"
                + "if(cancelled())return false;"
                + "var end=Math.min(off+CHUNK,u8.length);"
                + "var parts=[];"
                + "for(var i=off;i<end;i+=8192)parts.push(String.fromCharCode.apply(null,u8.subarray(i,Math.min(i+8192,end))));"
                + "b.onChunk(token,btoa(parts.join('')));"
                + "}"
                + "return !cancelled();"
                + "}"
                + "fetch(url,{credentials:'include'}).then(function(resp){"
                + "if(cancelled())return;"
                + "if(!resp.ok)throw new Error('HTTP '+resp.status);"
                + "var ct=resp.headers?resp.headers.get('content-type')||'':'';"
                + "var cd=resp.headers?resp.headers.get('content-disposition')||'':'';"
                + "var cl=resp.headers?parseInt(resp.headers.get('content-length')||'0',10)||0:0;"
                + "if(resp.body&&typeof resp.body.getReader==='function'){"
                + "var reader=resp.body.getReader();"
                + "var started=false;"
                + "var total=0;"
                + "function pump(){"
                + "if(cancelled()){try{reader.cancel();}catch(e){}return;}"
                + "return reader.read().then(function(step){"
                + "if(cancelled()){try{reader.cancel();}catch(e){}return;}"
                + "if(step.done){"
                + "if(total===0)throw new Error('EMPTY');"
                + "b.onEnd(token);"
                + "return;"
                + "}"
                + "var v=step.value;"
                + "if(v&&v.length>0){"
                + "if(!started){started=true;b.onBegin(token,ct,cd,cl);}"
                + "total+=v.length;"
                + "if(!emitSlice(v)){try{reader.cancel();}catch(e){}return;}"
                + "}"
                + "return pump();"
                + "});"
                + "}"
                + "return pump();"
                + "}"
                + "return resp.arrayBuffer().then(function(buf){"
                + "if(cancelled())return;"
                + "var u8=new Uint8Array(buf);"
                + "if(u8.length===0)throw new Error('EMPTY');"
                + "b.onBegin(token,ct,cd,u8.length);"
                + "if(!emitSlice(u8))return;"
                + "b.onEnd(token);"
                + "});"
                + "}).catch(function(err){"
                + "if(cancelled())return;"
                + "try{b.onFetchFailed(token,String(err&&err.message||err));}catch(e){}"
                + "});"
                + "})(" + quotedToken + "," + quotedUrl + ");";
    }

    private static String jsQuote(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    /** 解析账号代理地址用于原生 HttpURLConnection 兜底下载 */
    static ProxySpec parseProxy(String rawProxy) {
        if (rawProxy == null || rawProxy.trim().isEmpty()) return null;
        try {
            String normalized = ProxyHelper.normalize(rawProxy.trim());
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isEmpty() || port <= 0) return null;
            Proxy.Type type;
            if ("socks5".equals(scheme) || "socks4".equals(scheme) || "socks".equals(scheme)) {
                type = Proxy.Type.SOCKS;
            } else if ("http".equals(scheme)) {
                type = Proxy.Type.HTTP;
            } else {
                // https 代理隧道 Java HttpURLConnection 不原生支持，返回 null 交给上层处理
                return null;
            }
            String user = null;
            String pass = null;
            String userInfo = uri.getRawUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    user = urlDecodeSafe(userInfo.substring(0, colon));
                    pass = urlDecodeSafe(userInfo.substring(colon + 1));
                } else {
                    user = urlDecodeSafe(userInfo);
                    pass = "";
                }
            }
            return new ProxySpec(type, host, port, user, pass);
        } catch (Exception e) {
            return null;
        }
    }

    /** 将 SAF Tree URI 格式化为易读的目录路径（如 Download/Muse、SD卡/Muse） */
    public static String formatTreeUriDisplay(String treeUri) {
        if (treeUri == null || treeUri.trim().isEmpty()) return "";
        String docId = extractTreeDocId(treeUri);
        if (docId == null || docId.isEmpty()) return treeUri;
        int colon = docId.indexOf(':');
        if (colon >= 0) {
            String volume = docId.substring(0, colon);
            String path = trimSlashes(docId.substring(colon + 1));
            if ("primary".equalsIgnoreCase(volume)) {
                return path.isEmpty() ? "内部存储根目录" : path;
            }
            return path.isEmpty() ? ("外置存储(" + volume + ")") : ("外置存储/" + path);
        }
        return trimSlashes(docId);
    }

    /** 若 SAF Tree URI 指向主内部存储，返回其相对路径（供 MediaScanner 索引刷新相册）；否则返回 null */
    public static String extractPrimaryRelativePath(String treeUri) {
        String docId = extractTreeDocId(treeUri);
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String volume = docId.substring(0, colon);
        if (!"primary".equalsIgnoreCase(volume)) return null;
        return trimSlashes(docId.substring(colon + 1));
    }

    private static String extractTreeDocId(String treeUri) {
        if (treeUri == null || treeUri.isEmpty()) return null;
        int idx = treeUri.indexOf("/tree/");
        if (idx < 0) return null;
        String rest = treeUri.substring(idx + 6);
        int nextSlash = rest.indexOf('/');
        if (nextSlash >= 0) rest = rest.substring(0, nextSlash);
        return urlDecodeSafe(rest);
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }

    /**
     * 优先按用户配置的自定义 SAF 目录创建下载文件；未配置或自定义目录失效时自动落盘到默认 MediaStore 目录。
     */
    public static PendingMedia openPendingDownload(Context ctx, Destination dest,
                                                   String customTreeUri) throws IOException {
        boolean customFallback = false;
        if (customTreeUri != null && !customTreeUri.trim().isEmpty()) {
            try {
                PendingMedia saf = openPendingSaf(ctx, dest, customTreeUri.trim());
                if (saf != null) return saf;
                customFallback = true;
            } catch (Exception e) {
                Log.w("MuseDownload", "custom SAF dir unavailable, fallback to default: "
                        + e.getMessage());
                customFallback = true;
            }
        }
        return openPendingMedia(ctx, dest, customFallback);
    }

    private static PendingMedia openPendingSaf(Context ctx, Destination dest,
                                               String treeUriStr) throws IOException {
        Uri treeUri = Uri.parse(treeUriStr);
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        if (treeDocId == null || treeDocId.isEmpty()) return null;
        Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
        ContentResolver resolver = ctx.getContentResolver();
        Uri createdUri = DocumentsContract.createDocument(
                resolver, parentDocUri, dest.mimeType, dest.fileName);
        if (createdUri == null) return null;
        OutputStream out = resolver.openOutputStream(createdUri, "w");
        if (out == null) {
            try {
                DocumentsContract.deleteDocument(resolver, createdUri);
            } catch (Exception ignored) {
            }
            return null;
        }
        String actualName = queryDisplayName(resolver, createdUri, dest.fileName);
        String displayDir = formatTreeUriDisplay(treeUriStr);
        Destination effective = new Destination(dest.category, displayDir, dest.mimeType, actualName);
        String primaryRel = extractPrimaryRelativePath(treeUriStr);
        String scanPath = null;
        if (primaryRel != null) {
            String base = Environment.getExternalStorageDirectory().getAbsolutePath();
            scanPath = primaryRel.isEmpty()
                    ? (base + "/" + actualName)
                    : (base + "/" + primaryRel + "/" + actualName);
        }
        return new PendingMedia(ctx.getApplicationContext(), resolver, createdUri, out,
                effective, true, false, scanPath);
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 在 Android MediaStore 中开启一个待写入的媒体/下载条目（IS_PENDING=1） */
    public static PendingMedia openPendingMedia(Context ctx, Destination dest) throws IOException {
        return openPendingMedia(ctx, dest, false);
    }

    private static PendingMedia openPendingMedia(Context ctx, Destination dest,
                                                 boolean customDirFallback) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        Uri collection;
        String relPath;
        if (dest.category == Category.IMAGE) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_PICTURES + "/Muse";
        } else if (dest.category == Category.VIDEO) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_MOVIES + "/Muse";
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            relPath = Environment.DIRECTORY_DOWNLOADS + "/Muse";
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, dest.fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, dest.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri itemUri = resolver.insert(collection, values);
        if (itemUri == null) {
            throw new IOException("MediaStore insert returned null");
        }
        OutputStream out = resolver.openOutputStream(itemUri);
        if (out == null) {
            try {
                resolver.delete(itemUri, null, null);
            } catch (Exception ignored) {
            }
            throw new IOException("MediaStore openOutputStream returned null");
        }
        return new PendingMedia(ctx.getApplicationContext(), resolver, itemUri, out,
                dest, false, customDirFallback, null);
    }

    /**
     * 原生后台流式下载（当跨域 CDN 因 CORS 拦截 WebView fetch 时自动走此路径）：
     * 无 CORS 限制、携带账号 Cookie/UA/Referer 与账号代理，优先写入用户配置的下载目录，未配置则写入默认 MediaStore。
     */
    public static SavedMedia downloadHttpToMediaStore(
            Context ctx,
            String urlStr,
            String userAgent,
            String referer,
            String contentDisposition,
            String mimeType,
            CookieManager cookieManager,
            String proxyUrl,
            String customTreeUri) throws IOException {
        return downloadHttpToMediaStore(ctx, urlStr, userAgent, referer, contentDisposition,
                mimeType, cookieManager, proxyUrl, customTreeUri, null);
    }

    public static SavedMedia downloadHttpToMediaStore(
            Context ctx,
            String urlStr,
            String userAgent,
            String referer,
            String contentDisposition,
            String mimeType,
            CookieManager cookieManager,
            String proxyUrl,
            String customTreeUri,
            DownloadProgressListener listener) throws IOException {
        if (listener != null && listener.isCancelled()) {
            throw new DownloadCancelledException();
        }
        ProxySpec proxySpec = parseProxy(proxyUrl);
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
        HttpURLConnection conn = null;
        boolean connected = false;
        for (int redirect = 0; redirect < 6; redirect++) {
            URL u = new URL(currentUrl);
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
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (userAgent != null && !userAgent.isEmpty()) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookieManager != null) {
                String cookies = cookieManager.getCookie(currentUrl);
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
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
            connected = true;
            break;
        }
        if (!connected || conn == null) throw new IOException("Too many redirects");

        PendingMedia pending = null;
        try {
            String respCd = conn.getHeaderField("Content-Disposition");
            String respCt = conn.getContentType();
            String mergedCd = (respCd != null && !respCd.isEmpty()) ? respCd : contentDisposition;
            String mergedMime = (respCt != null && !isGenericMime(cleanMime(respCt))) ? respCt : mimeType;
            Destination dest = resolveDestination(currentUrl, mergedCd, mergedMime, null);
            pending = openPendingDownload(ctx, dest, customTreeUri);

            byte[] buf = new byte[65536];
            long total = 0;
            long expectedTotal = conn.getContentLengthLong();
            if (listener != null) {
                listener.onProgress(0L, expectedTotal);
            }
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (listener != null && listener.isCancelled()) {
                        throw new DownloadCancelledException();
                    }
                    pending.out.write(buf, 0, n);
                    total += n;
                    if (listener != null) {
                        listener.onProgress(total, expectedTotal);
                    }
                }
            }
            if (total <= 0) {
                pending.abort();
                throw new IOException("Empty download response");
            }
            return pending.commit();
        } catch (IOException | RuntimeException e) {
            if (pending != null) pending.abort();
            throw e;
        } finally {
            conn.disconnect();
        }
    }
}
