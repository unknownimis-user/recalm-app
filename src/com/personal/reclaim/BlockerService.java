package com.personal.reclaim;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * ReCalm — the distraction shield.
 *
 * A foreground service armed by the app's JavaScript (state in
 * SharedPreferences via the bridge). It blocks in four ways:
 * (1) while a focus session runs, (2) inside weekly scheduled windows
 * (refocus-style, pausable for short breaks), (3) during an instant
 * "quick block" (timed lock, no session needed) and (4) after a daily
 * per-app usage limit is reached (time limits keep it alive all day).
 * It polls UsageStatsManager for foreground app transitions; when a
 * blocked app is opened it vibrates, logs the attempt, silences its
 * audio and slams a full-screen overlay — complete with a slow
 * breathing circle and a mindful quote — over the offending app.
 * Strict sessions/schedules offer no way out except returning to
 * ReCalm.
 */
public class BlockerService extends Service {

    static final String PREFS = "reclaim_prefs";
    private static final String CHANNEL_FGS = "reclaim_shield";
    private static final String CHANNEL_ALERT = "reclaim_block";
    private static final int NOTIF_FGS = 2001;
    private static final int NOTIF_ALERT = 3001;
    private static final long POLL_MS = 500;
    private static final long POLL_SLOW_MS = 3000;   // waiting for the next window
    private static final long NAG_GRACE_MS = 4000;

    /** Mindful one-liners for the block screen (rotating). */
    private static final String[] QUOTES = {
            "The urge passes in about 90 seconds. Give it a moment.",
            "Nothing here is going anywhere. Your focus is.",
            "One scroll won't fix boredom — it only postpones it.",
            "You said this time matters. It still does.",
            "Boredom is the doorway to better ideas.",
            "Future you is grateful right now.",
            "You opened this on purpose. Breathe, then choose again.",
            "Almost there — the discomfort is the practice.",
    };

    private HandlerThread thread;
    private Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String currentPkg = null;
    private volatile View overlay = null;
    private volatile long overlayDismissedAt = 0;
    private Bitmap mascotCache = null;
    private AudioManager audio = null;
    private volatile boolean audioSilenced = false; // we hold focus & media is muted
    private boolean muteByFlag = false;             // ADJUST_MUTE path (vs volume-zero fallback)
    private int savedVolume = -1;                   // volume for the volume-zero fallback
    private boolean wasBlocking = false;            // last tick's session|schedule verdict
    private String notifMode = null;                // dedupe key for the FGS notification
    private ObjectAnimator breathAnim = null;       // the breathing circle on the overlay
    private Runnable breathRunnable = null;

