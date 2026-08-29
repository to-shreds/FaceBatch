package com.jon.facebatch;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Size;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UriTools {
    private UriTools() {
    }

    public static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "image" : last;
    }

    public static long size(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    public static String mimeType(Context context, Uri uri) {
        String type = null;
        try {
            type = context.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (type != null && type.startsWith("image/")) {
            return type;
        }
        String lower = displayName(context, uri).toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    public static Bitmap thumbnail(Context context, Uri uri, int pixels) throws Exception {
        return context.getContentResolver().loadThumbnail(uri, new Size(pixels, pixels), new CancellationSignal());
    }

    public static List<Uri> scanImageTree(Context context, Uri treeUri, int maxImages) {
        ArrayList<Uri> images = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        scanDirectory(context.getContentResolver(), treeUri, rootDocumentId, images, visited, maxImages);
        return images;
    }

    private static void scanDirectory(ContentResolver resolver, Uri treeUri, String documentId,
                                      List<Uri> images, Set<String> visited, int maxImages) {
        if (images.size() >= maxImages || visited.contains(documentId)) {
            return;
        }
        visited.add(documentId);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    }, null, null, null);
            while (cursor != null && cursor.moveToNext() && images.size() < maxImages) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanDirectory(resolver, treeUri, childId, images, visited, maxImages);
                } else if (isImage(mime, name)) {
                    images.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static boolean isImage(String mime, String name) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif")
                || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    public static String baseName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "image";
        }
        String clean = new File(name).getName();
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot);
        }
        clean = clean.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        clean = clean.replaceAll("\\s+", "_");
        if (clean.isEmpty()) {
            clean = "image";
        }
        return clean.length() > 42 ? clean.substring(0, 42) : clean;
    }

    public static final class ImageRef {
        public final String uri;
        public final String name;

        public ImageRef(String uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }
}
