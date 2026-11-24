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
        host.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        host.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- Verse card, 60% ----
        LinearLayout verse = card();
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
        row.addView(foot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));

        LinearLayout last = section();
        foot.addView(last, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        last.addView(redLabel("LIVERPOOL FC  ·  LAST RESULT"));
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
        next.addView(redLabel("NEXT FIXTURE"));
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
        String today = LocalDate.now(DUBAI).toString();
        if (!today.equals(prefs.getString("verse_day", "")) && !verseLoading) fetchVerse(today);
        long now = System.currentTimeMillis();
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
                prefs.edit().putString("verse_text", text).putString("verse_ref", ref)
                        .putString("verse_version", version).putString("verse_day", today).apply();
                main.post(() -> { verseLoading = false; showVerse(); });
            } catch (Exception e) {
                main.post(() -> { verseLoading = false; showVerse(); });
            }
        });
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
                if (!LocalDate.now(DUBAI).toString().equals(prefs.getString("verse_day", ""))) {
                    ref += "  (saved " + prefs.getString("verse_day", "") + ", offline)";
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
