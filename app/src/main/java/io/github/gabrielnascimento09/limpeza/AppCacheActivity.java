package io.github.gabrielnascimento09.limpeza;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lista os apps com o tamanho do cache de cada um e abre a tela do app no Android,
 * onde dá para limpar só o cache daquele app. (Um app comum não pode limpar o cache
 * de outros apps diretamente; o Android exige que o próprio usuário faça isso.)
 */
public class AppCacheActivity extends Activity {
    static class AppItem {
        String label = "";
        String pkg = "";
        long cacheBytes = -1; // -1 = desconhecido
    }

    private TextView info, summary;
    private Button usageButton;
    private ListView listView;
    private AppAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_app_cache);
        info = findViewById(R.id.info);
        summary = findViewById(R.id.summary);
        usageButton = findViewById(R.id.usageButton);
        listView = findViewById(R.id.list);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        usageButton.setOnClickListener(v -> openUsageAccessSettings());

        adapter = new AppAdapter(this);
        adapter.setNotifyOnChange(false);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, rowId) -> {
            AppItem a = adapter.getItem(position);
            if (a != null) openAppInfo(a);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        load(); // atualiza os tamanhos ao voltar da tela do app
    }

    // ------------------------------------------------------------------ carregamento

    private void load() {
        final boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        final boolean access = supported && hasUsageAccess();
        if (!supported) {
            info.setText("Mostrar o tamanho do cache de cada app exige Android 8 ou superior. "
                    + "Toque num app para abrir a tela dele e limpar o cache.");
            usageButton.setVisibility(View.GONE);
        } else if (!access) {
            info.setText("Para mostrar quanto cache cada app guarda, o Android exige a permissão \"Acesso de uso\". "
                    + "Ela só permite ler tamanhos de armazenamento e fica nas configurações do aparelho. "
                    + "Sem ela, a lista aparece sem tamanhos.");
            usageButton.setVisibility(View.VISIBLE);
        } else {
            info.setText("Toque num app para abrir a tela dele. Lá, vá em Armazenamento e toque em Limpar cache. "
                    + "Ao voltar, o tamanho é atualizado.");
            usageButton.setVisibility(View.GONE);
        }

        new Thread(() -> {
            final List<AppItem> list = scanApps(access);
            long total = 0;
            for (AppItem a : list) if (a.cacheBytes > 0) total += a.cacheBytes;
            final long totalCache = total;
            runOnUiThread(() -> {
                adapter.clear();
                adapter.addAll(list);
                adapter.notifyDataSetChanged();
                if (list.isEmpty()) summary.setText("Nenhum app encontrado.");
                else if (access) summary.setText(list.size() + " apps • cache total: " + formatSize(totalCache));
                else summary.setText(list.size() + " apps");
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private List<AppItem> scanApps(boolean access) {
        List<AppItem> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return out;
        }
        StorageStatsManager ssm = null;
        if (access && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ssm = (StorageStatsManager) getSystemService(Context.STORAGE_STATS_SERVICE);
        }
        for (PackageInfo pi : packages) {
            if (pi.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(pi.packageName) == null) continue; // só apps com ícone
            AppItem a = new AppItem();
            a.pkg = pi.packageName;
            try {
                a.label = String.valueOf(pm.getApplicationLabel(pi.applicationInfo));
            } catch (Exception e) {
                a.label = pi.packageName;
            }
            if (ssm != null) {
                try {
                    a.cacheBytes = queryCache(ssm, pi.packageName);
                } catch (Exception e) {
                    a.cacheBytes = -1;
                }
            }
            out.add(a);
        }
        final boolean bySize = ssm != null;
        Collections.sort(out, (x, y) -> {
            if (bySize && x.cacheBytes != y.cacheBytes) return Long.compare(y.cacheBytes, x.cacheBytes);
            return x.label.compareToIgnoreCase(y.label);
        });
        return out;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private long queryCache(StorageStatsManager ssm, String pkg) throws Exception {
        return ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()).getCacheBytes();
    }

    @SuppressWarnings("deprecation")
    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_ALLOWED) return true;
            return mode == AppOpsManager.MODE_DEFAULT
                    && checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------ ações

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppInfo(AppItem a) {
        try {
            Toast.makeText(this, "Na tela do app, toque em Armazenamento e depois em Limpar cache.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg)));
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir as configurações do app.", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppAdapter extends ArrayAdapter<AppItem> {
        AppAdapter(Context c) { super(c, 0); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_app_cache, parent, false);
            AppItem a = getItem(position);
            if (a == null) return row;
            ((TextView) row.findViewById(R.id.appName)).setText(a.label);
            ((TextView) row.findViewById(R.id.appPkg)).setText(a.pkg);
            ((TextView) row.findViewById(R.id.appCache)).setText(a.cacheBytes < 0 ? "" : formatSize(a.cacheBytes));
            return row;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
