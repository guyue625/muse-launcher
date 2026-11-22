package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FileChooserHelperTest {

    @Test
    public void emptyOrWildcard_returnsAnyMime() {
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(null));
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(new String[0]));
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(new String[]{""}));
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(new String[]{"*/*"}));
    }

    @Test
    public void imageOnlyAccepts_returnsImageWildcard() {
        assertEquals("image/*", FileChooserHelper.resolveChooserMimeType(new String[]{"image/*"}));
        assertEquals("image/*", FileChooserHelper.resolveChooserMimeType(new String[]{"image/png", "image/jpeg"}));
        assertEquals("image/*", FileChooserHelper.resolveChooserMimeType(new String[]{"image/png, image/webp, .heic"}));
        assertEquals("image/*", FileChooserHelper.resolveChooserMimeType(new String[]{".jpg", ".png"}));
    }

    @Test
    public void videoOrAudioOnlyAccepts_returnsMatchingWildcard() {
        assertEquals("video/*", FileChooserHelper.resolveChooserMimeType(new String[]{"video/mp4", ".mov"}));
        assertEquals("audio/*", FileChooserHelper.resolveChooserMimeType(new String[]{"audio/mpeg", ".wav"}));
    }

    @Test
    public void mixedChatAttachmentAccepts_fallsBackToAnyMime() {
        // 典型 AI 聊天 + 号附件列表：图片 + PDF + Office + Markdown
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(
                new String[]{"image/*,application/pdf,.docx,.txt,.md,.csv"}));
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(
                new String[]{"image/png", "application/pdf"}));
        assertEquals("*/*", FileChooserHelper.resolveChooserMimeType(
                new String[]{".pdf", ".txt"}));
    }
}
