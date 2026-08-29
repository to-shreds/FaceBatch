package com.jon.facebatch;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class DownloadSaver {
    private static final int BUFFER = 64 * 1024;
    private static final int JPEG_QUALITY = 95;

    private DownloadSaver() {
    }

    public static SavedImage save(Context context, File temporaryFile, SwapJob job,
                                  String outputFolder, int sequence) throws Exception {
        ImageType inputType = detectType(temporaryFile);
        boolean convertWebpToJpeg = "image/webp".equals(inputType.mimeType);
        ImageType outputType = convertWebpToJpeg
                ? new ImageType("image/jpeg", ".jpg") : inputType;

        String target = UriTools.baseName(job.targetName);
        String source = UriTools.baseName(job.sourceName);
        String filename = target + "__" + source + "__"
                + String.format(Locale.US, "%03d", sequence) + "_"
                + System.currentTimeMillis() + outputType.extension;
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/"
                + AppSettings.sanitizeFolder(outputFolder);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, outputType.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new Exception("Android could not create the output file in Downloads.");
        }
        boolean success = false;
        try {
            OutputStream rawOutput = resolver.openOutputStream(uri, "w");
            if (rawOutput == null) {
                throw new Exception("Android could not open the new Downloads file for writing.");
            }
            OutputStream output = new BufferedOutputStream(rawOutput, BUFFER);
            try {
                if (convertWebpToJpeg) {
                    writeWebpAsJpeg(temporaryFile, output);
                } else {
                    copyFile(temporaryFile, output);
                }
                output.flush();
            } finally {
                output.close();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            success = true;
            return new SavedImage(uri, filename, relativePath);
        } finally {
            temporaryFile.delete();
            if (!success) {
                resolver.delete(uri, null, null);
            }
        }
    }

    private static void copyFile(File file, OutputStream output) throws Exception {
        InputStream input = new BufferedInputStream(new FileInputStream(file), BUFFER);
        try {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
    }

    private static void writeWebpAsJpeg(File file, OutputStream output) throws Exception {
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (decoded == null) {
            throw new Exception("Android could not decode the WebP result for JPEG conversion.");
        }

        Bitmap jpegBitmap = decoded;
        try {
            if (decoded.hasAlpha()) {
                jpegBitmap = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(jpegBitmap);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(decoded, 0f, 0f, null);
            }
            if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw new Exception("Android could not convert the WebP result to JPEG.");
            }
        } finally {
            if (jpegBitmap != decoded) {
                jpegBitmap.recycle();
            }
            decoded.recycle();
        }
    }

    private static ImageType detectType(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        byte[] h = new byte[16];
        int n;
        try {
            n = input.read(h);
        } finally {
            input.close();
        }
        if (n >= 2 && (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8) {
            return new ImageType("image/jpeg", ".jpg");
        }
        if (n >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 0x50 && h[2] == 0x4e && h[3] == 0x47) {
            return new ImageType("image/png", ".png");
        }
        if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') {
            return new ImageType("image/gif", ".gif");
        }
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return new ImageType("image/webp", ".webp");
        }
        throw new Exception("The API response was not a recognized JPEG, PNG, GIF, or WebP image.");
    }

    private static final class ImageType {
        final String mimeType;
        final String extension;

        ImageType(String mimeType, String extension) {
            this.mimeType = mimeType;
            this.extension = extension;
        }
    }

    public static final class SavedImage {
        public final Uri uri;
        public final String filename;
        public final String relativePath;

        SavedImage(Uri uri, String filename, String relativePath) {
            this.uri = uri;
            this.filename = filename;
            this.relativePath = relativePath;
        }
    }
}
