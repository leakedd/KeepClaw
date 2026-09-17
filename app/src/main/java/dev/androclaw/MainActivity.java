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
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
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
                    android.graphics.Insets b = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                    v.setPadding(b.left, b.top, b.right, b.bottom);
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
        });
        web.setBackgroundColor(0xFF0A0A0E);
        setState(R.color.muted, R.string.status_idle, null);

        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startCore(); }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopCore(); }
        });
        findViewById(R.id.btn_reload).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { waitAndLoad(); }
        });
        findViewById(R.id.btn_battery).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestBatteryExemption(); }
        });
        findViewById(R.id.btn_wipe).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmWipe(); }
        });

        askNotifications();
        startCore();
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

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
