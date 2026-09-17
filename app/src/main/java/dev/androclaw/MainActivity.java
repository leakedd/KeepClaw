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
 * L'UI d'AndroClaw = la console web picoclaw (launcher) dans une WebView locale,
 * plus une barre de contrôle minimale (démarrer / arrêter / recharger / exemption batterie).
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
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
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF101014);

        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startClaw(); }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopClaw(); }
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
        startClaw();
    }

    private void startClaw() {
        Intent i = new Intent(this, ClawService.class).setAction(ClawService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        waitAndLoad();
    }

    private void stopClaw() {
        startService(new Intent(this, ClawService.class).setAction(ClawService.ACTION_STOP));
        status.setText("arrêté");
        Toast.makeText(this, "AndroClaw arrêté", Toast.LENGTH_SHORT).show();
    }

    /** Attend que le port 18800 réponde (max ~40 s) puis charge la console. */
    private void waitAndLoad() {
        status.setText("démarrage…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean up = false;
                for (int i = 0; i < 80; i++) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                                new java.net.URL(ClawService.BASE + "/api/auth/status").openConnection();
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
                            status.setText("actif · " + ClawService.BASE);
                            web.loadUrl(ClawService.BASE);
                        } else {
                            status.setText("gateway injoignable — voir le journal");
                        }
                    }
                });
            }
        }, "claw-wait").start();
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
        status.setText("réinitialisation…");
        web.loadUrl("about:blank");
        new Thread(new Runnable() {
            @Override public void run() {
                ClawService.wipeAll(MainActivity.this);
                startClaw();   // relance : la console repart en « première ouverture »
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, "AndroClaw réinitialisé", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "claw-wipe").start();
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
