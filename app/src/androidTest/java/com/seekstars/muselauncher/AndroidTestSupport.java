package com.seekstars.muselauncher;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

final class AndroidTestSupport {
    private AndroidTestSupport() {
    }

    static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    static Context isolatedContext() throws Exception {
        Context base = targetContext();
        File root = Files.createTempDirectory(base.getCacheDir().toPath(), "avatar-test-").toFile();
        return new ContextWrapper(base) {
            @Override
            public File getFilesDir() {
                return root;
            }
        };
    }

    static <T> T onMain(Callable<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                value.set(work.call());
            } catch (Throwable ex) {
                error.set(ex);
            }
        });
        Throwable ex = error.get();
        if (ex instanceof Exception) throw (Exception) ex;
        if (ex instanceof Error) throw (Error) ex;
        return value.get();
    }

    static Uri source(Context c, String name, byte[] bytes) throws Exception {
        File f = new File(c.getFilesDir(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return Uri.fromFile(f);
    }

    static byte[] image(Bitmap.CompressFormat format, int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.RED);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.BLUE);
        int side = Math.min(width, height);
        cv.drawRect(new Rect((width - side) / 2, (height - side) / 2,
                (width + side) / 2, (height + side) / 2), p);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bmp.compress(format, 100, bytes);
        bmp.recycle();
        return bytes.toByteArray();
    }

    static void layout(View v, int width, int height) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, width, height);
    }

    static Object invoke(Object object, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(object, args);
    }
}
