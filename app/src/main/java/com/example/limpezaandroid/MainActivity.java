package com.example.limpezaandroid;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class MainActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(com.example.limpezaandroid.R.layout.activity_main);
        status = findViewById(com.example.limpezaandroid.R.id.status);
        Button clean = findViewById(com.example.limpezaandroid.R.id.cleanButton);
        Button storage = findViewById(com.example.limpezaandroid.R.id.storageButton);
        updateStatus();
        clean.setOnClickListener(v -> {
            deleteContents(getCacheDir());
            File external = getExternalCacheDir();
            if (external != null) deleteContents(external);
            Toast.makeText(this, "Cache do aplicativo limpo!", Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        storage.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void updateStatus() {
        long size = folderSize(getCacheDir());
        File external = getExternalCacheDir();
        if (external != null) size += folderSize(external);
        status.setText("Cache deste aplicativo: " + formatSize(size));
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteContents(f);
            f.delete();
        }
    }

    private static long folderSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.isDirectory() ? folderSize(f) : f.length();
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
