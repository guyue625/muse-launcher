package com.seekstars.muselauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class AvatarRuntimeTest {
    private Context context;
    private Account account;

    @Before
    public void setUp() throws Exception {
        context = AndroidTestSupport.isolatedContext();
        account = new Account(System.nanoTime(), "头像回归");
    }

    @Test
    public void twoChineseCharacters_fitOneLineAtDefaultFontScale() throws Exception {
        assertTextFits("中文", 1f);
    }

    @Test
    public void twoChineseCharacters_fitAtDoubleFontScale() throws Exception {
        assertTextFits("中文", 2f);
    }

    @Test
    public void twoEmoji_fitAtDoubleFontScale() throws Exception {
        assertTextFits("😊😊", 2f);
    }

    @Test
    public void normalize_preservesCompositeEmojiAndCombiningCharacters() {
        assertEquals("👨‍👩‍👧‍👦👍🏽", AvatarTile.normalize("👨‍👩‍👧‍👦👍🏽X"));
        assertEquals("áb́", AvatarTile.normalize("áb́c"));
        assertEquals("中文", AvatarTile.normalize(" 中文第三字 "));
        assertEquals("m", AvatarTile.normalize(null));
        assertEquals("m", AvatarTile.normalize("   "));
    }

    @Test
    public void png_isCroppedToSquareAndRetainsCenter() throws Exception {
        assertPhotoImport(Bitmap.CompressFormat.PNG);
    }

    @Test
    public void jpeg_isCroppedToSquareAndRetainsCenter() throws Exception {
        assertPhotoImport(Bitmap.CompressFormat.JPEG);
    }

    @Test
    public void webp_isCroppedToSquareAndRetainsCenter() throws Exception {
        assertPhotoImport(Bitmap.CompressFormat.WEBP);
    }

    @Test
    public void svgViewBoxOnly_isRenderedToSquare() throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 120 60'>"
                + "<rect width='120' height='60' fill='red'/>"
                + "<rect x='30' width='60' height='60' fill='blue'/></svg>";
        byte[] converted = AvatarStore.readAndConvert(context,
                AndroidTestSupport.source(context, "avatar.svg", svg.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(converted);
        Bitmap bmp = BitmapFactory.decodeByteArray(converted, 0, converted.length);
        assertEquals(256, bmp.getWidth());
        assertEquals(256, bmp.getHeight());
        assertEquals(Color.BLUE, bmp.getPixel(128, 128));
    }

    @Test
    public void svgWithoutViewBox_scalesToFillSquare() throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'>"
                + "<rect width='64' height='64' fill='blue'/></svg>";
        byte[] converted = AvatarStore.readAndConvert(context,
                AndroidTestSupport.source(context, "avatar-novb.svg", svg.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(converted);
        Bitmap bmp = BitmapFactory.decodeByteArray(converted, 0, converted.length);
        assertEquals(256, bmp.getWidth());
        assertEquals(256, bmp.getHeight());
        // 无 viewBox 时也必须等比放大铺满整张 256x256（右下象限不能空白）
        assertEquals(Color.BLUE, bmp.getPixel(200, 200));
    }

    @Test
    public void invalidInput_isRejected() throws Exception {
        Uri bad = AndroidTestSupport.source(context, "bad.svg", "not an image".getBytes());
        assertNull(AvatarStore.readAndConvert(context, bad));
    }

    @Test
    public void createShortcutIcon_doesNotRecycleCachedAvatarOrBreakDisplayedImage() throws Exception {
        byte[] png = AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256);
        assertTrue(AvatarStore.persist(context, account.id, png));
        account.hasImage = true;
        Bitmap cached = AvatarStore.load(context, account.id);
        assertNotNull(cached);
        ImageView tile = AndroidTestSupport.onMain(() ->
                (ImageView) AvatarTile.create(context, 40, 20f, account));
        Bitmap icon = AvatarStore.shortcutIcon(context, account);
        assertNotNull(icon);
        assertEquals(192, icon.getWidth());
        assertFalse("Shortcut icon generation must not recycle the displayed/cache bitmap",
                cached.isRecycled());
        assertSame(cached, AvatarStore.load(context, account.id));
        AndroidTestSupport.onMain(() -> {
            AndroidTestSupport.layout(tile, 40, 40);
            tile.draw(new Canvas(Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)));
            return null;
        });
        assertFalse(cached.isRecycled());
    }

    @Test
    public void avatarReplacement_invalidatesCacheAndClearRemovesFile() throws Exception {
        assertTrue(AvatarStore.persist(context, account.id,
                AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256)));
        Bitmap first = AvatarStore.load(context, account.id);
        Bitmap green = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        green.eraseColor(Color.GREEN);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        green.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        assertTrue(AvatarStore.persist(context, account.id, bytes.toByteArray()));
        Bitmap second = AvatarStore.load(context, account.id);
        assertNotNull(second);
        assertFalse(first == second);
        assertEquals(Color.GREEN, second.getPixel(128, 128));
        assertFalse("Existing views may still own the old image", first.isRecycled());
        AvatarStore.clear(context, account.id);
        assertFalse(AvatarStore.file(context, account.id).exists());
        assertNull(AvatarStore.load(context, account.id));
    }

    @Test
    public void failedAvatarWrite_doesNotTruncateExistingFile() throws Exception {
        byte[] original = AndroidTestSupport.image(Bitmap.CompressFormat.PNG, 256, 256);
        assertTrue(AvatarStore.persist(context, account.id, original));
        File f = AvatarStore.file(context, account.id);
        assertFalse(AvatarStore.persist(context, account.id, null));
        org.junit.Assert.assertArrayEquals(original, Files.readAllBytes(f.toPath()));
    }

    private void assertPhotoImport(Bitmap.CompressFormat format) throws Exception {
        byte[] input = AndroidTestSupport.image(format, 600, 300);
        byte[] converted = AvatarStore.readAndConvert(context,
                AndroidTestSupport.source(context, "source.img", input));
        assertNotNull(converted);
        Bitmap bmp = BitmapFactory.decodeByteArray(converted, 0, converted.length);
        assertEquals(256, bmp.getWidth());
        assertEquals(256, bmp.getHeight());
        int center = bmp.getPixel(128, 128);
        assertTrue(Color.blue(center) > 240);
        assertTrue(Color.red(center) < 15);
    }

    private void assertTextFits(String text, float fontScale) throws Exception {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.fontScale = fontScale;
        Context scaled = context.createConfigurationContext(config);
        int size = Math.round(38 * scaled.getResources().getDisplayMetrics().density);
        AndroidTestSupport.onMain(() -> {
            TextView tile = AvatarTile.text(scaled, size, 20f, text);
            AndroidTestSupport.layout(tile, size, size);
            assertEquals(text, tile.getText().toString());
            assertEquals(1, tile.getLineCount());
            assertTrue("Actual rendered text must fit width at fontScale=" + fontScale,
                    tile.getPaint().measureText(text) <= size * AvatarTile.WIDTH_BUDGET + 1f);
            assertTrue("Rendered line must fit height",
                    tile.getLayout().getHeight() <= size);
            return null;
        });
    }
}
