package com.seekstars.muselauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.LruCache;

import com.caverock.androidsvg.SVG;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 图片/SVG 头像导入、裁方及原子保存；缓存位图可以被多个界面借用，不能手动回收。 */
final class AvatarStore {
    private static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SVG_BYTES = 1024 * 1024;
    private static final int MAX_DECODE = 1024;
    private static final int OUT_SIZE = 256;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    private AvatarStore() {
    }

    static File file(Context c, long id) {
        return new File(new File(c.getFilesDir(), "avatars"), "acct_" + id + ".png");
    }

    static Bitmap load(Context c, long id) {
        File f = file(c, id);
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(f.getAbsolutePath());
            if (hit != null && !hit.isRecycled()) return hit;
        }
        byte[] raw = savedBytes(c, id);
        Bitmap bmp = raw != null ? decode(raw, OUT_SIZE) : null;
        if (bmp != null) {
            synchronized (CACHE) {
                CACHE.put(f.getAbsolutePath(), bmp);
            }
        }
        return bmp;
    }

    static byte[] savedBytes(Context c, long id) {
        try (InputStream in = new AtomicFile(file(c, id)).openRead()) {
            return readAll(in);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    static void evict(long id) {
        synchronized (CACHE) {
            String suffix = File.separator + "acct_" + id + ".png";
            for (String key : CACHE.snapshot().keySet()) {
                if (key.endsWith(suffix)) CACHE.remove(key);
            }
        }
    }

    static void clear(Context c, long id) {
        synchronized (CACHE) {
            CACHE.remove(file(c, id).getAbsolutePath());
        }
        new AtomicFile(file(c, id)).delete();
    }

    /** 系统内存紧张或退到后台时清空位图缓存（磁盘文件保留，下次按需重新解码） */
    static void trimMemory() {
        synchronized (CACHE) {
            CACHE.evictAll();
        }
    }

    /** 只返回标准化 PNG 草稿，不保存选择器 URI，不需要长期媒体权限。 */
    static byte[] readAndConvert(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            Bitmap bmp = decode(readAll(in), OUT_SIZE);
            if (bmp == null) return null;
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                return bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                        ? bytes.toByteArray() : null;
            } finally {
                bmp.recycle();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 失败回滚旧文件；null/损坏的草稿在打开目标文件之前拒绝。 */
    static boolean persist(Context c, long id, byte[] png) {
        if (png == null || png.length == 0 || png.length > MAX_INPUT_BYTES) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth != OUT_SIZE || bounds.outHeight != OUT_SIZE) return false;
        AtomicFile atomic = new AtomicFile(file(c, id));
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(png);
            atomic.finishWrite(out);
            out = null;
            // finishWrite 对“重命名失败”只记日志不抛异常（如目标被目录占用），
            // 必须读回校验，否则会出现假成功并静默丢头像。
            if (!java.util.Arrays.equals(png, savedBytes(c, id))) return false;
            evict(id);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (out != null) atomic.failWrite(out);
        }
    }

    static Bitmap shortcutIcon(Context c, Account a) {
        final int size = 192;
        Bitmap img = a.hasImage ? load(c, a.id) : null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        float radius = size * 0.275f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFFFFFFFF);
        cv.drawRoundRect(new RectF(0, 0, size, size), radius, radius, bg);
        if (img != null) {
            Bitmap square = centerCropSquare(img, size);
            Paint src = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            src.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            cv.drawRoundRect(new RectF(0, 0, size, size), radius, radius, src);
            if (square != img) square.recycle();
            return out;
        }
        String text = AvatarTile.normalize(a.avatar);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF2F7CF6);
        tp.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        float px = 108f;
        tp.setTextSize(px);
        while (px > 1f && (tp.measureText(text) > size * AvatarTile.WIDTH_BUDGET
                || tp.getFontMetrics().descent - tp.getFontMetrics().ascent
                > size * AvatarTile.WIDTH_BUDGET)) {
            tp.setTextSize(px -= 1f);
        }
        Paint.FontMetrics fm = tp.getFontMetrics();
        cv.drawText(text, (size - tp.measureText(text)) / 2f,
                (size - fm.descent - fm.ascent) / 2f, tp);
        return out;
    }

    private static Bitmap decode(byte[] raw, int size) {
        if (raw == null || raw.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                if ((long) bounds.outWidth * bounds.outHeight > 100_000_000L) return null;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                if (src == null) return null;
                Bitmap result = centerCropSquare(src, size);
                if (result != src) src.recycle();
                return result;
            }
            return decodeSvg(raw, size);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Bitmap decodeSvg(byte[] raw, int size) {
        if (raw.length > MAX_SVG_BYTES) return null;
        String xml = new String(raw, StandardCharsets.UTF_8);
        String lower = xml.toLowerCase(Locale.ROOT);
        // SVG 是静态矢量图，不允许实体、嵌入位图或外部资源；避免解析与位图解压炸弹。
        if (lower.contains("<!doctype") || lower.contains("<!entity")
                || lower.contains("<image") || lower.contains(":image")
                || xml.chars().filter(ch -> ch == '<').count() > 4000) return null;
        try {
            SVG.setInternalEntitiesEnabled(false);
            SVG svg = SVG.getFromInputStream(new ByteArrayInputStream(raw));
            float w = svg.getDocumentWidth();
            float h = svg.getDocumentHeight();
            if (!Float.isFinite(w) || !Float.isFinite(h) || w <= 0 || h <= 0) {
                RectF viewBox = svg.getDocumentViewBox();
                if (viewBox == null || viewBox.isEmpty()) return null;
                w = viewBox.width();
                h = viewBox.height();
            }
            if (!Float.isFinite(w) || !Float.isFinite(h) || w <= 0 || h <= 0) return null;
            if (svg.getDocumentViewBox() == null) {
                svg.setDocumentViewBox(0f, 0f, w, h);
            }
            float scale = size / Math.max(w, h);
            int width = Math.max(1, Math.round(w * scale));
            int height = Math.max(1, Math.round(h * scale));
            svg.setDocumentWidth(width);
            svg.setDocumentHeight(height);
            Bitmap src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new Canvas(src));
            Bitmap result = centerCropSquare(src, size);
            if (result != src) src.recycle();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / sample > MAX_DECODE) sample *= 2;
        return sample;
    }

    /** 用 Canvas 直接裁剪缩放，始终不回收借用的源位图。 */
    private static Bitmap centerCropSquare(Bitmap src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) return src;
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect(x, y, x + side, y + side),
                new Rect(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || bytes.size() + n > MAX_INPUT_BYTES)
                throw new IOException("头像文件过大或导入已取消");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }
}
