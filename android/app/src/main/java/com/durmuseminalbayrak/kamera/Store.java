package com.durmuseminalbayrak.kamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Galeri / İndirilenler'e kaydetme (Android 10+ izin gerektirmez). */
final class Store {
    private Store() { }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static Uri saveJpeg(Context c, byte[] jpg, String prefix) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, prefix + "_" + stamp() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Kamera");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        return write(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv, jpg, MediaStore.Images.Media.IS_PENDING);
    }

    static Uri savePdf(Context c, byte[] pdf, String prefix) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, prefix + "_" + stamp() + ".pdf");
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kamera");
        cv.put(MediaStore.Downloads.IS_PENDING, 1);
        return write(c, MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv, pdf, MediaStore.Downloads.IS_PENDING);
    }

    private static Uri write(Context c, Uri coll, ContentValues cv, byte[] data, String pendingKey) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri uri = cr.insert(coll, cv);
        if (uri == null) throw new IOException("Kayıt oluşturulamadı");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Dosya açılamadı");
            os.write(data);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(pendingKey, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    static Uri newVideo(Context c) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "VID_" + stamp() + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Kamera");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        return c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
    }

    static void publishVideo(Context c, Uri u) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.IS_PENDING, 0);
        c.getContentResolver().update(u, cv, null, null);
    }

    static void delete(Context c, Uri u) {
        try { c.getContentResolver().delete(u, null, null); } catch (Exception ignored) { }
    }
}
