package dev.keepclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Relance le gateway au démarrage du téléphone (et après un déverrouillage direct-boot).
 * Android 15+ interdit les types d'FGS dataSync/mediaProcessing lancés depuis BOOT_COMPLETED ;
 * « specialUse » reste autorisé — c'est précisément notre cas.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) return;

        Log.i("KeepClaw", "boot reçu : " + action + " -> démarrage du gateway");
        Intent i = new Intent(context, CoreService.class).setAction(CoreService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            Log.w("KeepClaw", "démarrage au boot refusé : " + e.getMessage());
        }
    }
}
