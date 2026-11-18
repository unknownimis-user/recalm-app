package com.personal.reclaim;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ReCalm — personal focus timer.
 * Native shell: a WebView running the fully offline app in assets/index.html,
 * plus keep-screen-on, completion notifications, vibration and JSON export
 * exposed to JavaScript through the "AndroidBridge" interface.
 */
public class MainActivity extends Activity {

    private static final String CHANNEL_ID = "reclaim_sessions";
    private static final int NOTIF_ID = 1001;
    private static final int PERM_REQ = 5001;

    private WebView webView;
    private Handler bgHandler;
    private String appCache;
    private long appCacheAt;
    private String pendingBlocked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingBlocked = (getIntent() != null) ? getIntent().getStringExtra("blocked_package") : null;

        HandlerThread thread = new HandlerThread("reclaim-bg");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        createChannel();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage persistence
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false); // timer chimes without a tap
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.parseColor("#0A1210"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) { }
                    return true; // never navigate the shell away from the app
                }
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingBlocked != null) {
                    final String p = pendingBlocked;
                    pendingBlocked = null;
                    dispatchBlocked(p);
                }
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        requestNotifPermissionIfNeeded();
    }

    /* ---------- notifications ---------- */

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Session updates", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifies when focus sessions and breaks finish");
            ch.enableVibration(true);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, PERM_REQ);
        }
    }

    private void postNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_ring)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setColor(Color.parseColor("#34D399"))
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_stat_ring)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
        }
        nm.notify(NOTIF_ID, n);
    }

    /* ---------- JSON export to Downloads ---------- */

    private void exportJson(String json) {
        try {
            String name = "recalm-backup-" + System.currentTimeMillis() + ".json";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("could not create file");
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("could not open stream");
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
                where = "Downloads/" + name;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File targetDir = (dir != null) ? dir : getExternalFilesDir(null);
                if (targetDir == null) targetDir = getFilesDir();
                File f = new File(targetDir, name);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(bytes);
                    fos.flush();
                } finally {
                    fos.close();
                }
                where = f.getAbsolutePath();
            }
            final String msg = "Backup saved: " + where;
            runOnUiThread(new Runnable() {
                @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
            });
        } catch (final Exception e) {
            final String msg = "Export failed: " + e.getMessage();
            runOnUiThread(new Runnable() {
                @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
            });
        }
    }

    /* ---------- back button: let the app consume it first ---------- */

    @Override
    public void onBackPressed() {
        if (webView == null) { defaultBack(); return; }
        webView.evaluateJavascript("(window.__androidBack ? window.__androidBack() : false)",
                value -> { if (!"true".equals(value)) defaultBack(); });
    }

    private void defaultBack() {
        moveTaskToBack(true); // keep the process warm; all state is persisted anyway
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String pkg = (intent != null) ? intent.getStringExtra("blocked_package") : null;
        if (pkg != null) dispatchBlocked(pkg);
    }

    @Override
    protected void onDestroy() {
        if (bgHandler != null) bgHandler.getLooper().quit();
        super.onDestroy();
    }

    /* ---------- app shield helpers ---------- */

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Tells the web app a blocked app was opened (fires window.__onBlockedAttempt). */
    private void dispatchBlocked(final String pkg) {
        if (webView == null || pkg == null) return;
        final String label = appLabel(pkg);
        webView.post(new Runnable() {
            @Override public void run() {
                String js = "window.__onBlockedAttempt && window.__onBlockedAttempt("
                        + JSONObject.quote(pkg) + "," + JSONObject.quote(label) + ")";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Rasterizes an app icon to a small base64 PNG data URL for the picker grid. */
    private String iconData(Drawable d) {
        try {
            int s = dp(44);
            Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            d.setBounds(0, 0, s, s);
            d.draw(cv);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private SharedPreferences shieldPrefs() {
        return getSharedPreferences(BlockerService.PREFS, MODE_PRIVATE);
    }

    /** Starts (or re-pings) the foreground shield service. */
    private void startShieldService() {
        Intent svc = new Intent(MainActivity.this, BlockerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception ignored) { }
    }

    /* ---------- javascript bridge ---------- */

    private class Bridge {

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void notifyAt(long atMs, final String title, final String text) {
            long delay = atMs - System.currentTimeMillis();
            if (delay < 250) delay = 250;
            bgHandler.removeCallbacksAndMessages(null);
            bgHandler.postDelayed(new Runnable() {
                @Override public void run() { postNotification(title, text); }
            }, delay);
        }

        @JavascriptInterface
        public void cancelNotify() {
            bgHandler.removeCallbacksAndMessages(null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(
                            Math.max(40, ms), VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void requestNotifPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() { requestNotifPermissionIfNeeded(); }
            });
        }

        /* ----- app shield: installed apps, permissions, session control ----- */

        /** JSON [{p:package, n:label, i:iconDataUrl}] of launchable apps, sorted by name. */
        @JavascriptInterface
        public String getInstalledApps() {
            if (appCache == null || System.currentTimeMillis() - appCacheAt > 60000) {
                JSONArray sorted = new JSONArray();
                try {
                    Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> ris = getPackageManager().queryIntentActivities(main, 0);
                    List<JSONObject> objs = new ArrayList<>();
                    for (ResolveInfo ri : ris) {
                        try {
                            String pkg = ri.activityInfo.applicationInfo.packageName;
                            if (getPackageName().equals(pkg)) continue;
                            JSONObject o = new JSONObject();
                            o.put("p", pkg);
                            o.put("n", ri.loadLabel(getPackageManager()).toString());
                            o.put("i", iconData(ri.loadIcon(getPackageManager())));
                            objs.add(o);
                        } catch (Exception ignored) { }
                    }
                    Collections.sort(objs, new Comparator<JSONObject>() {
                        @Override public int compare(JSONObject a, JSONObject b) {
                            return a.optString("n").compareToIgnoreCase(b.optString("n"));
                        }
                    });
                    for (JSONObject o : objs) sorted.put(o);
                } catch (Exception ignored) { }
                appCache = sorted.toString();
                appCacheAt = System.currentTimeMillis();
            }
            return appCache;
        }

        @JavascriptInterface
        public boolean hasUsageAccess() {
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(getPackageName(), 0);
                AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                return aom != null && aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        ai.uid, ai.packageName) == AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openUsageAccessSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean canOverlay() {
            return Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
        }

        /** Arms/disarms the shield: persists state and starts BlockerService. */
        @JavascriptInterface
        public void setBlockSession(boolean active, long endMs, String blockedJson, boolean strict) {
            shieldPrefs().edit()
                    .putBoolean("session_active", active)
                    .putLong("session_end", active ? endMs : 0)
                    .putString("blocked", active ? (blockedJson == null ? "[]" : blockedJson) : "[]")
                    .putBoolean("strict", strict)
                    .apply();
            if (active) startShieldService();
            // when disarming, the service notices on its next tick and stops
            // itself — unless a schedule, quick block or limits keep it alive
        }

        /** Arms/disarms weekly scheduled blocking (minutes-from-midnight IST). */
        @JavascriptInterface
        public void setSchedule(boolean enabled, String daysJson, int startMin, int endMin, boolean strict) {
            shieldPrefs().edit()
                    .putBoolean("sched_enabled", enabled)
                    .putString("sched_days", daysJson == null ? "[1,1,1,1,1,0,0]" : daysJson)
                    .putInt("sched_start", startMin)
                    .putInt("sched_end", endMin)
                    .putBoolean("sched_strict", strict)
                    .apply();
            if (enabled) startShieldService();
            // when disabling, the service stops itself on its next tick
        }

        /** Arms an instant quick block until the given timestamp (0 clears). */
        @JavascriptInterface
        public void setQuickBlock(long untilMs, String blockedJson) {
            shieldPrefs().edit()
                    .putLong("quick_until", Math.max(0L, untilMs))
                    .putString("quick_blocked", untilMs > 0 ? (blockedJson == null ? "[]" : blockedJson) : "[]")
                    .apply();
            if (untilMs > 0) startShieldService();
            // when it expires, the service stops (or slows down) on its next tick
        }

        /* ----- reels shield: block only the Reels tab (accessibility) ----- */

        /** Arms reels-only blocking for the given packages (JSON array of package names). */
        @JavascriptInterface
        public void setReelsOnly(String packagesJson) {
            shieldPrefs().edit()
                    .putString("reels_only", packagesJson == null ? "[]" : packagesJson)
                    .apply();
        }

        /* ----- trusted apps: shields go fully silent (anti-detection) ----- */

        /** Apps the user marked trusted (JSON array of packages). While a
         *  trusted app is open, every shield stays completely passive — no
         *  blocking, no screen scanning, no overlays, no audio grab, no
         *  logs — so the app never sees ReCalm act like a third-party app. */
        @JavascriptInterface
        public void setTrustedApps(String packagesJson) {
            shieldPrefs().edit()
                    .putString("trusted", packagesJson == null ? "[]" : packagesJson)
                    .apply();
        }

        /** JSON array of trusted packages — restores the list after restarts. */
        @JavascriptInterface
        public String getTrustedApps() {
            return shieldPrefs().getString("trusted", "[]");
        }

        /** UI color mood ("aurora"/"sunset"/"lavender"/"glass") — the native
         *  Reels block screen follows it so the whole app stays in one theme. */
        @JavascriptInterface
        public void setTheme(String theme) {
            shieldPrefs().edit()
                    .putString("ui_theme", theme == null ? "aurora" : theme)
                    .apply();
        }

        /** True once the user enabled the Reels Shield accessibility service. */
        @JavascriptInterface
        public boolean isReelsShieldEnabled() {
            try {
                if (Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
                String svcs = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return svcs != null && svcs.contains("com.personal.reclaim")
                        && svcs.contains("ReelsShieldService");
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens system Accessibility settings so the Reels Shield can be turned on. */
        @JavascriptInterface
        public void openAccessibilitySettings() {
            try {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
        }

        /** JSON {events:[…], deep, errCount, errLast, dump, dumpAt, urgesSurfed} — Reels Shield diagnostics. */
        @JavascriptInterface
        public String getReelsDiag() {
            SharedPreferences sp = shieldPrefs();
            try {
                JSONObject o = new JSONObject();
                o.put("events", new JSONArray(sp.getString("reels_diag", "[]")));
                o.put("deep", sp.getBoolean("reels_deep", false));
                o.put("errCount", sp.getInt("reels_err_count", 0));
                o.put("errLast", sp.getString("reels_err_last", ""));
                o.put("dump", sp.getString("reels_dump", ""));
                o.put("dumpAt", sp.getLong("reels_dump_t", 0));
                o.put("urgesSurfed", sp.getInt("urges_surfed", 0));
                return o.toString();
            } catch (Exception e) {
                return "{\"events\":[],\"deep\":false,\"errCount\":0,\"errLast\":\"\",\"dump\":\"\",\"dumpAt\":0,\"urgesSurfed\":0}";
            }
        }

        /** Deep capture: save the full walked screen layout at the next block. */
        @JavascriptInterface
        public void setReelsDeep(boolean on) {
            shieldPrefs().edit().putBoolean("reels_deep", on).apply();
        }

        /** Clears the Reels Shield diagnostics log and error breadcrumbs. */
        @JavascriptInterface
        public void clearReelsDiag() {
            shieldPrefs().edit()
                    .remove("reels_diag").remove("reels_dump").remove("reels_dump_t")
                    .remove("reels_err_count").remove("reels_err_last").apply();
        }

        /** Sets per-app daily usage limits: JSON {package: minutes}. */
        @JavascriptInterface
        public void setLimits(String limitsJson) {
            boolean hasLimits = false;
            try {
                hasLimits = new JSONObject(limitsJson == null ? "{}" : limitsJson).length() > 0;
            } catch (Exception ignored) { }
            shieldPrefs().edit()
                    .putString("limits", limitsJson == null ? "{}" : limitsJson)
                    .apply();
            if (hasLimits) startShieldService(); // limits are enforced all day
            // with no limits left, the service winds down on its next tick
        }

        /** Temporarily suspends scheduled blocking until the given timestamp. */
        @JavascriptInterface
        public void pauseShield(long untilMs) {
            shieldPrefs().edit().putLong("pause_until", Math.max(0L, untilMs)).apply();
            Intent svc = new Intent(MainActivity.this, BlockerService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } catch (Exception ignored) { }
        }

        /** Lifts a pause; scheduled blocking resumes immediately. */
        @JavascriptInterface
        public void resumeShield() {
            shieldPrefs().edit().putLong("pause_until", 0).apply();
        }

        /** JSON {pauseUntil, quickUntil, usage:{day, u:{pkg:sec}}} — lets the web UI reconcile after process restarts. */
        @JavascriptInterface
        public String getShieldState() {
            SharedPreferences sp = shieldPrefs();
            try {
                JSONObject out = new JSONObject();
                out.put("pauseUntil", sp.getLong("pause_until", 0));
                out.put("quickUntil", sp.getLong("quick_until", 0));
                JSONObject usage = new JSONObject();
                usage.put("day", sp.getString("usage_day", ""));
                try {
                    usage.put("u", new JSONObject(sp.getString("usage_json", "{}")));
                } catch (Exception e) {
                    usage.put("u", new JSONObject());
                }
                out.put("usage", usage);
                return out.toString();
            } catch (Exception e) {
                return "{\"pauseUntil\":0,\"quickUntil\":0,\"usage\":{\"day\":\"\",\"u\":{}}}";
            }
        }

        /** JSON {count:N, entries:[{t,p,n}...]} — blocked-attempt log. */
        @JavascriptInterface
        public String getBlockLog() {
            try {
                JSONObject o = new JSONObject();
                o.put("count", shieldPrefs().getInt("block_count", 0));
                o.put("entries", new JSONArray(shieldPrefs().getString("block_log", "[]")));
                return o.toString();
            } catch (Exception e) {
                return "{\"count\":0,\"entries\":[]}";
            }
        }

        @JavascriptInterface
        public void clearBlockLog() {
            shieldPrefs().edit()
                    .remove("block_log").remove("block_count")
                    .remove("block_today").remove("block_day").apply();
        }

        @JavascriptInterface
        public void exportData(String json) {
            exportJson(json);
        }
    }
}
