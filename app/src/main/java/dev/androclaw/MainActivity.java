package dev.androclaw;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * L'UI d'AndroClaw = la console web (moteur + console Go) dans une WebView locale,
 * plus une barre de contrôle minimale (démarrer / arrêter / recharger / exemption batterie).
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextView status;
    private TextView detail;
    private View dot;
    private View loading;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE_CHOOSER = 100;
    private boolean chatFullscreen = false;

    /** Pont JS <-> natif : la console demande le plein écran du chat (masquer l'en-tête
     *  Android + barres système), pour discuter sans chrome autour. */
    private class ShellBridge {
        @android.webkit.JavascriptInterface
        public void setFullscreen(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() { applyChatFullscreen(on); }
            });
        }
    }

    private void applyChatFullscreen(boolean on) {
        chatFullscreen = on;
        View header = findViewById(R.id.header);
        View divider = findViewById(R.id.header_divider);
        header.setVisibility(on ? View.GONE : View.VISIBLE);
        divider.setVisibility(on ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (on) {
                    c.hide(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(android.view.WindowInsets.Type.statusBars()
                            | android.view.WindowInsets.Type.navigationBars());
                }
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(on
                    ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                       | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                       | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    private final Runnable pollAgent = new Runnable() {
        @Override public void run() {
            refreshAgentState();
            if (polling) ui.postDelayed(this, 2000);
        }
    };

    /** Touche de finition appliquee a la console web : barres de defilement fines,
     *  selection teintee, fond identique au shell. Idempotent. */
    private static final String CONSOLE_SKIN =
            "(function(){if(document.getElementById('ac-skin'))return;"
            + "var s=document.createElement('style');s.id='ac-skin';"
            + "s.textContent='::-webkit-scrollbar{width:8px;height:8px}"
            + "::-webkit-scrollbar-thumb{background:#26262F;border-radius:8px}"
            + "::-webkit-scrollbar-track{background:transparent}"
            + "::selection{background:rgba(108,198,255,.30)}"
            + "html{background:#0A0A0E}"
            + "input,textarea,select{border-radius:12px}';"
            + "document.head.appendChild(s);})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        dot    = findViewById(R.id.dot);
        web    = findViewById(R.id.web);

        // Android 15+ (targetSdk 35+) impose l'edge-to-edge : sans ceci, le bandeau passe SOUS la
        // barre d'état (heure/batterie) et devient partiellement intouchable. On réserve les insets
        // système (haut = barre d'état, bas = barre de navigation).
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    // Barres système + encoche, et surtout le clavier (ime) : sur Android 15+
                    // edge-to-edge, adjustResize ne redimensionne plus la fenêtre — c'est à
                    // l'app de réserver l'espace du clavier, sinon le champ de saisie du chat
                    // passe dessous.
                    android.graphics.Insets b = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                    android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                    v.setPadding(b.left, b.top, b.right, Math.max(b.bottom, ime.bottom));
                } else {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
        root.requestApplyInsets();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(true);
        loading = findViewById(R.id.loading);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                loading.setVisibility(View.GONE);
                v.evaluateJavascript(CONSOLE_SKIN, null);
            }

            /** La console est locale : tout lien externe (docs, GitHub, marketplace) part
             *  dans le navigateur système au lieu de remplacer la console dans la WebView. */
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                Uri u = req.getUrl();
                String host = u.getHost();
                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        // Pièces jointes : le chat (et l'installation de skills) utilisent <input type="file">.
        // Sans WebChromeClient.onShowFileChooser, le sélecteur ne s'ouvre jamais.
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                                       FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    String[] accept = params.getAcceptTypes();
                    if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) {
                        i.setType(accept[0]);
                    } else {
                        i.setType("*/*");
                    }
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i, getString(R.string.attach_pick)),
                            REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.setBackgroundColor(0xFF0A0A0E);
        web.addJavascriptInterface(new ShellBridge(), "AndroidShell");
        setState(R.color.muted, R.string.status_idle, null);

        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreMenu(v); }
        });

        askNotifications();
        maybeAskBatteryExemption();
        startCore();
    }

    /** Au tout premier lancement : demande l'exemption d'optimisation batterie.
     *  Sans elle, Android peut geler/tuer le service en arrière-plan et le gateway
     *  devient injoignable dès que l'écran est éteint. Une seule fois (mémorisé). */
    private void maybeAskBatteryExemption() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("asked_battery", false)) return;
        prefs.edit().putBoolean("asked_battery", true).apply();
        ui.postDelayed(new Runnable() {
            @Override public void run() { requestBatteryExemption(); }
        }, 1500);
    }

    /**
     * Met a jour la pastille d'etat (point colore + libelle) et la ligne secondaire.
     * detailText == null -> « moteur hors ligne ».
     */
    private void setState(int colorRes, int labelRes, String detailText) {
        status.setText(labelRes);
        detail.setText(detailText != null ? detailText : getString(R.string.detail_offline));
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(colorRes, getTheme())));
    }

    private void startCore() {
        Intent i = new Intent(this, CoreService.class).setAction(CoreService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        waitAndLoad();
    }

    private void stopCore() {
        startService(new Intent(this, CoreService.class).setAction(CoreService.ACTION_STOP));
        setState(R.color.muted, R.string.state_stopped, null);
        Toast.makeText(this, "AndroClaw arrêté", Toast.LENGTH_SHORT).show();
    }

    /** Attend que le port 18800 réponde (max ~40 s) puis charge la console. */
    private void waitAndLoad() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                setState(R.color.warn, R.string.state_starting, null);
                ((TextView) findViewById(R.id.loading_text)).setText(R.string.loading_console);
                loading.setVisibility(View.VISIBLE);
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                boolean up = false;
                for (int i = 0; i < 80; i++) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                new java.net.URL(CoreService.BASE + "/api/auth/status").openConnection();
                        c.setConnectTimeout(1200);
                        c.setReadTimeout(1200);
                        if (c.getResponseCode() > 0) { up = true; c.disconnect(); break; }
                    } catch (Exception ignored) { }
                    try { Thread.sleep(500); } catch (Exception ignored) { }
                }
                final boolean ok = up;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            setState(R.color.ok, R.string.state_running, CoreService.BASE.replace("http://", ""));
                            web.loadUrl(CoreService.BASE);
                        } else {
                            setState(R.color.danger, R.string.state_unreachable, null);
                            ((TextView) findViewById(R.id.loading_text)).setText(R.string.loading_failed);
                            loading.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }, "core-wait").start();
    }

    /** Réinitialisation : mot de passe console, clés, canaux, conversations, carnet du gateway. */
    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wipe_title)
                .setMessage(R.string.wipe_msg)
                .setPositiveButton(R.string.wipe_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { wipe(); }
                })
                .setNegativeButton(R.string.wipe_cancel, null)
                .show();
    }

    private void wipe() {
        setState(R.color.warn, R.string.state_wiping, null);
        loading.setVisibility(View.VISIBLE);
        web.loadUrl("about:blank");
        new Thread(new Runnable() {
            @Override public void run() {
                CoreService.wipeAll(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() { startCore(); }   // relance sur le thread UI (touche des vues)
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, "AndroClaw réinitialisé", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "core-wipe").start();
    }

    private void requestBatteryExemption() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (Build.VERSION.SDK_INT >= 23 && pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Déjà exempté", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Réglages batterie indisponibles", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 10);
        }
    }

    /** Toutes les actions du shell (moteur + maintenance) dans un menu ⋯ compact. */
    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, R.string.menu_start);
        m.getMenu().add(0, 2, 1, R.string.menu_stop);
        m.getMenu().add(0, 3, 2, R.string.menu_reload);
        m.getMenu().add(0, 4, 3, R.string.menu_battery);
        m.getMenu().add(0, 5, 4, R.string.menu_wipe);
        m.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) { startCore(); return true; }
                if (item.getItemId() == 2) { stopCore(); return true; }
                if (item.getItemId() == 3) { waitAndLoad(); return true; }
                if (item.getItemId() == 4) { requestBatteryExemption(); return true; }
                if (item.getItemId() == 5) { confirmWipe(); return true; }
                return false;
            }
        });
        m.show();
    }

    /**
     * Reflète en direct l'état de l'agent (travaille / prêt / hors ligne) et le modèle
     * actif, tels que suivis par le service (qui alimente aussi la notification).
     */
    private void refreshAgentState() {
        if (status == null || loading == null || loading.getVisibility() == View.VISIBLE) return;
        String agent = CoreService.agent();
        String model = CoreService.model();
        int color;
        int label;
        if ("working".equals(agent)) {
            color = R.color.ok;
            label = R.string.agent_working;
        } else if ("idle".equals(agent)) {
            color = R.color.ok;
            label = R.string.agent_idle;
        } else if (CoreService.isRunning()) {
            color = R.color.warn;
            label = R.string.agent_off;
        } else {
            color = R.color.muted;
            label = R.string.status_idle;
        }
        status.setText(label);
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(color, getTheme())));
        String host = CoreService.BASE.replace("http://", "");
        detail.setText((model == null || model.isEmpty()) ? host : model + " · " + host);
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        ui.post(pollAgent);
    }

    @Override
    protected void onPause() {
        polling = false;
        ui.removeCallbacks(pollAgent);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (chatFullscreen) {
            // Retour = sortir du plein écran (et prévenir la console).
            web.evaluateJavascript("window.__acSetFullscreen && window.__acSetFullscreen(false)", null);
            applyChatFullscreen(false);
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