    /* daily time limits: per-app foreground seconds, IST-day keyed */
    private final HashMap<String, Long> openSince = new HashMap<>();   // pkg -> open event ts
    private final HashMap<String, Long> usageSec = new HashMap<>();    // pkg -> seconds today
    private final HashSet<String> limitLogged = new HashSet<>();       // limit hits logged today
    private String usageDay = "";
    private long usageCursor = 0;
    private long lastUsagePersist = 0;
    private volatile long overlayShownAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("reclaim-blocker");
        thread.start();
        handler = new Handler(thread.getLooper());
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createChannels();
        loadUsage();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (handler != null) {
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 350);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        restoreAudio(); // never leave the device muted behind us
        persistUsage();
        if (handler != null) {
            handler.getLooper().quit();
            handler = null;
        }
        removeOverlayNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------- shared state (written by JavaScript via the bridge) ---------- */

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Set<String> blockedSet() {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("blocked", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i);
                if (p != null && !p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Packages the user marked trusted — never blocked, never covered,
     *  never muted, never logged. Trust wins over sessions, schedules,
     *  quick blocks and daily limits alike. */
    private Set<String> trustedSet() {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("trusted", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i);
                if (p != null && !p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private boolean sessionActive() {
        SharedPreferences sp = prefs();
        return sp.getBoolean("session_active", false)
                && sp.getLong("session_end", 0) > System.currentTimeMillis();
    }

    /* ---------- scheduled blocking (weekly windows, IST) ---------- */

    /** A schedule is armed at all (and there is something to block). */
    private boolean scheduleConfigured() {
        return prefs().getBoolean("sched_enabled", false) && !blockedSet().isEmpty();
    }

    /** True when the current IST time falls inside an armed, non-paused window. */
    private boolean scheduleWindowActive() {
        if (!scheduleConfigured()) return false;
        if (System.currentTimeMillis() < prefs().getLong("pause_until", 0)) return false;
        try {
            JSONArray days = new JSONArray(prefs().getString("sched_days", "[1,1,1,1,1,0,0]"));
            int s = prefs().getInt("sched_start", 540);   // minutes from midnight
            int e = prefs().getInt("sched_end", 1020);
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(System.currentTimeMillis() + (long) (5.5 * 3600e3));
            int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int calDow = c.get(Calendar.DAY_OF_WEEK);                  // 1=Sun .. 7=Sat
            int jsDow = (calDow == Calendar.SUNDAY) ? 6 : calDow - 2;  // 0=Mon .. 6=Sun
            boolean overnight = s > e;
            if (overnight) {
                // before dawn the window belongs to the day it started (yesterday)
                return min >= s ? dayOn(days, jsDow) : dayOn(days, (jsDow + 6) % 7);
            }
            return min >= s && min < e && dayOn(days, jsDow);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean dayOn(JSONArray days, int i) {
        return days.optInt(i, 0) == 1;
    }

    /* ---------- quick block (instant timed lock, no session needed) ---------- */

    private Set<String> quickSet() {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("quick_blocked", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i);
                if (p != null && !p.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /* ---------- daily time limits (per-app usage caps, IST days) ---------- */

    /** Configured limits: package -> minutes per day. */
    private HashMap<String, Integer> limitMap() {
        HashMap<String, Integer> out = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs().getString("limits", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                int v = o.optInt(k, 0);
                if (v > 0) out.put(k, v);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private boolean limitExceeded(String pkg, HashMap<String, Integer> limits) {
        if (pkg == null) return false;
        Integer min = limits.get(pkg);
        if (min == null) return false;
        Long used = usageSec.get(pkg);
        return used != null && used >= min * 60L;
    }

    private void loadUsage() {
        SharedPreferences sp = prefs();
        usageDay = sp.getString("usage_day", "");
        usageCursor = sp.getLong("usage_cursor", 0);
        try {
            JSONObject o = new JSONObject(sp.getString("usage_json", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                long v = o.optLong(k, 0);
                if (v > 0) usageSec.put(k, v);
            }
        } catch (Exception ignored) { }
        lastUsagePersist = System.currentTimeMillis();
    }

    private void persistUsage() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Long> e : usageSec.entrySet()) o.put(e.getKey(), e.getValue());
            prefs().edit()
                    .putString("usage_day", usageDay)
                    .putString("usage_json", o.toString())
                    .putLong("usage_cursor", usageCursor)
                    .apply();
            lastUsagePersist = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    /**
     * Accumulates per-app foreground seconds from UsageStats events
     * (resume→pause pairs), IST-day keyed, and keeps currentPkg fresh even
     * while slow-polling. Intra-app activity hops arrive as pause/resume
     * pairs of the same package, so the open-since map stays honest.
     */
    private void trackUsage(HashMap<String, Integer> limits) {
        long now = System.currentTimeMillis();
        String today = istDayKey(now);
        if (!today.equals(usageDay)) {
            usageSec.clear();
            limitLogged.clear();
            usageDay = today;
            persistUsage();
        }
        if (usageCursor <= 0) usageCursor = now - 60000L;
        if (limits.isEmpty()) { usageCursor = now; return; }
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return;
        try {
            UsageEvents events = usm.queryEvents(usageCursor, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                String pkg = ev.getPackageName();
                if (pkg == null || pkg.equals(getPackageName())) continue;
                int type = ev.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (limits.containsKey(pkg)) openSince.put(pkg, ev.getTimeStamp());
                    else openSince.remove(pkg);
                    currentPkg = pkg;
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    Long open = openSince.remove(pkg);
                    if (open != null && ev.getTimeStamp() > open) {
                        long add = (ev.getTimeStamp() - open) / 1000L;
                        if (add > 0) {
                            long base = usageSec.containsKey(pkg) ? usageSec.get(pkg) : 0L;
                            usageSec.put(pkg, base + add);
                        }
                    }
                }
            }
            usageCursor = now;
            if (now - lastUsagePersist > 60000L) persistUsage();
        } catch (Exception ignored) { }
    }

    private String minToHm(int m) {
        m = ((m % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    private String istHM(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ms + (long) (5.5 * 3600e3));
        return String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /* ---------- polling loop ---------- */

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean slow = false;
            try { slow = !tick(); } catch (Exception ignored) { }
            if (handler != null) handler.postDelayed(this, slow ? POLL_SLOW_MS : POLL_MS);
        }
    };

    /** One detection pass; returns true while actively blocking (fast poll). */
    private boolean tick() {
        Set<String> blocked = blockedSet();
        Set<String> quickApps = quickSet();
        HashMap<String, Integer> limits = limitMap();
        Set<String> trusted = trustedSet();
        boolean sess = !blocked.isEmpty() && sessionActive();
        boolean sch = scheduleWindowActive();
        boolean quick = !quickApps.isEmpty()
                && System.currentTimeMillis() < prefs().getLong("quick_until", 0);
        boolean armed = sess || sch || quick;

        trackUsage(limits);

        boolean limitHit = currentPkg != null && !getPackageName().equals(currentPkg)
                && !trusted.contains(currentPkg)
                && limitExceeded(currentPkg, limits);
        boolean shouldBlock = armed || limitHit;

        if (shouldBlock && !wasBlocking) {
            // just (re)armed: recover what is foreground right now — the long
            // lookback catches "stayed inside the blocked app through a pause
            // or a service restart"
            String fg = queryForeground(30 * 60000L);
            if (fg != null) currentPkg = fg;
            if (isBlockedNow(currentPkg, sess, sch, quick, blocked, quickApps, limits)) {
                logAttempt(currentPkg);
                vibrate();
                silenceAudio();
                showOverlay(currentPkg);
            }
        } else if (!shouldBlock && wasBlocking) {
            // disarmed (session over / window ended / pause began): clear the screen
            removeOverlayNow();
            restoreAudio();
        }
        wasBlocking = shouldBlock;

        /* keep media muted the whole time we block — someone may have
           restored the stream mid-block (reels-shield hand-off, races) */
        if (shouldBlock && !audioSilenced && currentPkg != null
                && isBlockedNow(currentPkg, sess, sch, quick, blocked, quickApps, limits)) {
            silenceAudio();
        }

        if (!shouldBlock) {
            boolean waiting = scheduleConfigured()
                    || System.currentTimeMillis() < prefs().getLong("quick_until", 0)
                    || !limits.isEmpty();
            if (waiting) {
                updateNotif("wait");  // stay alive for limits / the next window (slow poll)
                return false;
            }
            shutdown();
            return false;
        }

        updateNotif(sess ? "session" : sch ? "sched" : quick ? "quick" : "limit");

        String latest = queryForeground(8000);
        if (latest != null && !latest.equals(currentPkg)) {
            currentPkg = latest;
            if (isBlockedNow(currentPkg, sess, sch, quick, blocked, quickApps, limits)) {
                logAttempt(currentPkg);
                vibrate();
                silenceAudio();
                showOverlay(currentPkg);
            } else if (audioSilenced) {
                restoreAudio(); // left the blocked app: ReCalm / home / an allowed app
            }
        }

        /* no overlay while inside a blocked app: either the shield was
           dismissed (nag), a quick block armed mid-use, or a daily limit
           was just hit — re-assert after the grace */
        long now = System.currentTimeMillis();
        if (overlay == null && isBlockedNow(currentPkg, sess, sch, quick, blocked, quickApps, limits)) {
            boolean freshLimit = limitExceeded(currentPkg, limits)
                    && !(armed && (blocked.contains(currentPkg) || quickApps.contains(currentPkg)))
                    && !limitLogged.contains(currentPkg);
            long lastEvent = Math.max(overlayShownAt, overlayDismissedAt);
            if (freshLimit || (lastEvent > 0 && now - lastEvent > NAG_GRACE_MS)) {
                if (freshLimit) {
                    limitLogged.add(currentPkg);
                    logAttempt(currentPkg);
                    vibrate();
                }
                silenceAudio(); // re-assert in case focus was taken meanwhile
                showOverlay(currentPkg);
            }
        }
        return true;
    }

    /** Is this package blocked right now, by any of the three blocking modes? */
    private boolean isBlockedNow(String pkg, boolean sess, boolean sch, boolean quick,
                                 Set<String> blocked, Set<String> quickApps,
                                 HashMap<String, Integer> limits) {
        return isBlockedNow(pkg, sess, sch, quick, blocked, quickApps, limits, trustedSet());
    }

    /** Same check with a pre-fetched trusted list — trusted apps are exempt
     *  everywhere: no overlay, no mute, no vibration, no log entry. */
    private boolean isBlockedNow(String pkg, boolean sess, boolean sch, boolean quick,
                                 Set<String> blocked, Set<String> quickApps,
                                 HashMap<String, Integer> limits, Set<String> trusted) {
        if (pkg == null || pkg.equals(getPackageName())) return false;
        if (trusted.contains(pkg)) return false;
        if ((sess || sch) && blocked.contains(pkg)) return true;
        if (quick && quickApps.contains(pkg)) return true;
        return limitExceeded(pkg, limits);
    }

    /** Latest foreground app within the lookback window, or null if no event. */
    private String queryForeground(long lookbackMs) {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        String latest = null;
        try {
            UsageEvents events = usm.queryEvents(now - lookbackMs, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    latest = ev.getPackageName();
            }
        } catch (Exception ignored) { }
        return latest;
    }

    private void shutdown() {
        restoreAudio();
        persistUsage();
        removeOverlayNow();
        stopForeground(true);
        stopSelf();
    }

    /* ---------- attempt log (read back through the bridge) ---------- */

    private String istDayKey(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ms + (long) (5.5 * 3600e3));
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void logAttempt(String pkg) {
        SharedPreferences sp = prefs();
        long now = System.currentTimeMillis();
        String day = istDayKey(now);
        SharedPreferences.Editor ed = sp.edit();
        try {
            JSONArray entries = new JSONArray(sp.getString("block_log", "[]"));
            JSONObject e = new JSONObject();
            e.put("t", now);
            e.put("p", pkg);
            e.put("n", appLabel(pkg));
            entries.put(e);
            while (entries.length() > 200) entries.remove(0);
            ed.putString("block_log", entries.toString());
        } catch (Exception ignored) { }
        int today = day.equals(sp.getString("block_day", "")) ? sp.getInt("block_today", 0) : 0;
        ed.putString("block_day", day)
          .putInt("block_today", today + 1)
          .putInt("block_count", sp.getInt("block_count", 0) + 1)
          .apply();
    }

    private int todayCount() {
        SharedPreferences sp = prefs();
        if (!istDayKey(System.currentTimeMillis()).equals(sp.getString("block_day", ""))) return 0;
        return sp.getInt("block_today", 0);
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(300);
        } catch (Exception ignored) { }
    }

    /* ---------- audio silencing (reels/videos keep playing behind the overlay) ---------- */

    /**
     * We cannot force-stop another app, and our overlay is non-focusable,
     * so a blocked app's player keeps running behind it. Two-pronged fix:
     * (1) take permanent audio focus — well-behaved players (ExoPlayer:
     * Instagram, Facebook) pause on focus loss — and (2) hard-mute the
     * music stream as a failsafe for apps that ignore focus. Everything is
     * restored by restoreAudio() once the user leaves the blocked app.
     */
    private void silenceAudio() {
        if (audio == null) return;
        try {
            audio.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (!audioSilenced) {
                try {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_MUTE, 0);
                    muteByFlag = true;
                } catch (Exception e) {
                    savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (savedVolume > 0)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    muteByFlag = false;
                }
                audioSilenced = true;
            }
        } catch (Exception ignored) { }
    }

    /** User is back in Reclaim (or any allowed app): unmute media, release focus. */
    private void restoreAudio() {
        if (audio == null || !audioSilenced) return;
        audioSilenced = false;
        try {
            if (muteByFlag) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, 0);
            } else if (savedVolume > 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            }
        } catch (Exception ignored) { }
        try { audio.abandonAudioFocus(focusListener); } catch (Exception ignored) { }
        savedVolume = -1;
    }

    /** We hold focus without playing anything; a yank (e.g. a call) keeps the mute. */
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) { }
            };

    /* ---------- the block screen (system overlay) ---------- */

    /** Which mode blocks this package right now: session/sched/quick/limit. */
    private String reasonFor(String pkg) {
        if (pkg != null) {
            Set<String> blocked = blockedSet();
            if (blocked.contains(pkg)) {
                if (sessionActive()) return "session";
                if (scheduleWindowActive()) return "sched";
            }
            Set<String> qa = quickSet();
            if (qa.contains(pkg)
                    && System.currentTimeMillis() < prefs().getLong("quick_until", 0)) {
                return "quick";
            }
        }
        return "limit";
    }

    private String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void showOverlay(final String pkg) {
        main.post(new Runnable() {
            @Override public void run() { showOverlayUi(pkg); }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Bitmap mascot() {
        if (mascotCache != null) return mascotCache;
        try {
            InputStream is = getAssets().open("img/mascot-guard.png");
            mascotCache = BitmapFactory.decodeStream(is);
            is.close();
        } catch (Exception e) {
            mascotCache = null;
        }
        return mascotCache;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private void lpTop(TextView tv, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        tv.setLayoutParams(lp);
    }

    private void showOverlayUi(final String pkg) {
        if (!Settings.canDrawOverlays(this)) {
            fallbackNotify(pkg);
            return;
        }
        removeOverlayUi(0);

        final String reason = reasonFor(pkg);
        final boolean inSession = "session".equals(reason);
        final boolean strict;
        String sub;
        long now = System.currentTimeMillis();
        if (inSession) {
            strict = prefs().getBoolean("strict", false);
            sub = "Focus session in progress — "
                    + mmss(prefs().getLong("session_end", 0) - now) + " left";
        } else if ("sched".equals(reason)) {
            strict = prefs().getBoolean("sched_strict", false);
            sub = "Scheduled block — until " + minToHm(prefs().getInt("sched_end", 1020));
        } else if ("quick".equals(reason)) {
            strict = true; // a quick block is a commitment, not a suggestion
            sub = "Quick block — " + mmss(prefs().getLong("quick_until", 0) - now) + " left";
        } else {
            strict = true; // daily cap: the only way out is tomorrow
            Integer cap = limitMap().get(pkg);
            long used = usageSec.containsKey(pkg) ? usageSec.get(pkg) : 0L;
            sub = cap != null
                    ? "Daily limit reached — " + (used / 60) + " of " + cap + " min used"
                    : "Daily limit reached";
        }
        String label = appLabel(pkg);
        int attempts = todayCount();

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Color.parseColor("#0A1210"));
        root.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(58), dp(28), dp(42));
        root.addView(box);

        Bitmap m = mascot();
        if (m != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(m);
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(128), dp(128)));
            box.addView(img);
            box.addView(gap(dp(20)));
        }

        TextView title = new TextView(this);
        title.setText(label + " is blocked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextColor(Color.parseColor("#34D399"));
        subView.setTextSize(15);
        subView.setTypeface(Typeface.DEFAULT_BOLD);
        lpTop(subView, dp(8));
        box.addView(subView);

        TextView note = new TextView(this);
        note.setText(attempts <= 1
                ? "Nice try. Back to your session."
                : "Attempt #" + attempts + " today — it is logged.");
        note.setTextColor(Color.parseColor("#8FA79B"));
        note.setTextSize(13.5f);
        lpTop(note, dp(8));
        box.addView(note);

        /* the mindful moment: a slow breathing circle (refocus-style) */
        final View circle = new View(this);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(Color.parseColor("#12271F"));
        cd.setStroke(dp(2), Color.parseColor("#34D399"));
        circle.setBackground(cd);
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(dp(86), dp(86));
        cpl.topMargin = dp(26);
        cpl.gravity = Gravity.CENTER_HORIZONTAL;
        circle.setLayoutParams(cpl);
        box.addView(circle);

        final TextView breath = new TextView(this);
        breath.setText("Breathe in…");
        breath.setTextColor(Color.parseColor("#8FA79B"));
        breath.setTextSize(13.5f);
        breath.setTypeface(Typeface.DEFAULT_BOLD);
        breath.setGravity(Gravity.CENTER);
        lpTop(breath, dp(10));
        box.addView(breath);

        try {
            breathAnim = ObjectAnimator.ofPropertyValuesHolder(circle,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.32f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.32f));
            breathAnim.setDuration(4000L);
            breathAnim.setRepeatCount(ValueAnimator.INFINITE);
            breathAnim.setRepeatMode(ValueAnimator.REVERSE);
            breathAnim.start();
        } catch (Exception ignored) { }

        breathRunnable = new Runnable() {
            boolean phase = true;
            @Override public void run() {
                if (overlay == null) return; // overlay gone: stop cycling
                phase = !phase;
                breath.setText(phase ? "Breathe in…" : "Breathe out…");
                main.postDelayed(this, 4000L);
            }
        };
        main.postDelayed(breathRunnable, 4000L);

        TextView quote = new TextView(this);
        int qi = (attempts + Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) % QUOTES.length;
        quote.setText('"' + QUOTES[qi] + '"');
        quote.setTextColor(Color.parseColor("#6E8A7C"));
        quote.setTextSize(12.5f);
        quote.setGravity(Gravity.CENTER);
        quote.setPadding(dp(10), dp(18), dp(10), 0);
        box.addView(quote);

        Button back = new Button(this);
        back.setText(inSession ? "Back to focus" : "Back to ReCalm");
        back.setTextColor(Color.parseColor("#06251C"));
        back.setTextSize(16);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        bg.setColor(Color.parseColor("#34D399"));
        bg.setCornerRadius(dp(30));
        back.setBackground(bg);
        back.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        bp.topMargin = dp(30);
        back.setLayoutParams(bp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                removeOverlayUi(0);
                Intent i = new Intent(BlockerService.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("blocked_package", pkg);
                try { startActivity(i); } catch (Exception ignored) { }
            }
        });
        box.addView(back);

        TextView tail = new TextView(this);
        if (!strict) {
            tail.setText("Not now (the shield returns)");
        } else if ("quick".equals(reason)) {
            tail.setText("Quick block — it lifts automatically");
        } else if ("limit".equals(reason)) {
            tail.setText("Daily cap reached — unlocks tomorrow");
        } else {
            tail.setText("Strict block — the only way out is back.");
        }
        tail.setTextColor(Color.parseColor("#8FA79B"));
        tail.setTextSize(13.5f);
        tail.setPadding(dp(8), dp(16), dp(8), dp(4));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        tail.setLayoutParams(tp);
        if (!strict) {
            tail.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    removeOverlayUi(System.currentTimeMillis());
                }
            });
        }
        box.addView(tail);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 27) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(root, lp);
            overlay = root;
            overlayShownAt = System.currentTimeMillis();
            overlayDismissedAt = 0;
        } catch (Exception e) {
            overlay = null;
            fallbackNotify(pkg);
        }
    }

    /**
     * Removes the overlay. dismissedAt > 0 marks a user dismissal (the shield
     * comes back after the nag grace if they stay in the blocked app);
     * 0 means resolved (session over or user returned to focus).
     */
    private void removeOverlayUi(long dismissedAt) {
        if (breathAnim != null) {
            try { breathAnim.cancel(); } catch (Exception ignored) { }
            breathAnim = null;
        }
        if (breathRunnable != null) {
            main.removeCallbacks(breathRunnable);
            breathRunnable = null;
        }
        if (overlay != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(overlay);
            } catch (Exception ignored) { }
            overlay = null;
        }
        overlayDismissedAt = dismissedAt;
    }

    private void removeOverlayNow() {
        main.post(new Runnable() {
            @Override public void run() { removeOverlayUi(0); }
        });
    }

    /* ---------- notifications ---------- */

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel fgs = new NotificationChannel(CHANNEL_FGS,
                "Distraction shield", NotificationManager.IMPORTANCE_LOW);
        fgs.setDescription("Runs while a focus session has the app shield armed");
        fgs.setShowBadge(false);
        nm.createNotificationChannel(fgs);
    }

    private void startAsForeground() {
        notifMode = null;
        updateNotif(sessionActive() ? "session" : (scheduleWindowActive() ? "sched" : "wait"));
    }

    /** Keeps the FGS notification honest: session / scheduled / waiting. */
    private void updateNotif(String mode) {
        if (mode.equals(notifMode)) return;
        notifMode = mode;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int n = blockedSet().size();
        String title;
        String text;
        if ("session".equals(mode)) {
            title = "Shield on — focus session";
            text = n + " app" + (n == 1 ? "" : "s") + " blocked while you focus";
        } else if ("sched".equals(mode)) {
            title = "Shield on — scheduled block";
            text = "Blocked until " + minToHm(prefs().getInt("sched_end", 1020)) + " (IST)";
        } else if ("quick".equals(mode)) {
            title = "Shield on — quick block";
            long left = Math.max(0, prefs().getLong("quick_until", 0) - System.currentTimeMillis());
            text = "Locked for " + (left / 60000) + " more min";
        } else if ("limit".equals(mode)) {
            title = "Shield on — daily limit";
            text = "That app hit its daily cap — back tomorrow";
        } else {
            long pauseUntil = prefs().getLong("pause_until", 0);
            if (System.currentTimeMillis() < pauseUntil) {
                title = "Shield paused";
                text = "Resumes at " + istHM(pauseUntil) + " on its own";
            } else if (scheduleConfigured()) {
                title = "Scheduled shield";
                text = "Next window " + minToHm(prefs().getInt("sched_start", 540))
                        + "–" + minToHm(prefs().getInt("sched_end", 1020)) + " (IST)";
            } else {
                int m = limitMap().size();
                if (m > 0) {
                    title = "Time limits armed";
                    text = m + " app" + (m == 1 ? "" : "s") + " capped daily (IST)";
                } else {
                    title = "Shield armed";
                    text = "Waiting for the next window";
                }
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_FGS)
                : new Notification.Builder(this);
        Notification notif = b
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .setColor(Color.parseColor("#34D399"))
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_FGS, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_FGS, notif);
            }
            nm.notify(NOTIF_FGS, notif);
        } catch (Exception ignored) { }
    }

    /** No overlay permission (or add failed): at least make some noise. */
    private void fallbackNotify(String pkg) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT, "Blocked apps",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts when a blocked app is opened during focus");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("blocked_package", pkg);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ALERT)
                : new Notification.Builder(this);
        Notification n = b
                .setSmallIcon(R.drawable.ic_stat_ring)
                .setContentTitle(appLabel(pkg) + " is blocked")
                .setContentText("Focus session in progress — tap to get back")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setColor(Color.parseColor("#34D399"))
                .build();
        nm.notify(NOTIF_ALERT, n);
    }
}
