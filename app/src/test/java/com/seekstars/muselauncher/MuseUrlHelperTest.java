package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class MuseUrlHelperTest {

    @Test
    public void isMusePage_matchesMainAndSubdomainsOnly() {
        assertTrue(MuseUrlHelper.isMusePage("https://muse.ai/"));
        assertTrue(MuseUrlHelper.isMusePage("https://muse.ai/spaces"));
        assertTrue(MuseUrlHelper.isMusePage("https://MUSE.AI/thread/123?x=1"));
        assertTrue(MuseUrlHelper.isMusePage("https://app.muse.ai/chat"));
        assertTrue(MuseUrlHelper.isMusePage("http://muse.ai."));

        assertFalse(MuseUrlHelper.isMusePage("https://notmuse.ai/"));
        assertFalse(MuseUrlHelper.isMusePage("https://muse.ai.evil.com/"));
        assertFalse(MuseUrlHelper.isMusePage("https://example.com/muse.ai"));
        assertFalse(MuseUrlHelper.isMusePage("about:blank"));
        assertFalse(MuseUrlHelper.isMusePage("javascript:void(0)"));
        assertFalse(MuseUrlHelper.isMusePage(""));
        assertFalse(MuseUrlHelper.isMusePage(null));
    }

    @Test
    public void isExternalPage_trueOnlyForNonMuseHttpPages() {
        assertTrue(MuseUrlHelper.isExternalPage("https://github.com/seekstars306/muse-launcher"));
        assertTrue(MuseUrlHelper.isExternalPage("http://example.com/article"));
        assertTrue(MuseUrlHelper.isExternalPage("https://notmuse.ai/"));

        assertFalse(MuseUrlHelper.isExternalPage("https://muse.ai/"));
        assertFalse(MuseUrlHelper.isExternalPage("https://muse.ai/space/demo"));
        assertFalse(MuseUrlHelper.isExternalPage("about:blank"));
        assertFalse(MuseUrlHelper.isExternalPage("blob:https://muse.ai/123"));
        assertFalse(MuseUrlHelper.isExternalPage("mailto:test@example.com"));
        assertFalse(MuseUrlHelper.isExternalPage(""));
        assertFalse(MuseUrlHelper.isExternalPage(null));
    }

    @Test
    public void isTrustedWebPermissionOrigin_trustsMuseAndMetaUserContent() {
        assertTrue(MuseUrlHelper.isTrustedWebPermissionOrigin("https://muse.ai/"));
        assertTrue(MuseUrlHelper.isTrustedWebPermissionOrigin("https://www.muse.ai"));
        assertTrue(MuseUrlHelper.isTrustedWebPermissionOrigin("https://meta.ai"));
        assertTrue(MuseUrlHelper.isTrustedWebPermissionOrigin("https://abc.s.metaaiusercontent.com"));
        assertTrue(MuseUrlHelper.isTrustedWebPermissionOrigin("https://foo.s.ecto1usercontent.com"));

        assertFalse(MuseUrlHelper.isTrustedWebPermissionOrigin("https://evil-muse.ai"));
        assertFalse(MuseUrlHelper.isTrustedWebPermissionOrigin("https://muse.ai.attacker.com"));
        assertFalse(MuseUrlHelper.isTrustedWebPermissionOrigin("https://example.com"));
        assertFalse(MuseUrlHelper.isTrustedWebPermissionOrigin(null));
    }

    @Test
    public void isWebViewInternalScheme_allowsWebAndIframeSchemes() {
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("http"));
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("HTTPS"));
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("about"));
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("blob"));
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("data"));
        assertTrue(MuseUrlHelper.isWebViewInternalScheme("javascript"));

        assertFalse(MuseUrlHelper.isWebViewInternalScheme("tel"));
        assertFalse(MuseUrlHelper.isWebViewInternalScheme("mailto"));
        assertFalse(MuseUrlHelper.isWebViewInternalScheme("intent"));
        assertFalse(MuseUrlHelper.isWebViewInternalScheme("market"));
        assertFalse(MuseUrlHelper.isWebViewInternalScheme(""));
        assertFalse(MuseUrlHelper.isWebViewInternalScheme(null));
    }

    @Test
    public void findLastMuseHistoryStep_returnsNearestPreviousMuseEntry() {
        assertEquals(-2, MuseUrlHelper.findLastMuseHistoryStep(
                Arrays.asList(
                        "https://muse.ai/",
                        "https://muse.ai/thread/42",
                        "https://example.com/a",
                        "https://example.com/b"
                ), 3));

        assertEquals(-1, MuseUrlHelper.findLastMuseHistoryStep(
                Arrays.asList("https://muse.ai/", "https://example.com/a"), 1));

        assertEquals(0, MuseUrlHelper.findLastMuseHistoryStep(
                Arrays.asList("https://example.com/a", "https://other.com/b"), 1));

        assertEquals(0, MuseUrlHelper.findLastMuseHistoryStep(
                Collections.singletonList("https://example.com/a"), 0));

        assertEquals(0, MuseUrlHelper.findLastMuseHistoryStep(Collections.emptyList(), 0));
    }
}
