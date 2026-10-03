package io.github.gabrielnascimento09.limpeza;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 100;
    private static final long MIN_AGE_MS = 24L * 60 * 60 * 1000; // só mexe em itens com mais de 24h
    private static final int MAX_DEPTH = 12;
    private static final String[] TEMP_EXT = {".tmp", ".temp", ".log", ".crdownload", ".part", ".partial", ".download"};
    private static final List<String> PROTECTED_TOP = Arrays.asList(
            "DCIM", "Pictures", "Movies", "Music", "Download", "Documents", "Podcasts",
            "Ringtones", "Alarms", "Notifications", "Audiobooks", "Recordings");

    private TextView status;
    private TextView freeSpace;
    private Button freeButton, tempButton, cleanButton, storageButton;
    private boolean busy = false;

    private static class ScanResult {
        final List<File> files = new ArrayList<>();
        final List<File> dirs = new ArrayList<>(); // filhos antes dos pais
        long bytes = 0;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        freeSpace = findViewById(R.id.freeSpace);
        freeButton = findViewById(R.id.freeButton);
        tempButton = findViewById(R.id.tempButton);
        cleanButton = findViewById(R.id.cleanButton);
        storageButton = findViewById(R.id.storageButton);
        updateStatus();

        freeButton.setOnClickListener(v -> freeSystemSpace());
        tempButton.setOnClickListener(v -> startTempCleanup());

        cleanButton.setOnClickListener(v -> {
            deleteContents(getCacheDir());
            File external = getExternalCacheDir();
            if (external != null) deleteContents(external);
            Toast.makeText(this, "Cache do aplicativo limpo!", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        storageButton.setOnClickListener(v -> openStorageSettings());
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    // ---------------------------------------------------------------- status

    private void updateStatus() {
        long size = folderSize(getCacheDir());
        File external = getExternalCacheDir();
        if (external != null) size += folderSize(external);
        status.setText("Cache deste aplicativo: " + formatSize(size));
        try {
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            freeSpace.setText("Espaço livre no aparelho: " + formatSize(s.getAvailableBytes())
                    + " de " + formatSize(s.getTotalBytes()));
        } catch (Exception e) {
            freeSpace.setText("");
        }
    }

    private long freeBytes() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        freeButton.setEnabled(!value);
        tempButton.setEnabled(!value);
        cleanButton.setEnabled(!value);
        storageButton.setEnabled(!value);
    }

    private void openStorageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ------------------------------------- 1) liberar cache de todos os apps

    private void freeSystemSpace() {
        if (busy) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Este recurso exige Android 8.0 ou superior. Abrindo as configurações de armazenamento.",
                    Toast.LENGTH_LONG).show();
            openStorageSettings();
            return;
        }
        setBusy(true);
        final long before = freeBytes();
        new Thread(() -> {
            String error = null;
            try {
                requestSystemCacheClear();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final long after = freeBytes();
            final String err = error;
            runOnUiThread(() -> {
                setBusy(false);
                updateStatus();
                String msg;
                if (after > before) {
                    msg = "Espaço liberado: " + formatSize(after - before) + "\n\nAntes: " + formatSize(before)
                            + "\nDepois: " + formatSize(after);
                } else if (err != null) {
                    msg = "O sistema não conseguiu liberar mais espaço agora.\n\nDetalhe: " + err;
                } else {
                    msg = "Não havia mais cache para o sistema liberar.";
                }
                msg += "\n\nO Android decide o que pode apagar. Em alguns aparelhos ele libera só parte do cache.";
                new AlertDialog.Builder(this).setTitle("Resultado").setMessage(msg)
                        .setPositiveButton("OK", null).show();
            });
        }).start();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void requestSystemCacheClear() throws IOException {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        UUID uuid = sm.getUuidForPath(getFilesDir());
        long target = sm.getAllocatableBytes(uuid);
        IOException last = null;
        // Pede ao sistema todo o espaço possível; ele apaga caches de outros apps para atender.
        for (int i = 0; i < 8 && target > 0; i++) {
            try {
                sm.allocateBytes(uuid, target);
                return;
            } catch (IOException e) {
                last = e;
                target = (long) (target * 0.85);
            }
        }
        if (last != null) throw last;
    }

    // --------------------------------- 2) arquivos temporários no armazenamento

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void startTempCleanup() {
        if (busy) return;
        if (!hasStoragePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissão necessária")
                    .setMessage("Para encontrar arquivos temporários o app precisa de acesso aos arquivos do aparelho. "
                            + "Você será levado às configurações. Depois de permitir, volte e toque novamente.")
                    .setPositiveButton("Continuar", (d, w) -> requestStoragePermission())
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        scanTempFiles();
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanTempFiles();
        }
    }

    private void scanTempFiles() {
        setBusy(true);
        Toast.makeText(this, "Procurando arquivos temporários...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final ScanResult result = new ScanResult();
            try {
                File root = Environment.getExternalStorageDirectory().getCanonicalFile();
                scanDir(root, result, 0, System.currentTimeMillis());
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                setBusy(false);
                if (result.files.isEmpty() && result.dirs.isEmpty()) {
                    Toast.makeText(this, "Nenhum arquivo temporário encontrado.", Toast.LENGTH_LONG).show();
                    return;
                }
                String msg = "Encontrei " + result.files.size() + " arquivo(s) temporário(s) ("
                        + formatSize(result.bytes) + ") e " + result.dirs.size() + " pasta(s) vazia(s).\n\n"
                        + "Inclui: .tmp, .log, miniaturas (.thumbnails) e downloads incompletos com mais de 24h.\n\n"
                        + "Os itens apagados não vão para a lixeira. Deseja apagar?";
                new AlertDialog.Builder(this).setTitle("Limpar arquivos temporários").setMessage(msg)
                        .setPositiveButton("Apagar", (d, w) -> deleteTempFiles(result))
                        .setNegativeButton("Cancelar", null).show();
            });
        }).start();
    }

    /** Retorna true se a pasta ficará vazia depois de apagar os arquivos marcados. */
    private boolean scanDir(File dir, ScanResult r, int depth, long now) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        boolean emptyAfter = true;
        for (File f : children) {
            if (isSymlink(f)) { emptyAfter = false; continue; }
            String name = f.getName();
            if (f.isDirectory()) {
                if (depth == 0 && name.equals("Android")) { emptyAfter = false; continue; }
                if (name.equals(".thumbnails")) {
                    collectAll(f, r, 0);
                    emptyAfter = false;
                    continue;
                }
                if (name.startsWith(".") || depth >= MAX_DEPTH) { emptyAfter = false; continue; }
                boolean childEmpty = scanDir(f, r, depth + 1, now);
                boolean old = now - f.lastModified() > MIN_AGE_MS;
                boolean protectedDir = depth == 0 && PROTECTED_TOP.contains(name);
                if (childEmpty && old && !protectedDir) {
                    r.dirs.add(f);
                } else {
                    emptyAfter = false;
                }
            } else if (isTempName(name) && now - f.lastModified() > MIN_AGE_MS) {
                r.files.add(f);
                r.bytes += f.length();
            } else {
                emptyAfter = false;
            }
        }
        return emptyAfter;
    }

    private void collectAll(File dir, ScanResult r, int depth) {
        File[] children = dir.listFiles();
        if (children == null || depth > MAX_DEPTH) return;
        for (File f : children) {
            if (isSymlink(f)) continue;
            if (f.isDirectory()) collectAll(f, r, depth + 1);
            else { r.files.add(f); r.bytes += f.length(); }
        }
    }

    private void deleteTempFiles(final ScanResult result) {
        setBusy(true);
        new Thread(() -> {
            int deletedFiles = 0, deletedDirs = 0;
            long freed = 0;
            for (File f : result.files) {
                long len = f.length();
                if (f.delete()) { deletedFiles++; freed += len; }
            }
            for (File d : result.dirs) {
                if (d.delete()) deletedDirs++;
            }
            final int nf = deletedFiles, nd = deletedDirs;
            final long fr = freed;
            runOnUiThread(() -> {
                setBusy(false);
                updateStatus();
                new AlertDialog.Builder(this).setTitle("Concluído")
                        .setMessage("Apaguei " + nf + " arquivo(s) (" + formatSize(fr) + ") e " + nd + " pasta(s) vazia(s).")
                        .setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private static boolean isTempName(String name) {
        String lower = name.toLowerCase();
        for (String ext : TEMP_EXT) if (lower.endsWith(ext)) return true;
        return false;
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }

    // --------------------------------------------------------------- utilitários

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
