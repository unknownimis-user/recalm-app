package com.personal.reclaim;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * ReCalm — the Reels shield.
 *
 * An optional AccessibilityService that blocks ONLY the Reels tab of
 * Instagram and Facebook (plus Facebook's Video tab), leaving the rest of
 * those apps usable. Usage-stats polling can't see inside another app, but
 * the accessibility tree can: we watch for the bottom-navigation "Reels"
 * ("Reels"/"Video"/"Watch" on Facebook) item to become selected, which is
 * how those apps mark the active tab.
 *
 * When reels are detected while reels-only blocking is armed (and the app
 * isn't already fully blocked by the main shield), we vibrate, log the
 * attempt, silence media audio and cover the screen with a calm block
 * sheet. "Leave Reels" even presses Back on the user's behalf — the feed,
 * search, DMs and profile are never touched. The moment the reels view is
 * left, the screen is released and audio restored.
 *
 * v2.3 stability notes:
 *  - Screen scans run on a dedicated worker thread, never the main thread
 *    (Instagram fires a flood of content-changed events; scanning on main
 *    starved input and got the service killed as "malfunctioning").
 *  - AccessibilityNodeInfo.getStateDescription() only exists on API 30+;
 *    calling it on older phones threw NoSuchMethodError (an Error, not an
 *    Exception) and crashed the process the instant a scan ran. Guarded.
 *  - Every callback catches Throwable — an accessibility service that
 *    crashes gets switched off by the system, so nothing may escape.
 *  - The block sheet uses TYPE_ACCESSIBILITY_OVERLAY, which accessibility
 *    services may always add — no "display over other apps" grant needed.
 *
 * v2.4 precision notes:
 *  - v2.3 blocked whenever ANY "Reels"-labeled node reported selected —
 *    but Instagram also labels feed posts and DM previews "Reels", so the
 *    home feed and messages got blocked by mistake. Detection now demands
 *    the full bottom-nav context: a row of sibling tab items (Home, Search,
 *    Reels, Profile…) in the bottom band of the screen. Only that row's
 *    selected item decides, and a selected non-reels tab always wins.
 *  - Taps on nav items (TYPE_VIEW_CLICKED) are remembered as a tie-breaker
 *    for nav bars that report no selection at all.
 *  - Facebook keeps the proven v2.3 node matcher as a fallback.
 *  - Every block/lift records an evidence snapshot (Detection log in the
 *    Guard tab); "Deep capture" also saves the walked screen layout.
 *
 * v2.5 player notes:
 *  - The Reels tab isn't the only way in: tapping a reel in the home feed
 *    (or in explore, a profile or a chat) opens the SAME fullscreen player
 *    — with no bottom nav on screen, so the tab logic above can't see it.
 *    The player's right-hand action rail (Like, Comment, Share… stacked
 *    icons) is its signature: three-plus different actions in one tight
 *    vertical strip at the right edge. The feed can never stack like that
 *    (its buttons sit in a horizontal row), so home, search and DMs stay
 *    exactly as untouched as before. The block sheet is the same, and
 *    "Leave Reels" still just presses Back — straight back to the feed.
 *
 * One-time setup: the user enables this service in Accessibility settings
 * (the app links there). Usage Access is shared with the main shield and
 * already granted by its setup flow.
 */
public class ReelsShieldService extends AccessibilityService {

    private static final String CHANNEL_ALERT = "reclaim_reels";
    private static final int NOTIF_ALERT = 4001;
    private static final int MAX_NODES = 300;        // BFS cap per scan (small = fast)
    private static final int MAX_DEPTH = 14;         // bottom nav lives shallow in the tree
    private static final long THROTTLE_MS = 300;     // min spacing between scans
    private static final long LIFT_AFTER_MS = 1600;  // no-hit window before lifting
    private static final long RESCAN_MS = 800;       // periodic re-check while blocking
    private static final long REBLOCK_GRACE_MS = 1200;
    private static final long CACHE_MS = 1500;       // armed-list cache lifetime

    /** Apps whose reels we can detect (also the package filter in the service config). */
    private static final String[] WATCHED = {
            "com.instagram.android", "com.instagram.lite",
            "com.facebook.katana", "com.facebook.lite",
    };

    private static final String[] REEL_QUOTES = {
            "One reel is never one reel. That is the design.",
            "The feed refreshes. Your attention does not.",
            "You came for one video. You would stay for forty.",
            "Scrolling is not rest. Boredom is.",
            "Ninety seconds and the urge is gone. Outlast it.",
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile HandlerThread scanThread = null;  // worker: all scanning happens here
    private volatile Handler scan = null;

    private volatile boolean blocking = false;      // our overlay is up
    private volatile String blockedPkg = null;
    private volatile View overlay = null;
    private volatile long lastHitAt = 0;            // last time reels were seen
    private long lastScanReal = 0;                  // throttle clock
    private volatile long overlayGoneAt = 0;        // for the re-block grace
    private volatile boolean scanBusy = false;      // dedup: never queue a scan backlog
    private ObjectAnimator breathAnim = null;
    private Runnable breathRunnable = null;
    private Bitmap mascotCache = null;

    /* urge surf (v2.6): a 90-second countdown on the block sheet — urges
     * measurably fade inside that window; riding it out is the win */
    private static final int URGE_SECONDS = 90;
    private Runnable urgeRunnable = null;
    private boolean urgeCounted = false;

    /* nav-tap memory (elapsedRealtime stamps) — tie-breaker for nav bars
     * that don't report which of their items is selected */
    private volatile long reelsClickAt = 0;   // last tap on the Reels nav item
    private volatile long otherClickAt = 0;   // last tap on any other nav item

    /* detection evidence for the on-device diagnostics log */
    private volatile String lastWhy = "init";
    private volatile String lastNavJson = "[]";
    private String pendingDump = null;        // deep capture of the tree at a HIT
    private long pendingDumpAt = 0;

    /* cached armed list (parsing JSON on every event is wasteful) */
    private volatile Set<String> armedCache = new HashSet<>();
    private volatile long armedCacheAt = 0;

    /* cached trusted list — trusted apps are never scanned or blocked */
    private volatile Set<String> trustedCache = new HashSet<>();
    private volatile long trustedCacheAt = 0;

    /* audio silencing — same two-pronged approach as the main shield */
    private final Object audioLock = new Object();
    private AudioManager audio = null;
    private volatile boolean audioSilenced = false;
    private boolean muteByFlag = false;
    private int savedVolume = -1;

    /* ---------- lifecycle ---------- */

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            audio = null;
            note(t);
        }
        try {
            HandlerThread ht = new HandlerThread("ReelsScan");
            ht.start();
            scanThread = ht;
            scan = new Handler(ht.getLooper());
        } catch (Throwable t) {
            scanThread = null;   // scans fall back to the main thread, throttled
            scan = null;
            note(t);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            // fresh connect: never inherit stale UI state
            blocking = false;
            blockedPkg = null;
            armedCacheAt = 0; // force an armed-list reload
            reelsClickAt = 0;
            otherClickAt = 0;
        } catch (Throwable t) {
            note(t);
        }
    }

    @Override
    public void onDestroy() {
        try { teardown(); } catch (Throwable t) { note(t); }
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // the user switched the service off: clean the screen immediately
        try { teardown(); } catch (Throwable t) { note(t); }
        return super.onUnbind(intent);
    }

    private void teardown() {
        blocking = false;
        blockedPkg = null;
        Handler h = scan;
        if (h != null) h.removeCallbacks(rescan);
        main.removeCallbacks(rescan);
        main.post(new Runnable() {
            @Override public void run() { removeOverlayUi(0); }
        });
        restoreAudio();
        HandlerThread ht = scanThread;
        scanThread = null;
        scan = null;
        if (ht != null) {
            try { ht.quitSafely(); } catch (Throwable ignored) { }
        }
    }

    /* ---------- shared prefs (same store the JavaScript bridge writes) ---------- */

    private SharedPreferences prefs() {
        return getSharedPreferences(BlockerService.PREFS, MODE_PRIVATE);
    }

    /** Packages armed for reels-only blocking (parsed at most once per CACHE_MS). */
    private boolean reelsArmed(String pkg) {
        if (pkg == null) return false;
        long now = now();
        if (now - armedCacheAt > CACHE_MS) {
            Set<String> out = new HashSet<>();
            try {
                JSONArray arr = new JSONArray(prefs().getString("reels_only", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i);
                    if (p != null && !p.isEmpty()) out.add(p);
                }
                armedCache = out;
                armedCacheAt = now;
            } catch (Throwable ignored) { }
        }
        return armedCache.contains(pkg);
    }

    private Set<String> blockedSet(SharedPreferences sp) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("blocked", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * True when the user marked this app trusted. The Reels shield stays
     * completely passive in trusted apps: no tree scans, no overlays, no
     * audio grab, no logs — nothing for the app to flag as third-party.
     */
    private boolean isTrusted(String pkg) {
        if (pkg == null) return false;
        long now = now();
        if (now - trustedCacheAt > CACHE_MS) {
            Set<String> out = new HashSet<>();
            try {
                JSONArray arr = new JSONArray(prefs().getString("trusted", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i);
                    if (p != null && !p.isEmpty()) out.add(p);
                }
                trustedCache = out;
                trustedCacheAt = now;
            } catch (Throwable ignored) { }
        }
        return trustedCache.contains(pkg);
    }

    /**
     * True when the main shield already blocks this app entirely (session,
     * schedule, quick block or daily limit). In that case we stay out of
     * the way — BlockerService owns the screen and the audio.
     */
    private boolean fullBlockedNow(String pkg) {
        if (pkg == null) return false;
        try {
            SharedPreferences sp = prefs();
            long now = System.currentTimeMillis();
            Set<String> blocked = blockedSet(sp);
            if (blocked.contains(pkg)) {
                if (sp.getBoolean("session_active", false)
                        && sp.getLong("session_end", 0) > now) return true;
                if (schedActive(sp, now)) return true;
            }
            if (sp.getLong("quick_until", 0) > now) {
                Set<String> quick = new HashSet<>();
                try {
                    JSONArray arr = new JSONArray(sp.getString("quick_blocked", "[]"));
                    for (int i = 0; i < arr.length(); i++) quick.add(arr.optString(i));
                } catch (Throwable ignored) { }
                if (quick.contains(pkg)) return true;
            }
            JSONObject limits = new JSONObject(sp.getString("limits", "{}"));
            int cap = limits.optInt(pkg, 0);
            if (cap > 0) {
                JSONObject used = new JSONObject(sp.getString("usage_json", "{}"));
                if (used.optLong(pkg, 0) >= cap * 60L) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean schedActive(SharedPreferences sp, long now) {
        try {
            if (!sp.getBoolean("sched_enabled", false)) return false;
            if (now < sp.getLong("pause_until", 0)) return false;
            JSONArray days = new JSONArray(sp.getString("sched_days", "[1,1,1,1,1,0,0]"));
            int s = sp.getInt("sched_start", 540);
            int e = sp.getInt("sched_end", 1020);
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(now + (long) (5.5 * 3600e3));
            int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int calDow = c.get(Calendar.DAY_OF_WEEK);                  // 1=Sun .. 7=Sat
            int jsDow = (calDow == Calendar.SUNDAY) ? 6 : calDow - 2;  // 0=Mon .. 6=Sun
            boolean over = s > e; // overnight window
            boolean inWin = over ? (min >= s || min < e) : (min >= s && min < e);
            boolean dayOk = over
                    ? (min >= s ? days.optInt(jsDow, 0) == 1 : days.optInt((jsDow + 6) % 7, 0) == 1)
                    : days.optInt(jsDow, 0) == 1;
            return inWin && dayOk;
        } catch (Throwable ex) {
            return false;
        }
    }

    /* ---------- event handling ---------- */

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // A crash here (or anywhere else in the process) makes the system
        // switch the service off as "malfunctioning" — nothing may escape.
        try {
            int type = event.getEventType();
            CharSequence p = event.getPackageName();
            if (p == null) return;
            String pkg = p.toString();
            if (!isWatched(pkg)) return;
            if (isTrusted(pkg)) return; // trusted app: never scan, never react
            if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                noteTabClick(event, pkg); // nav taps are strong tab-switch evidence
                return;
            }
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
            long t = SystemClock.elapsedRealtime();
            if (t - lastScanReal < THROTTLE_MS) return; // cheap check first
            lastScanReal = t;
            if (!blocking && !reelsArmed(pkg)) return;  // nothing to do for this app
            postScan(pkg);
        } catch (Throwable t) {
            note(t);
        }
    }

    @Override
    public void onInterrupt() { /* nothing to say */ }

    /** Runs one scan on the worker thread; skips if one is already running. */
    private void postScan(final String pkg) {
        Handler h = scan;
        if (h == null) {
            try { evaluate(pkg); } catch (Throwable t) { note(t); }
            return;
        }
        if (scanBusy) return; // drop the event — the next one re-triggers us
        scanBusy = true;
        h.post(new Runnable() {
            @Override public void run() {
                scanBusy = false;
                try { evaluate(pkg); } catch (Throwable t) { note(t); }
            }
        });
    }

    /**
     * The state machine. A scan result of HIT means reels are on screen,
     * MISS means we inspected a watched app and reels are gone, UNKNOWN
     * means we couldn't inspect (transition, or the active window belongs
     * to another app) — the current state is kept. Runs on the worker.
     */
    private void evaluate(String eventPkg) {
        if (!blocking) {
            String pkg = eventPkg != null ? eventPkg : blockedPkg;
            if (pkg == null || isTrusted(pkg) || !reelsArmed(pkg) || fullBlockedNow(pkg)) return;
            if (scanRoot() == HIT) {
                recordDiag("hit", pkg);
                startBlocking(pkg);
            }
            return;
        }
        // blocking in progress
        String pkg = blockedPkg;
        boolean armed = reelsArmed(pkg);
        boolean full = fullBlockedNow(pkg);
        if (!armed || full || isTrusted(pkg)) { stopBlocking(); return; }

        int r = scanRoot();
        if (r == HIT) {
            lastHitAt = now();
            if (overlay == null && now() - overlayGoneAt > REBLOCK_GRACE_MS) {
                recordDiag("reblock", pkg);
                showOverlay(pkg); // they found their way back to reels
            }
            return;
        }
        if (r == MISS) {
            recordDiag("lift", pkg);
            stopBlocking();
            return;
        }
        // UNKNOWN: the tree wasn't inspectable — is a watched app still in front?
        if (overlay == null && overlayGoneAt > 0 && now() - overlayGoneAt > 4000) {
            // the user already dismissed the sheet; an undecidable tree is no
            // reason to keep their audio muted
            stopBlocking();
            return;
        }
        String fg = queryForeground(120000);
        if (fg != null && !isWatched(fg)) stopBlocking(); // home / another app
        // fg == null (no recent event) or still ours: keep guarding
    }

    /* ---------- detection ---------- */

    private static final int HIT = 1, MISS = 0, UNKNOWN = -1;

    /** Node + tree depth pair for the capped BFS. */
    private static final class Nd {
        final AccessibilityNodeInfo node;
        final int depth;
        Nd(AccessibilityNodeInfo n, int d) { node = n; depth = d; }
    }

    /** One bottom-nav item candidate: normalized label, selection flag, screen Y. */
    private static final class Rec {
        final String low;
        final boolean sel;
        final int cy;
        Rec(String low, boolean sel, int cy) { this.low = low; this.sel = sel; this.cy = cy; }
    }

    /** The best nav row a scan found (its items + a preference score). */
    private static final class NavRow {
        final ArrayList<Rec> items;
        final int score;
        NavRow(ArrayList<Rec> items, int score) { this.items = items; this.score = score; }
    }

    /** Labels Instagram's bottom bar uses for its tabs. */
    private static final String[] IG_TABS = {
            "home", "search", "reels", "shop", "saved", "profile",
            "create", "notifications", "messages", "messenger", "direct",
    };

    /** Labels Facebook's bottom bar uses for its tabs. */
    private static final String[] FB_TABS = {
            "home", "friends", "video", "watch", "reels", "marketplace",
            "notifications", "menu", "groups", "gaming", "news", "feed",
            "shortcuts", "profile",
    };

    private static boolean isWatched(String pkg) {
        for (String w : WATCHED) if (w.equals(pkg)) return true;
        return false;
    }

    private static boolean isFacebook(String pkg) {
        return pkg != null && pkg.startsWith("com.facebook.");
    }

    private static boolean isTabLabel(String low, boolean facebook) {
        String[] set = facebook ? FB_TABS : IG_TABS;
        for (String t : set) if (low.startsWith(t)) return true;
        return false;
    }

    /** Reels-ish: the Reels tab on Instagram; Reels/Video/Watch on Facebook. */
    private static boolean isReelsLabel(String low, boolean facebook) {
        if (low.startsWith("reel")) return true;
        return facebook && (low.startsWith("video") || low.startsWith("watch"));
    }

    /** Selected/checked in the accessibility sense (state descriptions need API 30). */
    private static boolean anySel(AccessibilityNodeInfo n) {
        try {
            if (n.isSelected() || n.isChecked()) return true;
            if (Build.VERSION.SDK_INT >= 30) {
                // getStateDescription() only exists from API 30 — calling it
                // on older phones crashes with NoSuchMethodError (v2.2 bug)
                String st = chars(n.getStateDescription());
                if (!st.isEmpty() && st.toLowerCase(Locale.US).contains("select")) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Content description or text, trimmed and lowercased. */
    private static String labelOf(AccessibilityNodeInfo n) {
        String s = chars(n.getContentDescription());
        if (s.isEmpty()) s = chars(n.getText());
        return s.trim().toLowerCase(Locale.US);
    }

    /** Action buttons of the fullscreen reel player's right-hand rail. */
    private static final String[] RAIL_LABELS = {
            "like", "comment", "comments", "send", "share", "save",
            "more", "react",
    };

    /**
     * Canonical rail label, or null. The separator rule keeps feed text
     * like "liked by X…" (a like count, not a button) from matching.
     */
    private static String railBase(String low) {
        for (String b : RAIL_LABELS) {
            if (low.equals(b)) return b;
            if (low.length() > b.length() && low.startsWith(b)) {
                char c = low.charAt(b.length());
                if (c == ' ' || c == ',' || c == '.' || c == ':' || c == '·') return b;
            }
        }
        return null;
    }

    /**
     * Player probe, run on every visited node: small button-sized nodes in
     * the right 40% of the screen, mid-to-low on it. One slot per canonical
     * label — first sighting wins, position kept for the stack test.
     */
    private static void collectRail(AccessibilityNodeInfo n, int sw, int sh,
                                    ArrayList<String> labs, ArrayList<int[]> pos) {
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.width() <= 0 || b.height() <= 0) return;
            if (b.width() > sw * 0.30 || b.height() > sh * 0.10) return; // buttons, not text blocks
            int cx = b.centerX();
            int cy = b.centerY();
            if (cx < sw * 0.60 || cy < sh * 0.18 || cy > sh * 0.97) return;
            String low = labelOf(n);
            if (low.isEmpty() || low.length() > 32) return;
            String base = railBase(low);
            if (base == null || labs.contains(base)) return;
            labs.add(base);
            pos.add(new int[]{cx, cy});
        } catch (Throwable ignored) { }
    }

    /**
     * The fullscreen player signature: three-plus DIFFERENT rail actions
     * in one tight vertical strip at the right edge, spread over three-plus
     * height bands. The feed's action row is horizontal and can place at
     * most two rail-ish labels at the right edge (a card's "···" menu and
     * one end-of-row button), so it can never satisfy this.
     */
    private static boolean railSignature(ArrayList<String> labs, ArrayList<int[]> pos,
                                         int sw, int sh) {
        try {
            if (labs.size() < 3) return false;
            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
            int minCy = Integer.MAX_VALUE, maxCy = Integer.MIN_VALUE;
            HashSet<Integer> bands = new HashSet<>();
            int band = Math.max(1, sh / 25); // ~4% buckets
            for (int[] p : pos) {
                if (p[0] < minCx) minCx = p[0];
                if (p[0] > maxCx) maxCx = p[0];
                if (p[1] < minCy) minCy = p[1];
                if (p[1] > maxCy) maxCy = p[1];
                bands.add(p[1] / band);
            }
            if (maxCx - minCx > sw * 0.14) return false; // icons share one vertical strip
            if (maxCy - minCy < sh * 0.10) return false;  // tall stack, not a single row
            return bands.size() >= 3;                     // truly stacked
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fullscreen reel player found — opened from the feed, explore or a chat. */
    private int playerHit(StringBuilder dump, String railJson) {
        lastWhy = "player";
        lastNavJson = railJson;
        if (dump != null) { pendingDump = dump.toString(); pendingDumpAt = now(); }
        return HIT;
    }

    /**
     * Walks the active window's tree looking for the BOTTOM NAVIGATION BAR:
     * a parent whose children include at least three distinct tab labels
     * (Home, Search, Reels, Profile…) sitting in the bottom band of the
     * screen. The selected item of that row — and only that item — decides
     * whether the reels tab is on screen. This is what keeps Instagram's
     * home feed and DMs unblocked: a lone "Reels"-labeled feed post or DM
     * preview has no tab siblings, and even a stale selected flag on the
     * nav's Reels item loses to whichever tab is actually selected.
     */
    private int scanRoot() {
        try {
            pendingDump = null;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return UNKNOWN;
            CharSequence rp = root.getPackageName();
            if (rp == null || !isWatched(rp.toString())) return UNKNOWN;
            boolean fb = isFacebook(rp.toString());

            boolean deep = false;
            try { deep = prefs().getBoolean("reels_deep", false); } catch (Throwable ignored) { }
            StringBuilder dump = deep ? new StringBuilder() : null;

            DisplayMetrics dm = getResources().getDisplayMetrics();
            final int sw = dm.widthPixels;
            final int sh = dm.heightPixels;
            float minCy = (sh >= sw)
                    ? sh * 0.70f   // portrait: the nav lives in the bottom 30%
                    : sh * 0.55f;  // landscape: keep some slack

            ArrayDeque<Nd> queue = new ArrayDeque<>();
            queue.add(new Nd(root, 0));
            int seen = 0;
            NavRow best = null;
            boolean fbLegacyHit = false;
            /* fullscreen reel-player probe: right-edge rail buttons, one
             * slot per canonical label (v2.5) */
            ArrayList<String> railLab = new ArrayList<>();
            ArrayList<int[]> railPos = new ArrayList<>();

            while (!queue.isEmpty() && seen < MAX_NODES) {
                Nd nd = queue.poll();
                seen++;
                AccessibilityNodeInfo n = nd.node;
                if (dump != null) appendDump(dump, nd.depth, n);
                if (!fbLegacyHit && fb) {
                    // proven v2.3 matcher — Facebook fallback when no nav row is found
                    String fl = labelOf(n);
                    if (!fl.isEmpty() && isReelsLabel(fl, true) && anySel(n)) fbLegacyHit = true;
                }
                if (railLab.size() < 6) collectRail(n, sw, sh, railLab, railPos);

                if (nd.depth >= MAX_DEPTH) continue;
                int kids = n.getChildCount();
                if (kids <= 0) continue;
                ArrayList<AccessibilityNodeInfo> cs = new ArrayList<>(kids);
                for (int i = 0; i < kids; i++) {
                    AccessibilityNodeInfo c = null;
                    try { c = n.getChild(i); } catch (Throwable ignored) { }
                    if (c != null) { cs.add(c); queue.add(new Nd(c, nd.depth + 1)); }
                }
                if (kids > 12) continue; // a nav row has a handful of items, not a grid

                ArrayList<Rec> row = new ArrayList<>(kids);
                ArrayList<AccessibilityNodeInfo> used = new ArrayList<>();
                for (AccessibilityNodeInfo c : cs) {
                    String low = labelOf(c);
                    if (low.isEmpty() || low.length() > 24 || !isTabLabel(low, fb)) continue;
                    row.add(recFrom(c, c, low));
                    used.add(c);
                }
                if (row.size() < 3) {
                    // the label may sit on an icon inside each item — probe one level deeper
                    for (AccessibilityNodeInfo c : cs) {
                        if (used.contains(c)) continue;
                        int k = c.getChildCount();
                        if (k <= 0 || k > 4) continue;
                        for (int j = 0; j < k; j++) {
                            AccessibilityNodeInfo g = null;
                            try { g = c.getChild(j); } catch (Throwable ignored) { }
                            if (g == null) continue;
                            String gl = labelOf(g);
                            if (gl.isEmpty() || gl.length() > 24 || !isTabLabel(gl, fb)) continue;
                            row.add(recFrom(c, g, gl));
                            break; // one label per item
                        }
                    }
                }
                if (row.size() < 3) continue;
                HashSet<String> labs = new HashSet<>();
                for (Rec r : row) labs.add(r.low);
                if (labs.size() < 3) continue;
                long sum = 0; int cnt = 0;
                for (Rec r : row) if (r.cy > 0) { sum += r.cy; cnt++; }
                boolean bottom = cnt > 0 && (sum / (double) cnt) >= minCy;
                if (cnt > 0 && !bottom) continue; // mid-screen label row = content, not the nav
                int score = labs.size() + (bottom ? 10 : 0);
                if (best == null || score > best.score) best = new NavRow(row, score);
            }

            boolean rail = railSignature(railLab, railPos, sw, sh);
            String railJson = "[]";
            if (rail) {
                try { // rail evidence for the Guard tab's detection log
                    JSONArray a = new JSONArray();
                    for (String l : railLab) a.put(new JSONObject().put("l", l));
                    railJson = a.toString();
                } catch (Throwable ignored) { }
            }

            if (best == null) {
                if (fb && fbLegacyHit) {
                    lastWhy = "fb-fallback";
                    lastNavJson = "[]";
                    if (dump != null) { pendingDump = dump.toString(); pendingDumpAt = now(); }
                    return HIT;
                }
                if (rail) return playerHit(dump, railJson);
                // a watched app on a screen without its bottom nav (DM thread,
                // settings, camera…) is by definition not the reels tab
                lastWhy = "no-nav";
                lastNavJson = "[]";
                return MISS;
            }
            lastNavJson = navJson(best.items);

            ArrayList<Rec> active = new ArrayList<>();
            for (Rec r : best.items) if (r.sel) active.add(r);
            long el = SystemClock.elapsedRealtime();

            if (active.size() == 1) {
                Rec a = active.get(0);
                if (isReelsLabel(a.low, fb)) {
                    if (otherClickAt > reelsClickAt && el - otherClickAt < 45000) {
                        if (rail) return playerHit(dump, railJson); // the player on top beats tap memory
                        lastWhy = "reels-stale";   // a newer tap on another tab says we left
                        return MISS;
                    }
                    lastWhy = "reels-selected";
                    if (dump != null) { pendingDump = dump.toString(); pendingDumpAt = now(); }
                    return HIT;
                }
                if (rail) return playerHit(dump, railJson); // that feed tap led into a reel
                int c = a.low.indexOf(',');
                lastWhy = (c > 0 ? a.low.substring(0, c) : a.low) + "-selected";
                return MISS;
            }
            if (active.size() > 1) {
                for (Rec r : active) {
                    if (!isReelsLabel(r.low, fb)) {
                        if (rail) return playerHit(dump, railJson);
                        lastWhy = "multi-selected"; // a real tab is active; the reels flag is stale
                        return MISS;
                    }
                }
                lastWhy = "multi-reels";
                if (dump != null) { pendingDump = dump.toString(); pendingDumpAt = now(); }
                return HIT;
            }
            // nothing reports selected — recent nav taps decide
            if (reelsClickAt > 0 && reelsClickAt >= otherClickAt && el - reelsClickAt < 20000) {
                lastWhy = "reels-click";
                if (dump != null) { pendingDump = dump.toString(); pendingDumpAt = now(); }
                return HIT;
            }
            if (otherClickAt > reelsClickAt && el - otherClickAt < 20000) {
                if (rail) return playerHit(dump, railJson); // that tap led into a reel
                lastWhy = "other-click";
                return MISS;
            }
            if (rail) return playerHit(dump, railJson); // player on top of the feed
            lastWhy = "nav-idle";
            return UNKNOWN;
        } catch (Throwable t) {
            note(t);
            return UNKNOWN;
        }
    }

    /** Turns a nav item into a Rec: label, selection (item or its labeled icon), screen Y. */
    private Rec recFrom(AccessibilityNodeInfo item, AccessibilityNodeInfo labeled, String low) {
        Rect b = new Rect();
        try { labeled.getBoundsInScreen(b); } catch (Throwable ignored) { }
        int cy = (b.width() > 0 || b.height() > 0) ? b.centerY() : -1;
        boolean sel = anySel(labeled) || (item != labeled && anySel(item));
        return new Rec(low, sel, cy);
    }

    /** Compact nav snapshot for the diagnostics log: [{"l":"home","s":1},…]. */
    private static String navJson(ArrayList<Rec> row) {
        try {
            JSONArray a = new JSONArray();
            for (Rec r : row) {
                JSONObject o = new JSONObject();
                o.put("l", r.low.length() > 14 ? r.low.substring(0, 14) : r.low);
                if (r.sel) o.put("s", 1);
                if (r.cy > 0) o.put("y", r.cy);
                a.put(o);
            }
            return a.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    /** One compact line per node of the walk (deep capture only). */
    private static void appendDump(StringBuilder sb, int depth, AccessibilityNodeInfo n) {
        if (sb.length() > 22000) return;
        try {
            String cls = chars(n.getClassName());
            int dot = cls.lastIndexOf('.');
            if (dot >= 0) cls = cls.substring(dot + 1);
            String lab = chars(n.getContentDescription());
            if (lab.isEmpty()) lab = chars(n.getText());
            if (lab.length() > 16) lab = lab.substring(0, 16);
            lab = lab.replace('\n', ' ').replace('\'', ' ');
            Rect b = new Rect();
            try { n.getBoundsInScreen(b); } catch (Throwable ignored) { }
            sb.append(depth).append(' ').append(cls);
            if (!lab.isEmpty()) sb.append(" '").append(lab).append('\'');
            if (n.isSelected()) sb.append(" SEL");
            if (n.isChecked()) sb.append(" CHK");
            if (b.width() > 0 || b.height() > 0)
                sb.append(" (").append(b.centerX()).append(',').append(b.centerY()).append(')');
            sb.append('\n');
        } catch (Throwable ignored) { }
    }

    /** Remembers taps on bottom-nav items: Reels vs any other tab. */
    private void noteTabClick(AccessibilityEvent e, String pkg) {
        try {
            String lab = chars(e.getContentDescription());
            if (lab.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence t : e.getText()) {
                    if (t == null) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(t);
                }
                lab = sb.toString();
            }
            if (lab.isEmpty()) {
                AccessibilityNodeInfo s = null;
                try { s = e.getSource(); } catch (Throwable ignored) { }
                if (s != null) {
                    lab = chars(s.getContentDescription());
                    if (lab.isEmpty()) lab = chars(s.getText());
                }
            }
            String low = lab.trim().toLowerCase(Locale.US);
            if (low.isEmpty() || low.length() > 24) return;
            boolean fb = isFacebook(pkg);
            if (!isTabLabel(low, fb)) return;
            long t = SystemClock.elapsedRealtime();
            if (isReelsLabel(low, fb)) reelsClickAt = t;
            else otherClickAt = t;
        } catch (Throwable t) {
            note(t);
        }
    }

    /** Appends a decision + its evidence to the on-device log (max 8, newest last). */
    private void recordDiag(String decision, String pkg) {
        try {
            SharedPreferences sp = prefs();
            JSONArray arr;
            try { arr = new JSONArray(sp.getString("reels_diag", "[]")); } catch (Throwable t) { arr = new JSONArray(); }
            JSONObject o = new JSONObject();
            o.put("t", now());
            o.put("pkg", pkg == null ? "" : pkg);
            o.put("d", decision);
            o.put("why", lastWhy);
            try { o.put("nav", new JSONArray(lastNavJson)); } catch (Throwable ignored) { }
            long el = SystemClock.elapsedRealtime();
            if (reelsClickAt > 0) o.put("cr", (el - reelsClickAt) / 1000);
            if (otherClickAt > 0) o.put("co", (el - otherClickAt) / 1000);
            arr.put(o);
            while (arr.length() > 8) arr.remove(0);
            SharedPreferences.Editor ed = sp.edit().putString("reels_diag", arr.toString());
            if (pendingDump != null) {
                ed.putString("reels_dump", pendingDump).putLong("reels_dump_t", pendingDumpAt);
                pendingDump = null;
            }
            ed.apply();
        } catch (Throwable ignored) { }
    }

    private static String chars(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /** Latest foreground app within the lookback, or null (mirror of the main shield). */
    private String queryForeground(long lookbackMs) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            String latest = null;
            UsageEvents events = usm.queryEvents(now - lookbackMs, now);
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    latest = ev.getPackageName();
            }
            return latest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long now() { return System.currentTimeMillis(); }

    /* ---------- blocking / releasing ---------- */

    private void startBlocking(String pkg) {
        blocking = true;
        blockedPkg = pkg;
        lastHitAt = now();
        vibrate();
        logReelsAttempt(pkg);
        silenceAudio();
        showOverlay(pkg);
        scheduleRescan(); // keeps state fresh even if events starve
    }

    private void stopBlocking() {
        blocking = false;
        blockedPkg = null;
        Handler h = scan;
        if (h != null) h.removeCallbacks(rescan);
        main.removeCallbacks(rescan);
        main.post(new Runnable() {
            @Override public void run() { removeOverlayUi(0); }
        });
        restoreAudio();
    }

    private void scheduleRescan() {
        Handler h = scan;
        if (h != null) {
            h.removeCallbacks(rescan);
            h.postDelayed(rescan, RESCAN_MS);
        } else {
            main.removeCallbacks(rescan);
            main.postDelayed(rescan, RESCAN_MS);
        }
    }

    /** Periodic re-check while the block sheet is up. */
    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            if (!blocking) return;
            try {
                if (now() - lastHitAt > LIFT_AFTER_MS) {
                    evaluate(blockedPkg); // confirms the lift (or refreshes lastHitAt)
                    if (!blocking) return;
                }
            } catch (Throwable t) {
                note(t);
            }
            scheduleRescan();
        }
    };

    /* ---------- attempt log (same store the app reads for stats) ---------- */

    private String istDayKey(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(ms + (long) (5.5 * 3600e3));
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private void logReelsAttempt(String pkg) {
        try {
            SharedPreferences sp = prefs();
            long ts = now();
            String day = istDayKey(ts);
            SharedPreferences.Editor ed = sp.edit();
            JSONArray entries;
            try {
                entries = new JSONArray(sp.getString("block_log", "[]"));
            } catch (Throwable t) {
                entries = new JSONArray();
            }
            JSONObject e = new JSONObject();
            e.put("t", ts);
            e.put("p", pkg);
            e.put("n", appLabel(pkg) + " (Reels)");
            entries.put(e);
            while (entries.length() > 200) entries.remove(0);
            ed.putString("block_log", entries.toString());
            int today = day.equals(sp.getString("block_day", "")) ? sp.getInt("block_today", 0) : 0;
            ed.putString("block_day", day)
              .putInt("block_today", today + 1)
              .putInt("block_count", sp.getInt("block_count", 0) + 1)
              .apply();
        } catch (Throwable ignored) { }
    }

    private String appLabel(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable e) {
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
        } catch (Throwable ignored) { }
    }

    /* ---------- audio silencing (same strategy as BlockerService) ---------- */

    private void silenceAudio() {
        AudioManager a = audio;
        if (a == null) return;
        synchronized (audioLock) {
            try {
                a.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                if (!audioSilenced) {
                    try {
                        a.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_MUTE, 0);
                        muteByFlag = true;
                    } catch (Throwable e) {
                        savedVolume = a.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (savedVolume > 0)
                            a.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                        muteByFlag = false;
                    }
                    audioSilenced = true;
                }
            } catch (Throwable ignored) { }
        }
    }

    private void restoreAudio() {
        AudioManager a = audio;
        if (a == null) return;
        synchronized (audioLock) {
            if (!audioSilenced) return;
            audioSilenced = false;
            try {
                if (muteByFlag) {
                    a.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_UNMUTE, 0);
                } else if (savedVolume > 0) {
                    a.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
                }
            } catch (Throwable ignored) { }
            try { a.abandonAudioFocus(focusListener); } catch (Throwable ignored) { }
            savedVolume = -1;
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int focusChange) { }
            };

    /* ---------- breadcrumbs (so a future failure leaves a trace) ---------- */

    private void note(Throwable t) {
        try {
            SharedPreferences sp = prefs();
            String last = t == null ? "?" : t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            sp.edit()
              .putInt("reels_err_count", sp.getInt("reels_err_count", 0) + 1)
              .putString("reels_err_last", now() + " " + last)
              .apply();
        } catch (Throwable ignored) { }
    }

    /* ---------- the reels block screen ---------- */

    /* liquid-glass palette (v2.8): used ONLY when the app's color mood is
     * "glass" — every other theme keeps the classic aurora look untouched */
    private static final int GL_SILVER = 0xFFF7FAFD;
    private static final int GL_MUTED  = 0xFFA7B2C2;
    private static final int GL_FAINT  = 0xFF8A94A6;
    private static final int GL_ONBTN  = 0xFF121824;

    /** True when the WebView picked the liquid-glass color mood. */
    private boolean glassTheme() {
        try {
            return "glass".equals(prefs().getString("ui_theme", "aurora"));
        } catch (Throwable t) {
            return false;
        }
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
        } catch (Throwable e) {
            mascotCache = null;
        }
        return mascotCache;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private void showOverlay(final String pkg) {
        main.post(new Runnable() {
            @Override public void run() { showOverlayUi(pkg); }
        });
    }

    private void showOverlayUi(final String pkg) {
        removeOverlayUi(0);

        String label = appLabel(pkg);

        final boolean glass = glassTheme();
        /* real frosted blur behind the overlay needs API 31+ and
         * cross-window blur to be enabled right now — checked, never assumed */
        final boolean blurBehind;
        if (glass && Build.VERSION.SDK_INT >= 31) {
            boolean ok = false;
            try {
                WindowManager wmc = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                ok = wmc != null && wmc.isCrossWindowBlurEnabled();
            } catch (Throwable ignored) { }
            blurBehind = ok;
        } else {
            blurBehind = false;
        }

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(glass
                ? Color.parseColor(blurBehind ? "#D90B0E14" : "#F20B0E14")
                : Color.parseColor("#0A1210"));
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
            img.setLayoutParams(new LinearLayout.LayoutParams(dp(120), dp(120)));
            box.addView(img);
            box.addView(gap(dp(20)));
        }

        TextView title = new TextView(this);
        title.setText("Reels are blocked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(label + " stays open — Reels doesn't.");
        sub.setTextColor(glass ? GL_SILVER : Color.parseColor("#34D399"));
        sub.setTextSize(15);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        sub.setLayoutParams(slp);
        box.addView(sub);

        TextView note = new TextView(this);
        note.setText("Feed, search, messages and profile still work. Reels are off limits — the tab, and ones opened from the feed.");
        note.setTextColor(glass ? GL_MUTED : Color.parseColor("#8FA79B"));
        note.setTextSize(13.5f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(6), dp(8), dp(6), 0);
        box.addView(note);

        /* the mindful moment */
        final View circle = new View(this);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(glass ? 0x2EFFFFFF : Color.parseColor("#12271F"));
        cd.setStroke(dp(2), glass ? 0x99F7FAFD : Color.parseColor("#34D399"));
        circle.setBackground(cd);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(86), dp(86));
        clp.topMargin = dp(26);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        circle.setLayoutParams(clp);
        box.addView(circle);

        final TextView breath = new TextView(this);
        breath.setText("Breathe in…");
        breath.setTextColor(glass ? GL_MUTED : Color.parseColor("#8FA79B"));
        breath.setTextSize(13.5f);
        breath.setTypeface(Typeface.DEFAULT_BOLD);
        breath.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        breath.setLayoutParams(blp);
        box.addView(breath);

        try {
            breathAnim = ObjectAnimator.ofPropertyValuesHolder(circle,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.32f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.32f));
            breathAnim.setDuration(4000L);
            breathAnim.setRepeatCount(ValueAnimator.INFINITE);
            breathAnim.setRepeatMode(ValueAnimator.REVERSE);
            breathAnim.start();
        } catch (Throwable ignored) { }

        breathRunnable = new Runnable() {
            boolean phase = true;
            @Override public void run() {
                if (overlay == null) return;
                phase = !phase;
                breath.setText(phase ? "Breathe in…" : "Breathe out…");
                main.postDelayed(this, 4000L);
            }
        };
        main.postDelayed(breathRunnable, 4000L);

        /* urge surf (v2.6): ride out the pull for 90 seconds — a slim bar
         * drains and the label swaps to a gentle "it passed" once done */
        final TextView urge = new TextView(this);
        urge.setText("Urge surfing \u00b7 90s to go");
        urge.setTextColor(glass ? GL_MUTED : Color.parseColor("#8FA79B"));
        urge.setTextSize(13.5f);
        urge.setTypeface(Typeface.DEFAULT_BOLD);
        urge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(22);
        urge.setLayoutParams(ulp);
        box.addView(urge);

        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        try {
            GradientDrawable track = new GradientDrawable();
            track.setColor(glass ? 0x33FFFFFF : Color.parseColor("#1E3A2F"));
            track.setCornerRadius(dp(3));
            GradientDrawable fill = new GradientDrawable();
            if (glass) {
                fill.setOrientation(GradientDrawable.Orientation.TL_BR);
                fill.setColors(new int[]{ Color.WHITE, Color.parseColor("#C3CEDD") });
            } else {
                fill.setColor(Color.parseColor("#34D399"));
            }
            fill.setCornerRadius(dp(3));
            ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
            LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            bar.setProgressDrawable(ld);
        } catch (Throwable ignored) { }
        bar.setMax(URGE_SECONDS);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7));
        blp2.topMargin = dp(10);
        bar.setLayoutParams(blp2);
        box.addView(bar);

        urgeCounted = false;
        urgeRunnable = new Runnable() {
            int left = URGE_SECONDS;
            @Override public void run() {
                if (overlay == null || urgeRunnable != this) return;
                left--;
                if (left > 0) {
                    bar.setProgress(URGE_SECONDS - left);
                    urge.setText("Urge surfing \u00b7 " + left + "s to go");
                    main.postDelayed(this, 1000L);
                    return;
                }
                bar.setProgress(URGE_SECONDS);
                urge.setText("The urge has passed \u2014 it always does.");
                urge.setTextColor(glass ? GL_SILVER : Color.parseColor("#34D399"));
                if (!urgeCounted) {
                    urgeCounted = true; // one per sheet: the user outlasted it
                    try {
                        prefs().edit().putInt("urges_surfed",
                                prefs().getInt("urges_surfed", 0) + 1).apply();
                    } catch (Throwable ignored) { }
                }
                vibrate();
            }
        };
        main.postDelayed(urgeRunnable, 1000L);

        TextView quote = new TextView(this);
        int qi = (int) (now() / 60000L % REEL_QUOTES.length);
        quote.setText('"' + REEL_QUOTES[qi] + '"');
        quote.setTextColor(glass ? GL_FAINT : Color.parseColor("#6E8A7C"));
        quote.setTextSize(12.5f);
        quote.setGravity(Gravity.CENTER);
        quote.setPadding(dp(10), dp(18), dp(10), 0);
        box.addView(quote);

        Button leave = new Button(this);
        leave.setText("Leave Reels");
        leave.setTextColor(glass ? GL_ONBTN : Color.parseColor("#06251C"));
        leave.setTextSize(16);
        leave.setTypeface(Typeface.DEFAULT_BOLD);
        leave.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setOrientation(GradientDrawable.Orientation.TL_BR);
        if (glass) bg.setColors(new int[]{ Color.WHITE, Color.parseColor("#C3CEDD") });
        else bg.setColor(Color.parseColor("#34D399"));
        bg.setCornerRadius(dp(30));
        leave.setBackground(bg);
        leave.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(30);
        leave.setLayoutParams(lp);
        leave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                removeOverlayUi(now());
                try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) { }
            }
        });
        box.addView(leave);

        TextView tail = new TextView(this);
        tail.setText("Tapping it returns you to " + label + " — everything but Reels stays open.");
        tail.setTextColor(glass ? GL_MUTED : Color.parseColor("#8FA79B"));
        tail.setTextSize(13.5f);
        tail.setGravity(Gravity.CENTER);
        tail.setPadding(dp(8), dp(16), dp(8), dp(4));
        box.addView(tail);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        // Accessibility services may always add windows of this type —
        // no "display over other apps" grant is needed (API 22+, minSdk 24)
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 27) {
            wlp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (blurBehind) {
            try {
                wlp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                wlp.setBlurBehindRadius(dp(26));
            } catch (Throwable ignored) { }
        }
        try {
            wm.addView(root, wlp);
            overlay = root;
            overlayGoneAt = 0;
        } catch (Throwable e) {
            overlay = null;
            alertNotify(pkg); // last resort: kick out + tell the user
            try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) { }
        }
    }

    /** Removes the sheet. goneAt > 0 marks a user dismissal (grace before re-block). Main thread only. */
    private void removeOverlayUi(long goneAt) {
        if (breathAnim != null) {
            try { breathAnim.cancel(); } catch (Throwable ignored) { }
            breathAnim = null;
        }
        if (breathRunnable != null) {
            main.removeCallbacks(breathRunnable);
            breathRunnable = null;
        }
        if (urgeRunnable != null) {
            main.removeCallbacks(urgeRunnable);
            urgeRunnable = null;
        }
        urgeCounted = false;
        if (overlay != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(overlay);
            } catch (Throwable ignored) { }
            overlay = null;
        }
        overlayGoneAt = goneAt;
    }

    /* ---------- fallback notification (overlay could not be shown) ---------- */

    private void alertNotify(String pkg) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT, "Reels shield",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Alerts when the Reels tab is blocked");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 4, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ALERT)
                    : new Notification.Builder(this);
            Notification n = b
                    .setSmallIcon(R.drawable.ic_stat_ring)
                    .setContentTitle("Reels blocked in " + appLabel(pkg))
                    .setContentText("Open ReCalm and re-enable the Reels Shield")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setColor(Color.parseColor("#34D399"))
                    .build();
            nm.notify(NOTIF_ALERT, n);
        } catch (Throwable ignored) { }
    }
}
