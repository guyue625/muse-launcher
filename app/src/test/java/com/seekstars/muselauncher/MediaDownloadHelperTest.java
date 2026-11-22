package com.seekstars.muselauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MediaDownloadHelperTest {

    @Test
    public void imageDownload_routesToPicturesMuse() {
        MediaDownloadHelper.Destination d = MediaDownloadHelper.resolveDestination(
                "blob:https://muse.ai/12345",
                "attachment; filename=\"poster.png\"",
                "image/png",
                "Muse_test");
        assertEquals(MediaDownloadHelper.Category.IMAGE, d.category);
        assertEquals("Pictures/Muse", d.relativePath);
        assertEquals("image/png", d.mimeType);
        assertEquals("poster.png", d.fileName);
    }

    @Test
    public void videoDownload_routesToMoviesMuse() {
        MediaDownloadHelper.Destination d = MediaDownloadHelper.resolveDestination(
                "https://cdn.example.com/media/clip.mp4?token=abc",
                null,
                "application/octet-stream",
                "Muse_test");
        assertEquals(MediaDownloadHelper.Category.VIDEO, d.category);
        assertEquals("Movies/Muse", d.relativePath);
        assertEquals("video/mp4", d.mimeType);
        assertEquals("clip.mp4", d.fileName);
    }

    @Test
    public void documentAndSvg_routeToDownloadMuse() {
        MediaDownloadHelper.Destination pdf = MediaDownloadHelper.resolveDestination(
                "https://muse.ai/export",
                "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf",
                "application/pdf",
                "Muse_test");
        assertEquals(MediaDownloadHelper.Category.DOWNLOAD, pdf.category);
        assertEquals("Download/Muse", pdf.relativePath);
        assertEquals("报告.pdf", pdf.fileName);

        MediaDownloadHelper.Destination svg = MediaDownloadHelper.resolveDestination(
                "blob:https://muse.ai/svg1",
                "attachment; filename=\"chart.svg\"",
                "image/svg+xml",
                "Muse_test");
        assertEquals(MediaDownloadHelper.Category.DOWNLOAD, svg.category);
        assertEquals("Download/Muse", svg.relativePath);
        assertEquals("chart.svg", svg.fileName);
    }

    @Test
    public void blobWithoutFilename_usesTimestampStemAndMimeExtension() {
        MediaDownloadHelper.Destination d = MediaDownloadHelper.resolveDestination(
                "blob:https://muse.ai/abcdef-1234",
                null,
                "video/mp4; codecs=avc1",
                "Muse_20261122_190000");
        assertEquals(MediaDownloadHelper.Category.VIDEO, d.category);
        assertEquals("Movies/Muse", d.relativePath);
        assertEquals("Muse_20261122_190000.mp4", d.fileName);
    }

    @Test
    public void sanitizeFilename_stripsTraversalAndFixesBinExtension() {
        MediaDownloadHelper.Destination d = MediaDownloadHelper.resolveDestination(
                "https://muse.ai/dl",
                "attachment; filename=\"../../evil/photo.bin\"",
                "image/jpeg",
                "Muse_test");
        assertEquals("photo.jpg", d.fileName);
        assertEquals(MediaDownloadHelper.Category.IMAGE, d.category);
    }

    @Test
    public void parseDataUrl_decodesBase64AndPlain() {
        byte[] raw = new byte[]{1, 2, 3, 4, 5};
        String b64 = Base64.getEncoder().encodeToString(raw);
        MediaDownloadHelper.DataUrlPayload p1 = MediaDownloadHelper.parseDataUrl("data:image/png;base64," + b64);
        assertNotNull(p1);
        assertEquals("image/png", p1.mimeType);
        assertArrayEquals(raw, p1.bytes);

        MediaDownloadHelper.DataUrlPayload p2 = MediaDownloadHelper.parseDataUrl("data:text/plain,hello%20world");
        assertNotNull(p2);
        assertEquals("text/plain", p2.mimeType);
        assertEquals("hello world", new String(p2.bytes, StandardCharsets.UTF_8));

        assertNull(MediaDownloadHelper.parseDataUrl("https://muse.ai/"));
    }

    @Test
    public void parseProxy_handlesHttpAndSocksWithAuth() {
        MediaDownloadHelper.ProxySpec http = MediaDownloadHelper.parseProxy("http://u1:p%401@1.2.3.4:3129");
        assertNotNull(http);
        assertEquals(Proxy.Type.HTTP, http.type);
        assertEquals("1.2.3.4", http.host);
        assertEquals(3129, http.port);
        assertEquals("u1", http.user);
        assertEquals("p@1", http.pass);

        MediaDownloadHelper.ProxySpec socks = MediaDownloadHelper.parseProxy("socks5://127.0.0.1:1080");
        assertNotNull(socks);
        assertEquals(Proxy.Type.SOCKS, socks.type);
        assertEquals("127.0.0.1", socks.host);
        assertEquals(1080, socks.port);
        assertNull(socks.user);

        MediaDownloadHelper.ProxySpec bare = MediaDownloadHelper.parseProxy("127.0.0.1:7890");
        assertNotNull(bare);
        assertEquals(Proxy.Type.HTTP, bare.type);
        assertEquals(7890, bare.port);

        assertNull(MediaDownloadHelper.parseProxy(""));
        assertNull(MediaDownloadHelper.parseProxy(null));
        assertTrue(MediaDownloadHelper.buildRevokeObjectUrlGuardScript().contains("revokeObjectURL"));
    }

    @Test
    public void formatTreeUriDisplay_andPrimaryRelativePath() {
        String dl = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FMyMuse";
        assertEquals("Download/MyMuse", MediaDownloadHelper.formatTreeUriDisplay(dl));
        assertEquals("Download/MyMuse", MediaDownloadHelper.extractPrimaryRelativePath(dl));

        String root = "content://com.android.externalstorage.documents/tree/primary%3A";
        assertEquals("内部存储根目录", MediaDownloadHelper.formatTreeUriDisplay(root));
        assertEquals("", MediaDownloadHelper.extractPrimaryRelativePath(root));

        String sd = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMuseFiles";
        assertEquals("外置存储/MuseFiles", MediaDownloadHelper.formatTreeUriDisplay(sd));
        assertNull(MediaDownloadHelper.extractPrimaryRelativePath(sd));

        assertEquals("", MediaDownloadHelper.formatTreeUriDisplay(""));
        assertEquals("", MediaDownloadHelper.formatTreeUriDisplay(null));
    }

    @Test
    public void formatBytes_andProgressPercent() {
        assertEquals("0 B", MediaDownloadHelper.formatBytes(-5));
        assertEquals("512 B", MediaDownloadHelper.formatBytes(512));
        assertEquals("240 KB", MediaDownloadHelper.formatBytes(240L * 1024L));
        assertEquals("12.5 MB", MediaDownloadHelper.formatBytes((long) (12.5 * 1024 * 1024)));
        assertEquals("1.50 GB", MediaDownloadHelper.formatBytes((long) (1.5 * 1024 * 1024 * 1024)));

        assertEquals(-1, MediaDownloadHelper.computeProgressPercent(100, 0));
        assertEquals(0, MediaDownloadHelper.computeProgressPercent(0, 1000));
        assertEquals(50, MediaDownloadHelper.computeProgressPercent(500, 1000));
        assertEquals(100, MediaDownloadHelper.computeProgressPercent(1200, 1000));

        String script = MediaDownloadHelper.buildWebFetchDownloadScript("tok1", "blob:https://muse.ai/1");
        assertTrue(script.contains("isCancelled"));
        assertTrue(script.contains("getReader"));
    }
}
