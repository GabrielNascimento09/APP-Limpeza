package io.github.gabrielnascimento09.limpeza;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Verificador de segurança local (não é um antivírus): aponta sinais de risco nos apps instalados
 * e em arquivos APK baixados. Tudo é feito no aparelho, sem enviar nada para a internet.
 */
public class SecurityActivity extends Activity {
    private static final int MAX_DEPTH = 12;
    private static final int REQ_STORAGE = 101;

    private static final int LEVEL_LOW = 0, LEVEL_ATTENTION = 1, LEVEL_HIGH = 2;

    private static final List<String> TRUSTED_INSTALLERS = Arrays.asList(
            "com.android.vending", "com.google.android.feedback", "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks", "com.xiaomi.market", "com.huawei.appmarket", "com.amazon.venezia",
            "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "org.fdroid.fdroid");
    private static final String[] SMS_PERMS = {
            "android.permission.READ_SMS", "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS"};
    private static final String[] CALL_LOG_PERMS = {
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS"};

    static class Item {
        boolean isApkFile;
        String label = "";
        String id;
        String path;
        String version;
        String installer;
        int score = 0;
        final List<String> reasons = new ArrayList<>();

        int level() {
            return score >= 5 ? LEVEL_HIGH : (score >= 2 ? LEVEL_ATTENTION : LEVEL_LOW);
        }
    }

    private TextView deviceInfo, scanStatus;
    private Button tabApps, tabApks;
    private ListView listView;
    private ItemAdapter adapter;
    private List<Item> installed, apkFiles;
    private boolean showingApks = false;
    private boolean pendingPermission = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_security);
        deviceInfo = findViewById(R.id.deviceInfo);
        scanStatus = findViewById(R.id.scanStatus);
        tabApps = findViewById(R.id.tabApps);
        tabApks = findViewById(R.id.tabApks);
        listView = findViewById(R.id.list);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        deviceInfo.setText(deviceSummary());

        adapter = new ItemAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, rowId) -> {
            Item clicked = adapter.getItem(position);
            if (clicked != null) showDetails(clicked);
        });

        tabApps.setOnClickListener(v -> selectTab(false, !showingApks));
        tabApks.setOnClickListener(v -> selectTab(true, showingApks));

        selectTab(false, false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingPermission) {
            pendingPermission = false;
            if (hasStoragePermission() && showingApks) selectTab(true, true);
        }
    }

    // ------------------------------------------------------------ resumo do aparelho

    private String deviceSummary() {
        StringBuilder sb = new StringBuilder("ESTADO DO APARELHO\n");
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null || patch.isEmpty()) {
            sb.append("• Patch de segurança: indisponível");
        } else {
            boolean old = false;
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch);
                if (d != null) old = (System.currentTimeMillis() - d.getTime()) / 86400000L > 365;
            } catch (Exception ignored) { }
            sb.append(old ? "⚠ " : "✓ ").append("Patch de segurança: ").append(patch);
            if (old) sb.append(" (desatualizado há mais de 1 ano)");
        }
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean secure = km != null && km.isDeviceSecure();
        sb.append("\n").append(secure ? "✓ Bloqueio de tela ativado" : "⚠ Sem bloqueio de tela (use PIN, senha ou biometria)");
        int adb = 0;
        try {
            adb = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (Exception ignored) { }
        sb.append("\n").append(adb == 1 ? "⚠ Depuração USB ativada" : "✓ Depuração USB desativada");
        return sb.toString();
    }

    // ------------------------------------------------------------------- abas e lista

    private void styleTab(Button b, boolean selected) {
        b.setBackgroundResource(selected ? R.drawable.button_primary : R.drawable.button_secondary);
        b.setTextColor(selected ? 0xFFFFFFFF : 0xFF1A5FB4);
    }

    private void selectTab(boolean apks, boolean force) {
        showingApks = apks;
        styleTab(tabApps, !apks);
        styleTab(tabApks, apks);
        if (apks) {
            if (!hasStoragePermission()) {
                adapter.clear();
                scanStatus.setText("Para procurar APKs baixados, o app precisa de acesso aos arquivos.");
                askStoragePermission();
                return;
            }
            if (apkFiles == null || force) loadApks(); else showList(apkFiles);
        } else {
            if (installed == null || force) loadInstalled(); else showList(installed);
        }
    }

    private void showList(List<Item> items) {
        Collections.sort(items, (a, b) -> {
            int c = b.level() - a.level();
            if (c != 0) return c;
            c = b.score - a.score;
            if (c != 0) return c;
            return a.label.compareToIgnoreCase(b.label);
        });
        adapter.clear();
        adapter.addAll(items);
        if (items.isEmpty()) {
            scanStatus.setText(showingApks ? "Nenhum arquivo APK encontrado no armazenamento."
                    : "Nenhum app instalado por você foi encontrado.");
            return;
        }
        int high = 0, attention = 0;
        for (Item it : items) {
            int l = it.level();
            if (l == LEVEL_HIGH) high++;
            else if (l == LEVEL_ATTENTION) attention++;
        }
        scanStatus.setText(items.size() + (showingApks ? " APK(s) encontrado(s)" : " app(s) analisado(s)")
                + " • " + high + " risco alto • " + attention + " atenção. Toque num item para ver os detalhes.");
    }

    private class ItemAdapter extends ArrayAdapter<Item> {
        ItemAdapter(Context c) { super(c, 0); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_security, parent, false);
            Item it = getItem(position);
            TextView dot = row.findViewById(R.id.itemDot);
            TextView title = row.findViewById(R.id.itemTitle);
            TextView reasons = row.findViewById(R.id.itemReasons);
            if (it == null) return row;

            int lvl = it.level();
            dot.setTextColor(lvl == LEVEL_HIGH ? 0xFFD33A3A : lvl == LEVEL_ATTENTION ? 0xFFE0A100 : 0xFF1E9E5A);
            title.setText(it.label);
            String line;
            if (it.reasons.isEmpty()) {
                line = "Nenhum sinal de alerta";
            } else {
                line = it.reasons.get(0);
                if (it.reasons.size() > 1) line += " • " + it.reasons.get(1);
                if (it.reasons.size() > 2) line += " (+" + (it.reasons.size() - 2) + ")";
            }
            if (it.isApkFile && it.path != null) line = new File(it.path).getName() + " • " + line;
            reasons.setText(line);
            return row;
        }
    }

    // ------------------------------------------------- análise dos apps instalados

    private void loadInstalled() {
        adapter.clear();
        scanStatus.setText("Analisando apps instalados...");
        new Thread(() -> {
            final List<Item> list = scanInstalledApps();
            runOnUiThread(() -> {
                installed = list;
                if (!showingApks) showList(list);
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private List<Item> scanInstalledApps() {
        List<Item> result = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            return result;
        }
        Set<String> accessibility = enabledPackages(
                Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        Set<String> notifications = enabledPackages(
                Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners"));
        Set<String> admins = new HashSet<>();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> active = dpm.getActiveAdmins();
            if (active != null) for (ComponentName c : active) admins.add(c.getPackageName());
        } catch (Exception ignored) { }

        for (PackageInfo pi : packages) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (pi.packageName.equals(getPackageName())) continue;

            Item it = new Item();
            it.id = pi.packageName;
            it.path = ai.sourceDir;
            it.version = pi.versionName;
            it.installer = installerOf(pm, pi.packageName);
            try {
                it.label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Exception e) {
                it.label = pi.packageName;
            }

            // origem da instalação
            String inst = it.installer;
            if (inst == null || !TRUSTED_INSTALLERS.contains(inst)) {
                String how = inst == null ? "origem desconhecida"
                        : inst.contains("packageinstaller") ? "instalado manualmente por APK" : inst;
                addReason(it, 2, "Instalado fora de uma loja conhecida (" + how + ")");
            }

            // recursos especiais ativados
            if (accessibility.contains(pi.packageName))
                addReason(it, 4, "Serviço de acessibilidade ativo (pode ler a tela e agir por você)");
            if (notifications.contains(pi.packageName))
                addReason(it, 3, "Lê suas notificações (inclusive códigos de verificação)");
            if (admins.contains(pi.packageName))
                addReason(it, 4, "É administrador do dispositivo (difícil de desinstalar)");

            // permissões
            Set<String> requested = new HashSet<>();
            Set<String> granted = new HashSet<>();
            if (pi.requestedPermissions != null) {
                for (int i = 0; i < pi.requestedPermissions.length; i++) {
                    String p = pi.requestedPermissions[i];
                    requested.add(p);
                    if (pi.requestedPermissionsFlags != null && i < pi.requestedPermissionsFlags.length
                            && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        granted.add(p);
                    }
                }
            }
            analyzePermissions(it, requested, granted);

            if (pm.getLaunchIntentForPackage(pi.packageName) == null)
                addReason(it, 1, "Sem ícone na tela inicial");

            result.add(it);
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private String installerOf(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> enabledPackages(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null) return out;
        for (String part : raw.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null) out.add(cn.getPackageName());
        }
        return out;
    }

    private static void addReason(Item it, int weight, String text) {
        it.score += weight;
        it.reasons.add(text);
    }

    private static boolean anyOf(Set<String> set, String... names) {
        for (String n : names) if (set.contains(n)) return true;
        return false;
    }

    private static void analyzePermissions(Item it, Set<String> requested, Set<String> granted) {
        if (anyOf(granted, SMS_PERMS))
            addReason(it, 3, "Acesso a SMS (pode ler códigos de verificação)");
        if (anyOf(granted, CALL_LOG_PERMS))
            addReason(it, 2, "Acesso ao histórico de chamadas");
        if (granted.contains("android.permission.READ_CONTACTS"))
            addReason(it, 1, "Lê seus contatos");
        if (granted.contains("android.permission.RECORD_AUDIO"))
            addReason(it, 1, "Pode gravar áudio pelo microfone");
        if (granted.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
            addReason(it, 2, "Localização em segundo plano");
        if (requested.contains("android.permission.SYSTEM_ALERT_WINDOW"))
            addReason(it, 2, "Pode desenhar sobre outros apps");
        if (requested.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            addReason(it, 2, "Pode instalar outros apps");
        if (requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
            addReason(it, 1, "Acesso a todos os arquivos do aparelho");
        if (requested.size() > 40)
            addReason(it, 1, "Pede muitas permissões (" + requested.size() + ")");
    }

    // ------------------------------------------------------ APKs baixados

    private void loadApks() {
        adapter.clear();
        scanStatus.setText("Procurando arquivos APK...");
        new Thread(() -> {
            final List<Item> list = new ArrayList<>();
            try {
                File root = Environment.getExternalStorageDirectory().getCanonicalFile();
                List<File> files = new ArrayList<>();
                findApks(root, 0, files);
                PackageManager pm = getPackageManager();
                for (File f : files) list.add(analyzeApkFile(f, pm));
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                apkFiles = list;
                if (showingApks) showList(list);
            });
        }).start();
    }

    private void findApks(File dir, int depth, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (isSymlink(f)) continue;
            String name = f.getName();
            if (f.isDirectory()) {
                if (depth == 0 && name.equals("Android")) continue;
                if (name.startsWith(".") || depth >= MAX_DEPTH) continue;
                findApks(f, depth + 1, out);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                out.add(f);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Item analyzeApkFile(File f, PackageManager pm) {
        Item it = new Item();
        it.isApkFile = true;
        it.path = f.getAbsolutePath();
        it.label = f.getName();
        it.id = f.getName();

        PackageInfo pi = null;
        try {
            pi = pm.getPackageArchiveInfo(it.path, PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
        } catch (Exception ignored) { }
        if (pi == null) {
            addReason(it, 1, "Não foi possível ler este APK (arquivo corrompido ou incompleto)");
            return it;
        }
        it.id = pi.packageName;
        it.version = pi.versionName;
        if (pi.applicationInfo != null) {
            try {
                pi.applicationInfo.sourceDir = it.path;
                pi.applicationInfo.publicSourceDir = it.path;
                CharSequence l = pm.getApplicationLabel(pi.applicationInfo);
                if (l != null && l.length() > 0) it.label = l.toString();
            } catch (Exception ignored) { }
        }
        addReason(it, 1, "Arquivo APK fora de uma loja de apps");

        Set<String> requested = new HashSet<>();
        if (pi.requestedPermissions != null) requested.addAll(Arrays.asList(pi.requestedPermissions));
        analyzePermissions(it, requested, requested);

        if (pi.services != null) {
            boolean accessibility = false, listener = false;
            for (ServiceInfo si : pi.services) {
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(si.permission)) accessibility = true;
                if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(si.permission)) listener = true;
            }
            if (accessibility) addReason(it, 3, "Declara serviço de acessibilidade (pode ler a tela)");
            if (listener) addReason(it, 3, "Declara leitura de notificações");
        }
        if (pi.receivers != null) {
            for (ActivityInfo ri : pi.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)) {
                    addReason(it, 3, "Declara administrador do dispositivo");
                    break;
                }
            }
        }
        return it;
    }

    // ---------------------------------------------------------------- permissão de arquivos

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void askStoragePermission() {
        new AlertDialog.Builder(this)
                .setTitle("Permissão necessária")
                .setMessage("Para procurar APKs baixados o app precisa de acesso aos arquivos do aparelho. "
                        + "Você será levado às configurações; ao voltar, a busca começa sozinha.")
                .setPositiveButton("Continuar", (d, w) -> {
                    pendingPermission = true;
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
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------ detalhes e ações

    private static String levelName(int level) {
        return level == LEVEL_HIGH ? "● Risco alto" : level == LEVEL_ATTENTION ? "● Atenção" : "● Risco baixo";
    }

    private void showDetails(final Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append(levelName(it.level())).append("\n\n");
        if (it.id != null) sb.append("Pacote: ").append(it.id).append("\n");
        if (it.version != null) sb.append("Versão: ").append(it.version).append("\n");
        if (!it.isApkFile) {
            sb.append("Instalador: ").append(it.installer == null ? "desconhecido" : it.installer).append("\n");
        } else if (it.path != null) {
            sb.append("Arquivo: ").append(it.path).append(" (")
                    .append(formatSize(new File(it.path).length())).append(")\n");
        }
        sb.append("\nSinais de alerta:\n");
        if (it.reasons.isEmpty()) sb.append("• nenhum encontrado\n");
        else for (String r : it.reasons) sb.append("• ").append(r).append("\n");
        sb.append("\nSinais de alerta não provam que um app é malicioso; só indicam o que merece atenção. "
                + "Esta análise é feita só no aparelho e não substitui um antivírus.");

        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(it.label).setMessage(sb.toString())
                .setNegativeButton("Fechar", null);
        if (it.isApkFile) {
            b.setPositiveButton("Apagar APK", (d, w) -> confirmDeleteApk(it));
        } else {
            b.setPositiveButton("Desinstalar...", (d, w) -> openAppSettings(it));
        }
        b.show();
    }

    private void openAppSettings(Item it) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + it.id)));
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir as configurações do app.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteApk(final Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Apagar arquivo?")
                .setMessage("O arquivo será apagado permanentemente (não vai para a lixeira):\n\n" + it.path)
                .setPositiveButton("Apagar", (d, w) -> {
                    if (new File(it.path).delete()) {
                        if (apkFiles != null) apkFiles.remove(it);
                        if (showingApks && apkFiles != null) showList(apkFiles);
                        Toast.makeText(this, "Arquivo apagado.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Não foi possível apagar o arquivo.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---------------------------------------------------------------- utilitários

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
