package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Men Cave information row under the clock: the NIV verse of the day (about 60%) and Liverpool FC's last
 * result and next fixture (about 40%). Read-only: nothing here takes remote focus, so OK always opens the
 * focused app. Sources need no credentials, so none ship in the APK:
 *  - Bible Gateway's free verse-of-the-day feed (offered for display; names the translation);
 *  - ESPN's public team schedule for all competitions (results, and ?fixture=true for upcoming games).
 * The last good answers are cached; when a feed is down the cached data stays with its update time.
 * Live data only: nothing is ever filled in with sample scores or fixtures.
 */
final class InfoRow {
    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final String VERSE_URL = "https://www.biblegateway.com/votd/get/?format=json&version=NIV";
    private static final String TEAM_ID = "364";   // Liverpool on ESPN
    private static final String RESULTS_URL =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/" + TEAM_ID + "/schedule";
    private static final String FIXTURES_URL = RESULTS_URL + "?fixture=true";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=25.2048&longitude=55.2708"
            + "&current=temperature_2m,weather_code&timezone=Asia%2FDubai";
    private static final long FOOTBALL_EVERY_MS = 15 * 60_000L;
    private static final long TICK_MS = 60_000L;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int DETAIL = 0xFFE6EBF2;   // match details: near-white
    private static final int DIM = 0xFFB8C2CF;
    private static final int LFC_RED = 0xFFE31B23;  // Liverpool red, used only as a small accent
    private static final int CARD = 0xE00A0E14;     // dark enough that the forest is only a faint texture
    private static final float VERSE_MAX_SP = 15f;  // 30 px on a 1920x1080 canvas at the TV's density 2
    private static final float VERSE_MIN_SP = 9f;

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final float density;

    private LinearLayout verseBlock;
    private TextView verseText, verseRef;
    private TextView lastLine, lastMeta, nextLine, nextMeta, footStatus;
    /** Dubai weather beside the clock, under the date (Open-Meteo, no key). */
    private TextView weather;

