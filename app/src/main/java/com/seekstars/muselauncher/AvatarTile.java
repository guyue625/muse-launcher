package com.seekstars.muselauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 头像磁贴工厂。
 *
 * 文字头像：按实际字形宽度自动缩小字号（Paint.measureText），双字符不再换行/裁切，
 * CJK 与 Emoji 都按真实宽度计算；单行居中。
 * 图片头像：圆角 ImageView，CENTER_CROP 填满磁贴（与文字磁贴同圆角比例）。
 */
final class AvatarTile {

    /** 文本宽度预算（占磁贴边长比例），同时保证纵向不被裁切 */
    static final float WIDTH_BUDGET = 0.82f;

    private AvatarTile() {
    }

    /** 组合入口：图片头像优先，否则文字磁贴 */
    static View create(Context c, int sizePx, float maxTextSp, Account a) {
        Bitmap bmp = a != null && a.hasImage ? AvatarStore.load(c, a.id) : null;
        if (bmp != null) return image(c, sizePx, bmp);
        return text(c, sizePx, maxTextSp, a == null ? null : a.avatar);
    }

    static TextView text(Context c, int sizePx, float maxTextSp, String raw) {
        TextView t = new TextView(c);
        String s = normalize(raw);
        t.setText(s);
        t.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        t.setTextColor(0xFF2F7CF6);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                fitTextSizePx(c, s, maxTextSp, sizePx));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackgroundResource(R.drawable.bg_mtile);
        t.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        return t;
    }

    static ImageView image(Context c, int sizePx, Bitmap bmp) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bmp);
        // 与 bg_mtile（40dp 圆角 11dp）一致的比例裁圆角，并裁掉透明角
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(sizePx * 0.275f);
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        iv.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        return iv;
    }

    /** 头像文本归一化：去掉首尾空白，按字形（grapheme）截到 2 个，空则回退 "m" */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "m";
        s = limit(s, 2);
        return s.isEmpty() ? "m" : s;
    }

    /** 按字形截断（surrogate pair / 组合字符不劈半），比按 char 截断安全 */
    static String limit(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int end = it.first();
        for (int i = 0; i < max; i++) {
            int next = it.next();
            if (next == BreakIterator.DONE) break;
            end = next;
        }
        return s.substring(0, end);
    }

    /** 使用最终像素字号测量宽高，包含系统字体缩放及 Emoji 的回退字体行高。 */
    private static float fitTextSizePx(Context c, String s, float maxTextSp, int sizePx) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxTextSp,
                c.getResources().getDisplayMetrics());
        float budget = sizePx * WIDTH_BUDGET;
        Rect bounds = new Rect();
        while (px > 1f) {
            p.setTextSize(px);
            p.getTextBounds(s, 0, s.length(), bounds);
            StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), p,
                            Math.max(1, sizePx))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(true)
                    .build();
            if (Math.max(p.measureText(s), bounds.width()) <= budget
                    && layout.getLineCount() == 1 && layout.getHeight() <= budget) break;
            px -= 0.5f;
        }
        return Math.max(px, 1f);
    }
}
