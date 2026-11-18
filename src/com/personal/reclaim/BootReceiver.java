package com.personal.reclaim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Restarts the distraction shield after a reboot — if a focus session is
 * still running (sessions are timestamp-based, so they survive reboots), a
 * weekly blocking schedule is armed, a quick block is still ticking, or
 * daily time limits are configured (those run all day).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    BlockerService.PREFS, Context.MODE_PRIVATE);
            boolean active = sp.getBoolean("session_active", false);
            long end = sp.getLong("session_end", 0);
            String blocked = sp.getString("blocked", "[]");
            boolean hasApps = blocked != null && blocked.length() > 2; // more than "[]"
            boolean session = active && end > System.currentTimeMillis() && hasApps;
            boolean schedule = sp.getBoolean("sched_enabled", false) && hasApps;
            String qBlocked = sp.getString("quick_blocked", "[]");
            boolean quick = sp.getLong("quick_until", 0) > System.currentTimeMillis()
                    && qBlocked != null && qBlocked.length() > 2;
            String limits = sp.getString("limits", "{}");
            boolean hasLimits = limits != null && limits.length() > 2;
            if (!session && !schedule && !quick && !hasLimits) return;
            Intent svc = new Intent(context, BlockerService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Exception ignored) { }
    }
}
