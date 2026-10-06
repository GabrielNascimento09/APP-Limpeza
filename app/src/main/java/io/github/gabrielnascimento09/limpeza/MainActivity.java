package io.github.gabrielnascimento09.limpeza;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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

    // estados da etapa de arquivos temporários no relatório final
    private static final int TEMP_NOT_CHECKED = 0, TEMP_NONE = 1, TEMP_DECLINED = 2, TEMP_DELETED = 3;

    private TextView status;
    private TextView freeSpace;
    private Button cleanAllButton, storageButton;
    private boolean busy = false;
    private boolean pendingPermission = false;

    private static class ScanResult {
        final List<File> files = new ArrayList<>();
        final List<File> dirs = new ArrayList<>(); // filhos antes dos pais
        long bytes = 0;
    }

    private static class Report {
        long ownBytes = 0;
        long systemBytes = 0;
        boolean systemSupported = true;
        String systemError = null;
        int tempState = TEMP_NOT_CHECKED;
        int tempFiles = 0, tempDirs = 0;
        long tempBytes = 0;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        freeSpace = findViewById(R.id.freeSpace);
        cleanAllButton = findViewById(R.id.cleanAllButton);
        storageButton = findViewById(R.id.storageButton);
        updateStatus();

        cleanAllButton.setOnClickListener(v -> onCleanAllClicked());
        storageButton.setOnClickListener(v -> openStorageSettings());
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
        // voltou da tela de permissão: continua a limpeza automaticamente
        if (pendingPermission) {
            pendingPermission = false;
            if (hasStoragePermission()) runCleanAll(true);
        }
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
        cleanAllButton.setEnabled(!value);
        storageButton.setEnabled(!value);
    }

    private void openStorageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ------------------------------------------------------- botão "Limpar tudo"

    private void onCleanAllClicked() {
        if (busy) return;
        if (hasStoragePermission()) {
            runCleanAll(true);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Limpar tudo")
                .setMessage("Posso limpar o cache dos apps sem nenhuma permissão extra.\n\n"
                        + "Para também apagar arquivos temporários, preciso de acesso aos arquivos do aparelho. "
                        + "Se você permitir, a limpeza continua sozinha quando voltar para o app.")
                .setPositiveButton("Permitir acesso", (d, w) -> {
                    pendingPermission = true;
                    requestStoragePermission();
                })
                .setNegativeButton("Só limpar cache", (d, w) -> runCleanAll(false))
                .show();
    }

    private void runCleanAll(final boolean withTemp) {
        if (busy) return;
        setBusy(true);
        Toast.makeText(this, "Limpando...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Report report = new Report();

            // 1) cache do próprio app
            File ext = getExternalCacheDir();
            report.ownBytes = folderSize(getCacheDir()) + (ext != null ? folderSize(ext) : 0);
            deleteContents(getCacheDir());
            if (ext != null) deleteContents(ext);

            // 2) cache dos outros apps (pede ao sistema, Android 8+)
            report.systemSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
            if (report.systemSupported) {
                long before = freeBytes();
                try {
                    requestSystemCacheClear();
                } catch (Exception e) {
                    report.systemError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                report.systemBytes = Math.max(0, freeBytes() - before);
            }

            // 3) procura arquivos temporários (a exclusão só acontece após confirmação)
            final ScanResult scan = withTemp ? new ScanResult() : null;
            if (scan != null) {
                try {
                    File root = Environment.getExternalStorageDirectory().getCanonicalFile();
                    scanDir(root, scan, 0, System.currentTimeMillis());
                } catch (Exception ignored) { }
                report.tempState = (scan.files.isEmpty() && scan.dirs.isEmpty()) ? TEMP_NONE : TEMP_DECLINED;
            }

            runOnUiThread(() -> {
                setBusy(false);
                updateStatus();
                if (scan != null && report.tempState == TEMP_DECLINED) {
                    askToDeleteTemp(report, scan);
                } else {
                    showReport(report);
                }
            });
        }).start();
    }

    private void askToDeleteTemp(final Report report, final ScanResult scan) {
        String msg = "Cache já limpo: " + formatSize(report.ownBytes + report.systemBytes) + " liberados.\n\n"
                + "Também encontrei " + scan.files.size() + " arquivo(s) temporário(s) ("
                + formatSize(scan.bytes) + ") e " + scan.dirs.size() + " pasta(s) vazia(s).\n\n"
                + "Inclui: .tmp, .log, miniaturas (.thumbnails) e downloads incompletos com mais de 24h. "
                + "Fotos e vídeos não são apagados.\n\n"
                + "Os itens apagados não vão para a lixeira. Deseja apagar?";
        new AlertDialog.Builder(this).setTitle("Arquivos temporários").setMessage(msg)
                .setPositiveButton("Apagar", (d, w) -> deleteTempFiles(report, scan))
                .setNegativeButton("Agora não", (d, w) -> showReport(report))
                .setOnCancelListener(d -> showReport(report))
                .show();
    }

    private void deleteTempFiles(final Report report, final ScanResult scan) {
        setBusy(true);
        new Thread(() -> {
            int deletedFiles = 0, deletedDirs = 0;
            long freed = 0;
            for (File f : scan.files) {
                long len = f.length();
                if (f.delete()) { deletedFiles++; freed += len; }
            }
            for (File d : scan.dirs) {
                if (d.delete()) deletedDirs++;
            }
            report.tempFiles = deletedFiles;
            report.tempDirs = deletedDirs;
            report.tempBytes = freed;
            report.tempState = TEMP_DELETED;
            runOnUiThread(() -> {
                setBusy(false);
                updateStatus();
                showReport(report);
            });
        }).start();
    }

    private void showReport(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cache deste app: ").append(formatSize(r.ownBytes)).append("\n");

        if (!r.systemSupported) {
            sb.append("Cache de outros apps: exige Android 8 ou superior\n");
        } else if (r.systemBytes > 0) {
            sb.append("Cache de outros apps: ").append(formatSize(r.systemBytes)).append("\n");
        } else if (r.systemError != null) {
            sb.append("Cache de outros apps: o sistema não liberou nada agora\n");
        } else {
            sb.append("Cache de outros apps: nada a liberar\n");
        }

        switch (r.tempState) {
            case TEMP_DELETED:
                sb.append("Arquivos temporários: ").append(formatSize(r.tempBytes)).append(" (")
                        .append(r.tempFiles).append(" arquivo(s), ").append(r.tempDirs).append(" pasta(s))\n");
                break;
            case TEMP_NONE:
                sb.append("Arquivos temporários: nenhum encontrado\n");
                break;
            case TEMP_DECLINED:
                sb.append("Arquivos temporários: mantidos\n");
                break;
            default:
                sb.append("Arquivos temporários: não verificados (sem permissão)\n");
        }

        long total = r.ownBytes + r.systemBytes + r.tempBytes;
        sb.append("\nTotal liberado: ").append(formatSize(total));
        if (r.systemSupported) {
            sb.append("\n\nO Android decide quanto do cache dos outros apps pode apagar; em alguns aparelhos ele libera só parte.");
        }
        new AlertDialog.Builder(this).setTitle("Limpeza concluída").setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------- cache de todos os apps (via sistema)

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

    // --------------------------------- permissão e arquivos temporários

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
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