    private long lastFootballFetch;
    private boolean running;
    private boolean verseLoading, footballLoading;
    private long lastVerseTry;
    private LinearLayout verseCard;
    private GradientDrawable verseBg;
    private Runnable onVerseDown, onVerseFocus, onFootDown;
    private LinearLayout footCard;
    private GradientDrawable footBg;
    private static final long VERSE_RETRY_MS = 30 * 60_000L;
    private static final int HISTORY_MAX = 60;
    /** Layout test (started from the laptop only): sample long texts, clearly labelled, until Home is pressed. */
    private boolean testMode;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshIfDue();
            main.postDelayed(this, TICK_MS);
        }
    };

    InfoRow(Activity act, ViewGroup host) {
        this.act = act;
        this.prefs = act.getSharedPreferences("mencave_info", Activity.MODE_PRIVATE);
        this.density = act.getResources().getDisplayMetrics().density;
        layoutScreen(host);
        build(host);
        addWeather();
        showVerse();
        showFootball(false);
    }

    void start() {
        if (running) return;
        running = true;
        lastFootballFetch = 0;   // "refresh on launch": every return to Home asks again
        lastVerseTry = 0;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    void destroy() {
        stop();
        io.shutdownNow();
    }

    /**
     * {@code am start -n com.gathin.tvhome/.MainActivity --es mencave_test long} fills both cards with long sample
     * texts (marked LAYOUT TEST) to check the fit; any normal Home press goes back to live data.
     */
    void applyTestIntent(android.content.Intent intent) {
        if (intent == null) return;
        boolean want = "long".equals(intent.getStringExtra("mencave_test"));
        if (want == testMode) return;
        testMode = want;
        showVerse();
        showFootball(false);
    }

    /** Down from the verse card returns to the apps; focus on the card hides the grid's glass highlight. */
    void setNavigation(Runnable onVerseDown, Runnable onFootDown, Runnable onFocus) {
        this.onVerseDown = onVerseDown;
        this.onFootDown = onFootDown;
        this.onVerseFocus = onFocus;
    }

    /** Up from the first app row under the Liverpool card lands here. */
    boolean focusFootball() {
        return footCard != null && footCard.requestFocus();
    }

    /** Up from the first app row, under the verse card, lands here. */
    boolean focusVerse() {
        return verseCard != null && verseCard.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    /**
     * Screen split on a 16:9 TV: header 12%, cards 38%, apps 50%. Sized from the real screen height so 1080p and
     * 4K render the same proportions.
     */
    private void layoutScreen(ViewGroup host) {
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        View content = (View) host.getParent();
        // Header: tighter top padding and a slightly smaller clock so the header fits the top 12%.
        content.setPadding(content.getPaddingLeft(), Math.round(screenH * 0.025f), content.getPaddingRight(),
                content.getPaddingBottom());
        TextView clock = act.findViewById(R.id.clock);
        if (clock != null) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.getTextSize() * 0.82f);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) host.getLayoutParams();
        lp.height = Math.round(screenH * 0.36f);
        lp.topMargin = Math.round(screenH * 0.02f);
        host.setLayoutParams(lp);

        // A soft dark gradient behind the header, so white text reads over bright treetops.
        if (content.getParent() instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) content.getParent();
            View shade = new View(act);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xB3000000, 0x66000000, 0x00000000}));
            shade.setFocusable(false);
            shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(shade, root.indexOfChild(content),
                    new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(screenH * 0.22f)));
        }
    }

    private void build(ViewGroup host) {
        host.removeAllViews();
        host.setFocusable(false);
        host.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        host.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Verse card, 60% ----
        LinearLayout verse = card();
        verseCard = verse;
        verseBg = (GradientDrawable) verse.getBackground();
        verse.setFocusable(true);
        verse.setClickable(true);
        verse.setContentDescription("Verse of the day. Press OK to read it full screen.");
        verse.setOnClickListener(v -> openReader());
        verse.setOnFocusChangeListener((v, has) -> {
            verseBg.setStroke(has ? dp(3) : Math.max(1, Math.round(density / 2)), has ? 0xFFFFFFFF : 0x26FFFFFF);
            v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(150).start();
            if (has && onVerseFocus != null) onVerseFocus.run();
        });
        verse.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                return code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                        || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (onVerseDown != null) onVerseDown.run();
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && footCard != null) {
                footCard.requestFocus();
                return true;
            }
            return code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f);
        vp.setMarginEnd(dp(14));
        row.addView(verse, vp);
        verse.addView(label("VERSE OF THE DAY", 0xFFF2C14E));
        // Quote and reference together, centred vertically in the rest of the card.
        verseBlock = new LinearLayout(act);
        verseBlock.setOrientation(LinearLayout.VERTICAL);
        verseBlock.setGravity(Gravity.CENTER_VERTICAL);
        verse.addView(verseBlock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        verseText = new TextView(act);
        verseText.setTextColor(WHITE);
        verseText.setTypeface(verseFace());
        verseText.setLineSpacing(0, 1.25f);
        verseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, VERSE_MAX_SP);
        verseBlock.addView(verseText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        verseRef = text(11, DIM, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        verseRef.setSingleLine(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(10);
        verseBlock.addView(verseRef, rp);
        verseBlock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) main.post(this::fitVerse);
        });

        // ---- Liverpool card, 40%: two compact rows with a divider ----
        LinearLayout foot = card();
        footCard = foot;
        footBg = (GradientDrawable) foot.getBackground();
        foot.setFocusable(true);
        foot.setClickable(true);
        foot.setContentDescription("Liverpool FC. Press OK for recent results and upcoming fixtures.");
        foot.setOnClickListener(v -> openFixtures());
        foot.setOnFocusChangeListener((v, has) -> {
            footBg.setStroke(has ? dp(3) : Math.max(1, Math.round(density / 2)), has ? 0xFFFFFFFF : 0x26FFFFFF);
            v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(150).start();
            if (has && onVerseFocus != null) onVerseFocus.run();
        });
        foot.setOnKeyListener((v, code, ev) -> {
            boolean nav = code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return nav;
            if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (onFootDown != null) onFootDown.run();
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && verseCard != null) {
                verseCard.requestFocus();
                return true;
            }
            return nav;
        });
        row.addView(foot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));

        foot.addView(redLabel("LIVERPOOL FC"));
        LinearLayout last = section();
        foot.addView(last, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        last.addView(label("LAST RESULT", DIM));
        lastLine = fitLine(17);
        last.addView(lastLine, lineParams(26, 6));
        lastMeta = fitLine(11);
        lastMeta.setTextColor(DETAIL);
        last.addView(lastMeta, lineParams(16, 4));

        View divider = new View(act);
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(density)));
        foot.addView(divider, dv);

        LinearLayout next = section();
        foot.addView(next, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        next.addView(label("NEXT FIXTURE", DIM));
        nextLine = fitLine(17);
        next.addView(nextLine, lineParams(26, 6));
        nextMeta = fitLine(11);
        nextMeta.setTextColor(DETAIL);
        next.addView(nextMeta, lineParams(16, 4));
        footStatus = text(9, DIM, Typeface.DEFAULT);
        footStatus.setSingleLine(true);
        next.addView(footStatus, lineParams(14, 4));
    }

    private static Typeface verseFace() {
        Typeface serifItalic = Typeface.create(Typeface.SERIF, Typeface.ITALIC);
        if (android.os.Build.VERSION.SDK_INT >= 28) return Typeface.create(serifItalic, 500, true);   // medium
        return serifItalic;
    }

    /** Largest verse size up to 30 px that shows the whole quote and reference without scrolling or cutting. */
    private void fitVerse() {
        CharSequence text = verseText.getText();
        int w = verseBlock.getWidth() - verseText.getPaddingLeft() - verseText.getPaddingRight();
        int avail = verseBlock.getHeight() - verseRef.getHeight() - dp(10);
        if (w <= 0 || avail <= 0 || text.length() == 0) return;
        android.text.TextPaint paint = new android.text.TextPaint(verseText.getPaint());
        float sp = VERSE_MAX_SP;
        float scaled = act.getResources().getDisplayMetrics().scaledDensity;
        for (; sp > VERSE_MIN_SP; sp -= 0.5f) {
            paint.setTextSize(sp * scaled);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, w)
                    .setLineSpacing(0, 1.25f)
                    .setIncludePad(true)
                    .build();
            if (layout.getHeight() <= avail) break;
        }
        if (Math.abs(verseText.getTextSize() - sp * scaled) > 0.5f) {
            verseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        }
    }

    private LinearLayout card() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(22), dp(16), dp(22), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, Math.round(density / 2)), 0x26FFFFFF);
        p.setBackground(bg);
        p.setFocusable(false);
        return p;
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(act);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        return s;
    }

    private TextView label(String s, int color) {
        TextView t = text(10, color, Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setText(s);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** Section title: a short red bar and the words in white, so the red stays a small accent. */
    private TextView redLabel(String s) {
        TextView t = label(s, WHITE);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(LFC_RED);
        bar.setCornerRadius(dp(2));
        bar.setSize(dp(4), dp(11));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(bar, null, null, null);
        t.setCompoundDrawablePadding(dp(8));
        return t;
    }

    /** One line that shrinks for a long name instead of ending in an ellipsis. */
    private TextView fitLine(int maxSp) {
        TextView t = text(maxSp, WHITE, Typeface.DEFAULT);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(8, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
        return t;
    }

    private LinearLayout.LayoutParams lineParams(int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(int sp, int color, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setFocusable(false);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    // ---------------------------------------------------------------- refresh

    private void refreshIfDue() {
        // Bible Gateway's day starts at US Eastern midnight (about 09:00 in Dubai), so a fetch early in the Dubai
        // morning still returns yesterday's verse. Keep asking every 30 minutes until the feed's own date is today.
        String today = LocalDate.now(DUBAI).toString();
        long now = System.currentTimeMillis();
        if (!today.equals(prefs.getString("verse_day", "")) && !verseLoading && now - lastVerseTry >= VERSE_RETRY_MS) {
            lastVerseTry = now;
            fetchVerse(today);
        }
        if (now - lastFootballFetch >= FOOTBALL_EVERY_MS && !footballLoading) {
            lastFootballFetch = now;
            fetchFootball();
        }
        showFootball(false);   // keeps "Time TBC"/dates and the stale note current
    }

    private void fetchVerse(String today) {
        verseLoading = true;
        io.execute(() -> {
            try {
                JSONObject votd = new JSONObject(get(VERSE_URL)).getJSONObject("votd");
                // The feed sometimes opens with a section heading in brackets ("[Spiritual Fullness in Christ]"),
                // which is not part of the verse: drop it, keeping the opening quote mark.
                String text = dropHeading(clean(votd.optString("text", votd.optString("content", ""))));
                String ref = clean(votd.optString("display_ref", votd.optString("reference", "")));
                String version = clean(votd.optString("version", "New International Version"));
                if (text.isEmpty() || ref.isEmpty()) throw new IllegalStateException("empty verse");
                // The verse's own date, as the feed states it.
                String day = today;
                try {
                    day = LocalDate.of(Integer.parseInt(votd.getString("year").trim()),
                            Integer.parseInt(votd.getString("month").trim()),
                            Integer.parseInt(votd.getString("day").trim())).toString();
                } catch (Exception ignored) {
                    // No date in the answer: take it as today's.
                }
                final String feedDay = day;
                prefs.edit().putString("verse_text", text).putString("verse_ref", ref)
                        .putString("verse_version", version).putString("verse_day", feedDay).apply();
                remember(feedDay, text, ref, version);
                main.post(() -> { verseLoading = false; showVerse(); });
            } catch (Exception e) {
                main.post(() -> { verseLoading = false; showVerse(); });
            }
        });
    }

    /** Keeps the last 60 days' verses on the TV for the reader, newest first. */
    private synchronized void remember(String day, String text, String ref, String version) {
        try {
            JSONArray old = new JSONArray(prefs.getString("verse_history", "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("d", day).put("t", text).put("r", ref).put("v", version));
            for (int i = 0; i < old.length() && out.length() < HISTORY_MAX; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!day.equals(o.optString("d"))) out.put(o);
            }
            prefs.edit().putString("verse_history", out.toString()).apply();
        } catch (Exception ignored) {
            // History is a nicety; today's verse is already saved.
        }
    }

    private JSONArray history() {
        try {
            JSONArray h = new JSONArray(prefs.getString("verse_history", "[]"));
            if (h.length() == 0 && !prefs.getString("verse_text", "").isEmpty()) {
                h.put(new JSONObject().put("d", prefs.getString("verse_day", "")).put("t", prefs.getString("verse_text", ""))
                        .put("r", prefs.getString("verse_ref", "")).put("v", prefs.getString("verse_version", "NIV")));
            }
            return h;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---------------------------------------------------------------- full-screen reader

    /** OK on the verse card: the verse large and full screen; Left/Right step through earlier days; Back closes. */
    private void openReader() {
        if (testMode) return;
        final JSONArray h = history();
        if (h.length() == 0) return;
        final android.app.Dialog d = new android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(80), dp(40), dp(80), dp(32));
        page.setBackgroundColor(0xF2070A10);
        final TextView date = label("", 0xFFF2C14E);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        page.addView(date);
        final TextView big = new TextView(act);
        big.setTextColor(WHITE);
        big.setTypeface(verseFace());
        big.setLineSpacing(0, 1.3f);
        big.setAutoSizeTextTypeUniformWithConfiguration(14, 30, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bp.topMargin = dp(18);
        bp.bottomMargin = dp(18);
        page.addView(big, bp);
        final TextView ref = text(15, DETAIL, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(ref);
        final TextView hint = text(11, DIM, Typeface.DEFAULT);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(22);
        page.addView(hint, hp);
        final int[] at = {0};
        final Runnable show = () -> {
            try {
                JSONObject o = h.getJSONObject(at[0]);
                String day = o.optString("d");
                String when;
                try {
                    LocalDate ld = LocalDate.parse(day);
                    when = ld.equals(LocalDate.now(DUBAI)) ? "TODAY" : ld.equals(LocalDate.now(DUBAI).minusDays(1))
                            ? "YESTERDAY" : DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK).format(ld).toUpperCase(Locale.UK);
                } catch (Exception e) {
                    when = "";
                }
                date.setText("VERSE OF THE DAY" + (when.isEmpty() ? "" : "  ·  " + when));
                big.setText(dropHeading(o.optString("t")));
                ref.setText(o.optString("r") + "  ·  " + o.optString("v"));
                String older = at[0] < h.length() - 1 ? "\u25C0  Earlier" : "";
                String newer = at[0] > 0 ? "Later  \u25B6" : "";
                hint.setText((older + (older.isEmpty() || newer.isEmpty() ? "" : "      ") + newer
                        + "      Back to close").trim());
            } catch (Exception ignored) {
            }
        };
        d.setContentView(page);
        d.setOnKeyListener((dialog, code, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && at[0] < h.length() - 1) { at[0]++; show.run(); return true; }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && at[0] > 0) { at[0]--; show.run(); return true; }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        show.run();
        d.show();
    }

    private void showVerse() {
        if (testMode) {
            // Esther 8:9, King James Version (public domain): the longest verse in the Bible.
            verseText.setText("“Then were the king’s scribes called at that time in the third month, that is, the "
                    + "month Sivan, on the three and twentieth day thereof; and it was written according to all that Mordecai "
                    + "commanded unto the Jews, and to the lieutenants, and the deputies and rulers of the provinces which are "
                    + "from India unto Ethiopia, an hundred twenty and seven provinces, unto every province according to the "
                    + "writing thereof, and unto every people after their language, and to the Jews according to their "
                    + "writing, and according to their language.”");
            verseRef.setText("LAYOUT TEST  ·  Esther 8:9  ·  King James Version");
        } else {
            String text = prefs.getString("verse_text", "");
            if (text.isEmpty()) {
                verseText.setText("Loading today’s verse…");
                verseRef.setText("");
            } else {
                verseText.setText(dropHeading(text));
                String ref = prefs.getString("verse_ref", "") + "  ·  " + prefs.getString("verse_version", "NIV");
                try {
                    // The feed's day starts about 09:00 Dubai time, so yesterday's verse is normal until then.
                    if (LocalDate.parse(prefs.getString("verse_day", "")).isBefore(LocalDate.now(DUBAI).minusDays(1))) {
                        ref += "  (saved " + prefs.getString("verse_day", "") + ", offline)";
                    }
                } catch (Exception ignored) {
                }
                verseRef.setText(ref);
            }
        }
        verseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, VERSE_MAX_SP);
        main.post(this::fitVerse);
    }

    private void fetchFootball() {
        footballLoading = true;
        io.execute(() -> {
            boolean ok = false;
            try {
                try {
                    JSONObject cur = new JSONObject(get(WEATHER_URL)).getJSONObject("current");
                    prefs.edit().putString("wx_temp", String.valueOf(Math.round(cur.getDouble("temperature_2m"))))
                            .putInt("wx_code", cur.optInt("weather_code", -1)).apply();
                } catch (Exception ignored) {
                    // Weather is a nicety: keep the last reading.
                }
                String results = get(RESULTS_URL);
                String fixtures = get(FIXTURES_URL);
                // Only store answers that parse, so a broken response never replaces good data.
                new JSONObject(results).getJSONArray("events");
                new JSONObject(fixtures).getJSONArray("events");
                prefs.edit().putString("fb_results", results).putString("fb_fixtures", fixtures)
                        .putLong("fb_time", System.currentTimeMillis()).apply();
                ok = true;
            } catch (Exception ignored) {
                // Keep the cache; the panel shows its age.
            }
            final boolean fresh = ok;
            main.post(() -> { footballLoading = false; showFootball(!fresh); });
        });
    }

    private void addWeather() {
        TextView date = act.findViewById(R.id.date);
        if (date == null || !(date.getParent() instanceof LinearLayout)) return;
        // A second line under the date, in the date's own style, so the header row keeps its width.
        LinearLayout column = (LinearLayout) date.getParent();
        weather = new TextView(act);
        weather.setTextSize(TypedValue.COMPLEX_UNIT_PX, date.getTextSize() * 0.9f);
        weather.setTextColor(date.getCurrentTextColor());
        weather.setTypeface(date.getTypeface());
        weather.setLetterSpacing(date.getLetterSpacing());
        weather.setAllCaps(true);
        weather.setSingleLine(true);
        weather.setFocusable(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        column.addView(weather, column.indexOfChild(date) + 1, p);
        showWeather();
    }

    private void showWeather() {
        if (weather == null) return;
        String t = prefs.getString("wx_temp", "");
        weather.setText(t.isEmpty() ? "" : "DUBAI  " + t + "°  " + sky(prefs.getInt("wx_code", -1)));
        weather.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** WMO weather codes as Open-Meteo reports them. */
    private static String sky(int code) {
        if (code == 0) return "CLEAR";
        if (code <= 2) return "PARTLY CLOUDY";
        if (code == 3) return "CLOUDY";
        if (code == 45 || code == 48) return "FOG";
        if (code >= 51 && code <= 57) return "DRIZZLE";
        if (code >= 61 && code <= 67) return "RAIN";
        if (code >= 71 && code <= 77) return "SNOW";
        if (code >= 80 && code <= 82) return "SHOWERS";
        if (code >= 95) return "THUNDERSTORM";
        return "";
    }

    private void showFootball(boolean failed) {
        showWeather();
        if (testMode) {
            lastLine.setText(scoreText("Borussia Mönchengladbach", "3", "3", "Liverpool", "4–5"));
            lastMeta.setText("Wed 18 Nov 2026  ·  UEFA Europa Conference League Knockout Round Play-offs");
            nextLine.setText(fixtureText("Wolverhampton Wanderers Football Club", false));
            nextMeta.setText("Away  ·  Sat 28 Nov  ·  Time TBC  ·  English Football League Cup");
            footStatus.setText("LAYOUT TEST  ·  sample data  ·  press Home for live scores");
            return;
        }
        String results = prefs.getString("fb_results", "");
        String fixtures = prefs.getString("fb_fixtures", "");
        long time = prefs.getLong("fb_time", 0);
        if (results.isEmpty() && fixtures.isEmpty()) {
            lastLine.setText(failed ? "Can’t reach the fixtures feed" : "Loading…");
            lastMeta.setText("");
            nextLine.setText("");
            nextMeta.setText("");
            footStatus.setText(failed ? "Trying again in 15 minutes" : "");
            return;
        }
        try {
            JSONObject last = pick(results, true);
            if (last != null) {
                JSONObject h = side(last, "home"), a = side(last, "away");
                String hs = shootout(h), as = shootout(a);
                String pens = !hs.isEmpty() && !as.isEmpty() && !(hs.equals("0") && as.equals("0")) ? hs + "–" + as : "";
                lastLine.setText(scoreText(teamName(h), score(h), score(a), teamName(a), pens));
                lastMeta.setText(dateOnly(last) + "  ·  " + competition(last));
            } else {
                lastLine.setText("No completed match found");
                lastMeta.setText("");
            }
            JSONObject next = pick(fixtures, false);
            if (next != null) {
                JSONObject h = side(next, "home"), a = side(next, "away");
                boolean home = "Liverpool".equals(teamName(h));
                nextLine.setText(fixtureText(home ? teamName(a) : teamName(h), home));
                nextMeta.setText((home ? "Home" : "Away") + "  ·  " + kickoff(next) + "  ·  " + competition(next));
            } else {
                nextLine.setText("No fixture scheduled yet");
                nextMeta.setText("");
            }
        } catch (Exception e) {
            lastLine.setText("Fixtures data unreadable");
            lastMeta.setText("");
            nextLine.setText("");
            nextMeta.setText("");
        }
        boolean stale = failed || System.currentTimeMillis() - time > 2 * FOOTBALL_EVERY_MS + TICK_MS;
        footStatus.setText(stale && time > 0
                ? "Feed unavailable  ·  last updated " + DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
                        .format(Instant.ofEpochMilli(time).atZone(DUBAI))
                : "");
    }

    /** "Home  4 – 0  Away": the score is the boldest, largest part; a shootout follows in brackets. */
    private static CharSequence scoreText(String home, String hs, String as, String away, String pens) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append(home).append("   ");
        int s0 = b.length();
        b.append(hs).append(" – ").append(as);
        int s1 = b.length();
        b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s0, s1, 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(1.3f), s0, s1, 0);
        b.append("   ").append(away);
        if (!pens.isEmpty()) {
            int p0 = b.length();
            b.append("  (").append(pens).append(" pens)");
            b.setSpan(new android.text.style.RelativeSizeSpan(0.8f), p0, b.length(), 0);
        }
        return b;
    }

    /** The opponent leads, in medium weight; home or away is said once, in the line under it. */
    private static CharSequence fixtureText(String opponent, boolean home) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append(home ? "vs  " : "at  ");
        b.setSpan(new android.text.style.ForegroundColorSpan(DIM), 0, b.length(), 0);
        int o = b.length();
        b.append(opponent);
        b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), o, b.length(), 0);
        return b;
    }

    // ---------------------------------------------------------------- Liverpool full screen

    /** OK on the Liverpool card: the last six results and the next six fixtures, all competitions. Back closes. */
    private void openFixtures() {
        if (testMode) return;
        java.util.List<JSONObject> results, fixtures;
        try {
            results = matches(prefs.getString("fb_results", ""), true, 6);
            fixtures = matches(prefs.getString("fb_fixtures", ""), false, 6);
        } catch (Exception e) {
            return;
        }
        if (results.isEmpty() && fixtures.isEmpty()) return;
        android.app.Dialog d = new android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout page = new LinearLayout(act);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(64), dp(36), dp(64), dp(28));
        page.setBackgroundColor(0xF2070A10);
        page.addView(redLabel("LIVERPOOL FC"));

        LinearLayout cols = new LinearLayout(act);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setBaselineAligned(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = dp(18);
        page.addView(cols, cp);

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginEnd(dp(36));
        cols.addView(left, lp);
        left.addView(label("RECENT RESULTS", 0xFFF2C14E));
        for (JSONObject e : results) left.addView(resultRow(e));

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        right.addView(label("UPCOMING FIXTURES", 0xFFF2C14E));
        for (JSONObject e : fixtures) right.addView(fixtureRow(e));

        long time = prefs.getLong("fb_time", 0);
        TextView foot = text(10, DIM, Typeface.DEFAULT);
        foot.setText((time > 0 ? "Updated " + DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
                .format(Instant.ofEpochMilli(time).atZone(DUBAI)) + " Dubai  ·  " : "") + "Times in Dubai  ·  Back to close");
        page.addView(foot);
        d.setContentView(page);
        d.setOnKeyListener((dialog, code, ev) -> {
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER)) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        d.show();
    }

    /** One past match: W/D/L badge, both teams and the score in home-away order, then date and competition. */
    private View resultRow(JSONObject e) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        String badge = "";
        int color = DIM;
        CharSequence line = "";
        String meta = "";
        try {
            JSONObject h = side(e, "home"), a = side(e, "away");
            boolean home = "Liverpool".equals(teamName(h));
            int us = Integer.parseInt(score(home ? h : a).trim()), them = Integer.parseInt(score(home ? a : h).trim());
            String hs = shootout(h), as = shootout(a);
            boolean pens = !hs.isEmpty() && !as.isEmpty() && !(hs.equals("0") && as.equals("0"));
            if (us == them && pens) {
                int ps = Integer.parseInt(home ? hs : as), pt = Integer.parseInt(home ? as : hs);
                us += ps > pt ? 1 : 0;
                them += pt > ps ? 1 : 0;
            }
            badge = us > them ? "W" : us < them ? "L" : "D";
            color = us > them ? 0xFF2E9E5B : us < them ? LFC_RED : 0xFF8A94A3;
            line = scoreText(teamName(h), score(h), score(a), teamName(a), pens ? hs + "\u2013" + as : "");
            meta = dateOnly(e) + "  ·  " + competition(e);
        } catch (Exception ignored) {
        }
        TextView b = text(12, WHITE, Typeface.DEFAULT_BOLD);
        b.setText(badge);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(5));
        b.setBackground(bg);
        r.addView(b, new LinearLayout.LayoutParams(dp(26), dp(26)));
        r.addView(twoLines(line, meta), weightParams());
        return r;
    }

    /** One coming match: opponent first, home or away once, then date, Dubai kick-off and competition. */
    private View fixtureRow(JSONObject e) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        CharSequence line = "";
        String meta = "";
        try {
            JSONObject h = side(e, "home"), a = side(e, "away");
            boolean home = "Liverpool".equals(teamName(h));
            line = fixtureText(home ? teamName(a) : teamName(h), home);
            meta = (home ? "Home" : "Away") + "  ·  " + kickoff(e) + "  ·  " + competition(e);
        } catch (Exception ignored) {
        }
        r.addView(twoLines(line, meta), weightParams());
        return r;
    }

    private LinearLayout twoLines(CharSequence line, String meta) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), 0, 0, 0);
        TextView t = fitLine(15);
        t.setText(line);
        c.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        TextView m = fitLine(10);
        m.setTextColor(DETAIL);
        m.setText(meta);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(15));
        mp.topMargin = dp(3);
        c.addView(m, mp);
        return c;
    }

    private static LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Competitive matches only: completed ones newest first, or scheduled ones soonest first. */
    private static java.util.List<JSONObject> matches(String json, boolean done, int max) throws Exception {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        if (json.isEmpty()) return out;
        JSONArray events = new JSONObject(json).getJSONArray("events");
        Instant now = Instant.now();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            if (competition(e).toLowerCase(Locale.ROOT).contains("friendly")) continue;
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject type = c.optJSONObject("status") != null ? c.getJSONObject("status").optJSONObject("type") : null;
            if (type == null) continue;
            boolean completed = type.optBoolean("completed", false);
            Instant at = parse(e.optString("date", ""));
            if (at == null) continue;
            if (done && !completed) continue;
            if (!done) {
                String name = type.optString("name", "");
                String state = type.optString("state", "");
                if (completed || "in".equals(state) || "post".equals(state)) continue;
                if (name.contains("POSTPONED") || name.contains("CANCELED")) continue;
                if (at.isBefore(now.minusSeconds(3 * 3600))) continue;
            }
            out.add(e);
        }
        java.util.Collections.sort(out, (x, y) -> {
            Instant ax = parse(x.optString("date", "")), ay = parse(y.optString("date", ""));
            return done ? ay.compareTo(ax) : ax.compareTo(ay);
        });
        return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * The most recent completed (last == true) or the next scheduled competitive match. Friendlies are left out:
     * "competitive" means a league or cup.
     */
    private static JSONObject pick(String json, boolean last) throws Exception {
        if (json.isEmpty()) return null;
        JSONArray events = new JSONObject(json).getJSONArray("events");
        JSONObject best = null;
        Instant bestAt = null;
        Instant now = Instant.now();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            if (competition(e).toLowerCase(Locale.ROOT).contains("friendly")) continue;
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject type = c.optJSONObject("status") != null
                    ? c.getJSONObject("status").optJSONObject("type") : null;
            if (type == null) continue;
            boolean completed = type.optBoolean("completed", false);
            String state = type.optString("state", "");
            Instant at = parse(e.optString("date", ""));
            if (at == null) continue;
            if (last) {
                if (!completed) continue;
                if (bestAt == null || at.isAfter(bestAt)) { best = e; bestAt = at; }
            } else {
                if (completed || "in".equals(state) || "post".equals(state)) continue;
                String name = type.optString("name", "");
                if (name.contains("POSTPONED") || name.contains("CANCELED")) continue;
                if (at.isBefore(now.minusSeconds(3 * 3600))) continue;
                if (bestAt == null || at.isBefore(bestAt)) { best = e; bestAt = at; }
            }
        }
        return best;
    }

    private static Instant parse(String s) {
        try {
            if (s.length() == 17) s = s.substring(0, 16) + ":00Z";   // 2026-11-22T16:30Z
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject side(JSONObject e, String homeAway) throws Exception {
        JSONArray teams = e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            if (homeAway.equals(t.optString("homeAway"))) return t;
        }
        return teams.getJSONObject(homeAway.equals("home") ? 0 : Math.min(1, teams.length() - 1));
    }

    private static String teamName(JSONObject side) {
        JSONObject team = side.optJSONObject("team");
        return team == null ? "" : team.optString("displayName", team.optString("name", ""));
    }

    private static String score(JSONObject side) {
        Object s = side.opt("score");
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "");
        return s == null || s == JSONObject.NULL ? "" : String.valueOf(s);
    }

    private static String shootout(JSONObject side) {
        Object s = side.opt("shootoutScore");
        if (s == null || s == JSONObject.NULL) return "";
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "");
        return String.valueOf(s);
    }



    private static String competition(JSONObject e) {
        JSONObject league = e.optJSONObject("league");
        String name = league != null ? league.optString("name", "") : "";
        if (name.isEmpty()) {
            JSONObject season = e.optJSONObject("seasonType");
            name = season != null ? season.optString("name", "") : "";
        }
        return name;
    }

    private static String dateOnly(JSONObject e) {
        Instant at = parse(e.optString("date", ""));
        return at == null ? "" : DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK).format(at.atZone(DUBAI));
    }

    private static String kickoff(JSONObject e) {
        Instant at = parse(e.optString("date", ""));
        if (at == null) return "Date TBC";
        ZonedDateTime z = at.atZone(DUBAI);
        String date = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).format(z);
        boolean timeValid = true;
        try {
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            timeValid = c.optBoolean("timeValid", true);
        } catch (Exception ignored) { }
        return date + " · " + (timeValid ? DateTimeFormatter.ofPattern("HH:mm", Locale.UK).format(z) + " Dubai" : "Time TBC");
    }

    private static String dropHeading(String verse) {
        return verse.replaceFirst("^([\u201C\"]?)\\s*\\[[^\\]]{1,80}\\]\\s*", "$1");
    }

    private static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(' ', ' ').trim();
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "MenCaveTV/1.0");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder b = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) b.append(line);
                return b.toString();
            }
        } finally {
            c.disconnect();
        }
    }
}
