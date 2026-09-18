package dev.keepclaw;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KeepClaw — héberge le moteur + la console web dans un foreground service
 * « façon VPN/Spotify » : notification permanente, redémarrage START_STICKY, arrêt manuel
 * depuis la notification (visible sur l'écran verrouillé) ou depuis la tuile du volet rapide.
 *
 * Deux binaires Go sont embarqués dans jniLibs (extraits par PackageManager dans
 * nativeLibraryDir, la seule zone exec-able pour une app targetSdk >= 29) :
 *   libconsole.so  -> console (serveur web + spawn du moteur)          port 18800
 *   libcore.so  -> moteur (agent + gateway), passé via KEEPCLAW_BINARY
 */
public class CoreService extends Service {

    public static final String ACTION_START  = "dev.keepclaw.action.START";
    public static final String ACTION_STOP   = "dev.keepclaw.action.STOP";
    public static final String ACTION_TOGGLE = "dev.keepclaw.action.TOGGLE";

    public static final int    PORT = 18800;
    public static final String BASE = "http://127.0.0.1:" + PORT;

    private static final String TAG    = "KeepClaw";
    private static final String CH_ID  = "keepclaw";
    private static final int    NOTIF  = 4711;

    private static volatile boolean sRunning = false;
    private static volatile int     sPid     = -1;
    private static volatile String  sState   = "arrêté";
    private static volatile String  sAgent   = "off";   // working | idle | off
    private static volatile String  sModel   = "";

    private volatile boolean statusLoop = false;

    public static String agent() { return sAgent; }
    public static String model() { return sModel; }

    private Process proc;
    private Thread  pump;

    public static boolean isRunning() { return sRunning; }
    public static String  state()     { return sState; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent == null || intent.getAction() == null) ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopCore();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action) && sRunning) {
            stopCore();
            return START_NOT_STICKY;
        }

        goForeground("Démarrage…");
        if (!sRunning) startCore();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        statusLoop = false;
        killCore();
        super.onDestroy();
    }

    /** On veut survivre au swipe de la tâche : on repasse en foreground. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (sRunning) goForeground("Gateway actif · " + BASE);
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------ démarrage

    private void startCore() {
        sRunning = true;
        setState("démarrage");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final File home = new File(getFilesDir(), "core");
                    if (!home.isDirectory() && !home.mkdirs()) throw new IOException("mkdir " + home);
                    final File logFile = new File(getFilesDir(), "gateway.log");

                    final String nativeDir = getApplicationInfo().nativeLibraryDir;
                    final File launcher = new File(nativeDir, "libconsole.so");
                    final File core     = new File(nativeDir, "libcore.so");
                    if (!launcher.isFile()) throw new IOException("binaire launcher absent : " + launcher);
                    if (!core.isFile())     throw new IOException("binaire core absent : " + core);
                    makeExecutable(launcher);
                    makeExecutable(core);

                    seedIfEmpty(home);

                    final List<String> cmd = new ArrayList<String>();
                    if (new File("/system/bin/setsid").exists()) cmd.add("/system/bin/setsid");
                    cmd.add(launcher.getAbsolutePath());
                    cmd.add("-no-browser");
                    cmd.add("-host"); cmd.add("127.0.0.1");
                    cmd.add("-port"); cmd.add(String.valueOf(PORT));

                    final ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(home);
                    pb.redirectErrorStream(true);
                    final Map<String, String> env = pb.environment();
                    env.put("HOME", getFilesDir().getAbsolutePath());
                    env.put("KEEPCLAW_HOME", home.getAbsolutePath());
                    env.put("KEEPCLAW_BINARY", core.getAbsolutePath());
                    env.put("KEEPCLAW_BUILTIN_SKILLS", new File(getFilesDir(), "skills").getAbsolutePath());
                    env.put("KEEPCLAW_LAUNCHER_HOST", "127.0.0.1");
                    env.put("TMPDIR", getCacheDir().getAbsolutePath());
                    env.put("PATH", "/system/bin:/system/xbin");

                    proc = pb.start();
                    sPid = pidOf(proc);
                    Log.i(TAG, "launcher pid=" + sPid);

                    pump = new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                BufferedReader r = new BufferedReader(
                                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                                FileWriter w = new FileWriter(logFile, false);
                                String line;
                                while ((line = r.readLine()) != null) {
                                    Log.i(TAG, "launcher> " + line);
                                    w.write(line + "\n");
                                    w.flush();
                                }
                                w.close();
                            } catch (Exception ignored) { }
                        }
                    }, "core-log");
                    pump.setDaemon(true);
                    pump.start();

                    boolean up = false;
                    for (int i = 0; i < 80; i++) {
                        if (httpGet(BASE + "/api/auth/status", 1500) != null) { up = true; break; }
                        if (proc != null && !proc.isAlive() && i > 6) break;
                        Thread.sleep(500);
                    }

                    if (up) {
                        setState("actif · " + BASE);
                        goForeground(getString(R.string.notif_starting));
                        autoStartGateway();
                        startStatusLoop();
                    } else {
                        setState("démarrage lent — voir journal");
                        goForeground("KeepClaw · démarrage lent");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "startCore a échoué", e);
                    sRunning = false;
                    setState("échec : " + e.getMessage());
                    goForeground("Échec : " + e.getMessage());
                }
            }
        }, "core-start").start();
    }

    /**
     * PackageManager extrait les jniLibs en 0644 : le bit d'exécution manque, alors que la zone
     * (nativeLibraryDir) est la seule exécutable pour une app targetSdk >= 29. On le force.
     */
    private void makeExecutable(File f) {
        try {
            if (!f.canExecute()) {
                android.system.Os.chmod(f.getAbsolutePath(), 00755);
                Log.i(TAG, "chmod 0755 -> " + f.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "chmod impossible sur " + f.getName() + " : " + t);
        }
        Log.i(TAG, "binaire " + f.getName() + " : exec=" + f.canExecute()
                + " read=" + f.canRead() + " taille=" + f.length()
                + " chemin=" + f.getAbsolutePath());
    }

    /**
     * Réinitialisation complète, utilisable MÊME si le mot de passe de la console est perdu :
     * arrêt du service, effacement du dossier de travail (config.json, .security.yml, sessions,
     * carnet du gateway, historique, workspace) puis des cookies de la WebView. Au redémarrage,
     * le service ré-sème le config.json d'exemple : la console repart en « première ouverture ».
     * Bloquant (~1 s, le temps que le launcher meure) — à appeler hors du thread UI.
     */
    public static void wipeAll(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, CoreService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stopService : " + t);
        }
        sRunning = false;
        try { Thread.sleep(900); } catch (InterruptedException ignored) { }

        File dir = new File(ctx.getFilesDir(), "core");
        deleteTree(dir);
        // ancien emplacement (avant le renommage) : purge pour que la réinitialisation soit complète
        deleteTree(new File(ctx.getFilesDir(), "picoclaw"));
        // et le dossier historique du moteur (skills / sessions)
        deleteTree(new File(ctx.getFilesDir(), ".keepclaw"));
        deleteTree(new File(ctx.getFilesDir(), ".picoclaw"));

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable t) {
            Log.w(TAG, "cookies : " + t);
        }
        Log.i(TAG, "réinitialisation : " + dir.getAbsolutePath() + " supprimé (existe=" + dir.exists() + ")");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) Log.w(TAG, "suppression impossible : " + f.getAbsolutePath());
    }

    /**
     * Démarre le gateway sans intervention humaine : on réutilise le cookie de session que la
     * WebView a obtenu lors du premier login dans la console. Sans session -> 401, et c'est le
     * bouton « Start Gateway » de l'UI qui prend le relais.
     */
    private void autoStartGateway() {
        new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 3; attempt++) {
                    String cookie = null;
                    try {
                        cookie = CookieManager.getInstance().getCookie(BASE);
                    } catch (Throwable t) {
                        Log.w(TAG, "CookieManager indisponible : " + t);
                    }
                    if (cookie != null && !cookie.isEmpty()) {
                        String st = httpGet(BASE + "/api/gateway/status", 4000, cookie);
                        if (st != null && st.contains("\"gateway_status\":\"running\"")) {
                            setState("gateway actif · " + BASE);
                            return;
                        }
                        String r = httpPost(BASE + "/api/gateway/start", 9000, cookie);
                        boolean refused = r == null || r.contains("unauthorized") || r.contains("\"error\"");
                        Log.i(TAG, "démarrage auto du gateway -> " + (refused ? "refusé (pas de session)" : r.trim()));
                        setState(refused
                                ? "actif · " + BASE + " · gateway à lancer (1 tap)"
                                : "gateway actif · " + BASE);
                        return;
                    }
                    try { Thread.sleep(2500); } catch (InterruptedException e) { return; }
                }
                Log.i(TAG, "aucune session console : démarrer le gateway depuis l'UI (bouton Start Gateway)");
            }
        }, "core-gwstart").start();
    }

    /**
     * Boucle de statut (5 s) : interroge la console locale pour savoir si le gateway
     * tourne et si l'agent travaille (activité de session récente), puis met à jour
     * la notification (visible sur l'écran verrouillé) et la pastille de l'app.
     */
    private void startStatusLoop() {
        if (statusLoop) return;
        statusLoop = true;
        new Thread(new Runnable() {
            @Override public void run() {
                long lastActivity = 0;
                String lastUpdated = null;
                while (statusLoop && sRunning) {
                    String cookie = cookie();
                    String st = cookie == null ? null : httpGet(BASE + "/api/gateway/status", 4000, cookie);
                    boolean running = st != null && st.contains("\"gateway_status\":\"running\"");
                    String model = jsonString(st, "config_default_model");
                    if (model != null) sModel = model;

                    if (running && cookie != null) {
                        String sessions = httpGet(BASE + "/api/sessions", 5000, cookie);
                        String updated = jsonString(sessions, "updated");
                        if (updated != null && !updated.equals(lastUpdated)) {
                            if (lastUpdated != null) lastActivity = System.currentTimeMillis();
                            lastUpdated = updated;
                        }
                    }

                    long now = System.currentTimeMillis();
                    boolean working = running && lastActivity > 0 && (now - lastActivity) < 20000;

                    if (!running) {
                        sAgent = "off";
                        updateNotif(getString(R.string.notif_gateway_off));
                    } else if (working) {
                        sAgent = "working";
                        updateNotif(withModel(getString(R.string.notif_working)));
                    } else {
                        sAgent = "idle";
                        updateNotif(withModel(getString(R.string.notif_idle)));
                    }

                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                }
            }
        }, "agent-status").start();
    }

    private String withModel(String label) {
        String m = sModel;
        return (m == null || m.isEmpty()) ? label : label + " · " + m;
    }

    private String cookie() {
        try {
            String c = CookieManager.getInstance().getCookie(BASE);
            return (c == null || c.isEmpty()) ? null : c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Extrait la première valeur chaîne d'un champ JSON (sans dépendance). */
    private static String jsonString(String json, String field) {
        if (json == null) return null;
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private void updateNotif(String text) {
        if (!sRunning) return;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF, buildNotif(text));
        } catch (Throwable t) {
            Log.w(TAG, "notif : " + t);
        }
    }

    /** Première exécution : copie le config.json d'exemple (squelette vide, AUCUN modèle —
     *  ils n'apparaissent qu'après la saisie d'une clé dans l'onglet Providers). */
    private void seedIfEmpty(File home) {
        File cfg = new File(home, "config.json");
        if (cfg.exists()) return;
        try {
            InputStream in = getAssets().open("seed/config.json");
            OutputStream out = new java.io.FileOutputStream(cfg);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            Log.i(TAG, "config semé : " + cfg.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "pas de seed (" + e.getMessage() + "), le launcher créera la config");
        }
    }

    // ------------------------------------------------------------------ arrêt

    private void stopCore() {
        setState("arrêt");
        statusLoop = false;
        sAgent = "off";
        sModel = "";
        // 1) arrêt propre du gateway demandé au launcher
        httpPost(BASE + "/api/gateway/stop", 2500);
        // 2) on tue l'arbre de process (core compris)
        killCore();
        sRunning = false;
        sPid = -1;
        setState("arrêté");
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void killCore() {
        int pid = sPid > 0 ? sPid : (proc != null ? pidOf(proc) : -1);
        if (pid > 0) killTree(pid);
        if (proc != null) {
            try { proc.destroy(); } catch (Exception ignored) { }
            proc = null;
        }
    }

    /** Tue récursivement (enfants d'abord) : la console spawn le moteur. */
    private void killTree(int root) {
        try {
            List<Integer> pids = new ArrayList<Integer>();
            pids.add(root);

            Process p = new ProcessBuilder("/system/bin/sh", "-c", "ps -A -o PID,PPID 2>/dev/null || ps -A").start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Map<Integer, Integer> parentOf = new HashMap<Integer, Integer>();
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 2) continue;
                try { parentOf.put(Integer.parseInt(t[0]), Integer.parseInt(t[1])); } catch (Exception ignored) { }
            }
            r.close();

            boolean added = true;
            while (added) {
                added = false;
                for (Map.Entry<Integer, Integer> e : parentOf.entrySet()) {
                    if (pids.contains(e.getValue()) && !pids.contains(e.getKey())) {
                        pids.add(e.getKey());
                        added = true;
                    }
                }
            }
            Collections.reverse(pids);
            for (int id : pids) {
                try { new ProcessBuilder("/system/bin/sh", "-c", "kill -9 " + id).start(); } catch (Exception ignored) { }
            }
            Log.i(TAG, "tué : " + pids);
        } catch (Exception e) {
            Log.w(TAG, "killTree : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ foreground / notification

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "Gateway KeepClaw",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Statut du moteur hébergé par KeepClaw");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, CoreService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop), "Arrêter", psi).build();

        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_core)
                .setContentTitle("KeepClaw")
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(stopAction)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)   // commande depuis l'écran verrouillé
                .setCategory(Notification.CATEGORY_SERVICE);

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void goForeground(String text) {
        Notification n = buildNotif(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF, n);
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF, n);
    }

    private void setState(String s) {
        sState = s;
        Log.i(TAG, "état : " + s);
    }

    // ------------------------------------------------------------------ HTTP local

    private String httpGet(String url, int timeoutMs) { return httpGet(url, timeoutMs, null); }

    private String httpGet(String url, int timeoutMs, String cookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String httpPost(String url, int timeoutMs) { return httpPost(url, timeoutMs, null); }

    private String httpPost(String url, int timeoutMs, String cookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            OutputStream os = c.getOutputStream();
            os.write("{}".getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            return code + " " + readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        } catch (Exception e) {
            return "erreur " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readAll(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** PID d'un process Java (reflexion : java.lang.Process n'expose pas pid() avant API 26… on reste portable). */
    private int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            if (v instanceof Integer) return (Integer) v;
        } catch (Exception ignored) { }
        return -1;
    }
}
