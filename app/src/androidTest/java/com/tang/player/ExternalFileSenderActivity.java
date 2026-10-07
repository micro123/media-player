package com.tang.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

/** Standalone file manager fixture, running under the test APK's own UID. */
public class ExternalFileSenderActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File directory = new File(getCacheDir(), "external-fixture");
        if (getIntent().getBooleanExtra("cleanup", false)) {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                revokeUriPermission(uriFor(file), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                file.delete();
            }
            directory.delete();
            finish();
            return;
        }
        boolean video = getIntent().getBooleanExtra("video", false);
        directory.mkdirs();
        File file = new File(directory, video ? "来自文件管理器的测试视频.mp4" : "来自文件管理器的测试歌曲.mp3");
        try (InputStream input = getAssets().open(video ? "feature-test-video.mp4" : "music-tags-test.mp3");
             OutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        Intent request = new Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), video ? "video/mp4" : "audio/mpeg")
            .setPackage("com.tang.player");
        if (!getIntent().getBooleanExtra("deny", false)) request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(request);
        finish();
    }
    private Uri uriFor(File file) {
        return new Uri.Builder().scheme("content").authority("com.tang.player.test.externalfiles")
            .appendPath("external_fixture").appendPath(file.getName()).build();
    }
}
