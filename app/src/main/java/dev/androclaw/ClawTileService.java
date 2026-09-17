package dev.androclaw;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Tuile « AndroClaw » du volet rapide : une slide, un tap = démarrage ou coupure immédiate.
 */
public class ClawTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        update();
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, ClawService.class);
        if (ClawService.isRunning()) {
            i.setAction(ClawService.ACTION_STOP);
            startService(i);
        } else {
            i.setAction(ClawService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = ClawService.isRunning();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("AndroClaw");
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(on ? "gateway actif" : "arrêté");
        }
        t.updateTile();
    }
}
