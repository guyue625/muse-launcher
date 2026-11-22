package com.seekstars.muselauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.WebChromeClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WebView 文件选择器辅助（修复 muse.ai 点击 + 上传附件失败问题）：
 * 1. `FileChooserParams.createIntent()` 在 accept 包含多个类型或 `.pdf/.docx/.md` 等后缀时会生成无效 MIME，
 *    导致系统文件选择器无法打开或文件全被置灰；且默认不带 `EXTRA_ALLOW_MULTIPLE`。
 * 2. `FileChooserParams.parseResult()` 只读取 `data.getData()`，忽略 `data.getClipData()`，
 *    多选以及部分国产 ROM 单选返回 ClipData 时会直接返回 null（误判为取消）。
 */
public final class FileChooserHelper {

    private static final Set<String> IMAGE_EXTS = new HashSet<>(Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".heic", ".heif", ".svg", ".avif"
    ));
    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            ".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi", ".3gp"
    ));
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            ".mp3", ".wav", ".m4a", ".ogg", ".aac", ".flac", ".opus", ".webm"
    ));

    private FileChooserHelper() {
    }

    /**
     * 将 acceptTypes 归一化为对所有 Android 文件选择器安全的单一 MIME：
     * - 仅包含图片类型/后缀 → image/*
     * - 仅包含视频类型/后缀 → video/*
     * - 仅包含音频类型/后缀 → audio/*
     * - 混合类型（如图片 + PDF + 文档）、未知后缀或空 → 通用任意类型，避免系统选择器把附件灰掉导致无法选中。
     */
    public static String resolveChooserMimeType(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) return "*/*";
        List<String> tokens = new ArrayList<>();
        for (String raw : acceptTypes) {
            if (raw == null) continue;
            for (String part : raw.split(",")) {
                String t = part.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) tokens.add(t);
            }
        }
        if (tokens.isEmpty()) return "*/*";

        boolean allImage = true;
        boolean allVideo = true;
        boolean allAudio = true;
        for (String t : tokens) {
            if ("*/*".equals(t)) return "*/*";
            boolean isImage = t.startsWith("image/") || IMAGE_EXTS.contains(t);
            boolean isVideo = t.startsWith("video/") || VIDEO_EXTS.contains(t);
            boolean isAudio = t.startsWith("audio/") || AUDIO_EXTS.contains(t);
            if (!isImage) allImage = false;
            if (!isVideo) allVideo = false;
            if (!isAudio) allAudio = false;
        }
        if (allImage) return "image/*";
        if (allVideo) return "video/*";
        if (allAudio) return "audio/*";
        return "*/*";
    }

    /** 构建主选文件 Intent（优先 ACTION_GET_CONTENT，支持多选与拍照捕获） */
    public static Intent buildPickerIntent(WebChromeClient.FileChooserParams params,
                                           Uri cameraOutputUri,
                                           CharSequence chooserTitle) {
        String[] accept = params != null ? params.getAcceptTypes() : null;
        String mime = resolveChooserMimeType(accept);
        boolean allowMultiple = params != null
                && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        boolean capture = params != null && params.isCaptureEnabled();

        if (capture && cameraOutputUri != null && ("image/*".equals(mime) || "*/*".equals(mime))) {
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            cam.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return cam;
        }

        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType(mime);
        if (allowMultiple) content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        content.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(content, chooserTitle);
        if (cameraOutputUri != null && ("image/*".equals(mime) || "*/*".equals(mime))) {
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            cam.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        }
        return chooser;
    }

    /** 兜底 Intent：部分精简系统缺少 GET_CONTENT 聚合器时回退到 SAF ACTION_OPEN_DOCUMENT */
    public static Intent buildFallbackDocumentIntent(WebChromeClient.FileChooserParams params) {
        String mime = resolveChooserMimeType(params != null ? params.getAcceptTypes() : null);
        boolean allowMultiple = params != null
                && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        Intent doc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        doc.addCategory(Intent.CATEGORY_OPENABLE);
        doc.setType(mime);
        if (allowMultiple) doc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        doc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return doc;
    }

    /**
     * 解析文件选择器返回结果：
     * 1. 先检查 ClipData（多选 / 部分 ROM 单选）
     * 2. 再检查 data.getData()
     * 3. 再检查拍照输出 pendingCameraUri（需已实际写入文件）
     * 4. 最后回退到系统 FileChooserParams.parseResult
     */
    public static Uri[] extractUris(int resultCode, Intent data,
                                    Uri pendingCameraUri, boolean cameraFileReady) {
        if (resultCode != Activity.RESULT_OK) return null;

        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                List<Uri> uris = new ArrayList<>(clip.getItemCount());
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
                if (!uris.isEmpty()) return uris.toArray(new Uri[0]);
            }
            Uri single = data.getData();
            if (single != null) return new Uri[]{single};
        }

        if (pendingCameraUri != null && cameraFileReady) {
            return new Uri[]{pendingCameraUri};
        }

        return WebChromeClient.FileChooserParams.parseResult(resultCode, data);
    }
}
