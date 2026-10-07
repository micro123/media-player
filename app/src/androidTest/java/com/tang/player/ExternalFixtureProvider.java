package com.tang.player;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Private provider: the player can read it only with the sender's URI grant. */
public class ExternalFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 2 || !"external_fixture".equals(uri.getPathSegments().get(0)) ||
            !("来自文件管理器的测试视频.mp4".equals(name) || "来自文件管理器的测试歌曲.mp3".equals(name))) {
            throw new IllegalArgumentException("Unknown test fixture");
        }
        return new File(getContext().getCacheDir(), "external-fixture/" + name);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return file(uri).getName().endsWith(".mp4") ? "video/mp4" : "audio/mpeg"; }
    @Override public MatrixCursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = file(uri);
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[columns.length];
        for (int i=0; i<columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
