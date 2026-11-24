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
    private static final int SOFT = 0xFFD7DEE8;
    private static final int LABEL = 0xFFF2C14E;   // warm gold labels, high contrast on the dark glass
    private static final int RED = 0xFFFF6B6B;     // Liverpool section label

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final float density;

    private TextView verseText, verseRef;
    private TextView lastTeams, lastMeta, nextTeams, nextMeta, footStatus;
    /** Dubai weather beside the date in the header (Open-Meteo, no key). */
    private TextView weather;

    private long lastFootballFetch;
    private boolean running;
    private boolean verseLoading, footballLoading;

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

    // ---------------------------------------------------------------- layout

    private void build(ViewGroup host) {
        host.removeAllViews();
        host.setFocusable(false);
        host.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.TOP);
        host.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Verse: 60%.
        LinearLayout verse = panel();
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f);
        vp.setMarginEnd(dp(16));
        row.addView(verse, vp);
        verse.addView(label("VERSE OF THE DAY", LABEL));
        verseText = new TextView(act);
        verseText.setTextColor(WHITE);
        verseText.setTypeface(Typeface.SERIF);
        verseText.setLineSpacing(0, 1.12f);
        verseText.setIncludeFontPadding(false);
        // The whole verse always shows: long verses shrink to fit the panel instead of being cut or scrolled.
        verseText.setAutoSizeTextTypeUniformWithConfiguration(11, 24, 1, TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tp.topMargin = dp(8);
        verse.addView(verseText, tp);
        verseRef = text(13, SOFT, Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams rp = wrap();
        rp.topMargin = dp(8);
        verse.addView(verseRef, rp);

        // Liverpool: 40%.
        LinearLayout foot = panel();
        row.addView(foot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));
        foot.addView(label("LIVERPOOL FC", RED));

        TextView lastLabel = label("LAST RESULT", LABEL);
        LinearLayout.LayoutParams ll = wrap();
        ll.topMargin = dp(8);
        foot.addView(lastLabel, ll);
        lastTeams = fitLine(16, WHITE);
        foot.addView(lastTeams, fullWidth(dp(2), 24));
        lastMeta = fitLine(12, SOFT);
        foot.addView(lastMeta, fullWidth(dp(1), 18));

        TextView nextLabel = label("NEXT FIXTURE", LABEL);
        LinearLayout.LayoutParams nl = wrap();
        nl.topMargin = dp(10);
        foot.addView(nextLabel, nl);
        nextTeams = fitLine(16, WHITE);
        foot.addView(nextTeams, fullWidth(dp(2), 24));
        nextMeta = fitLine(12, SOFT);
        foot.addView(nextMeta, fullWidth(dp(1), 18));

        footStatus = text(10, 0xFFAEB8C6, Typeface.DEFAULT);
        LinearLayout.LayoutParams sp = wrap();
        sp.topMargin = dp(6);
        foot.addView(footStatus, sp);
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3101620);          // dark glass, so text stays readable over any photo
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, dp(1) / 2), 0x33FFFFFF);
        p.setBackground(bg);
        p.setFocusable(false);
        return p;
    }

    private TextView label(String s, int color) {
        TextView t = text(11, color, Typeface.DEFAULT_BOLD);
        t.setText(s);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** One line that shrinks for a long team name rather than ending in an ellipsis. */
    private TextView fitLine(int maxSp, int color) {
        TextView t = text(maxSp, color, Typeface.DEFAULT);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(9, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
        return t;
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

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fullWidth(int top, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        p.topMargin = top;
        return p;
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
                String text = clean(votd.optString("text", votd.optString("content", "")));
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
        String text = prefs.getString("verse_text", "");
        if (text.isEmpty()) {
            verseText.setText("Loading today's verse…");
            verseRef.setText("");
            return;
        }
        verseText.setText(text);
        String ref = prefs.getString("verse_ref", "") + " · " + prefs.getString("verse_version", "NIV");
        if (!LocalDate.now(DUBAI).toString().equals(prefs.getString("verse_day", ""))) {
            ref += "  (from " + prefs.getString("verse_day", "") + "; offline)";
        }
        verseRef.setText(ref);
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
        String results = prefs.getString("fb_results", "");
        String fixtures = prefs.getString("fb_fixtures", "");
        long time = prefs.getLong("fb_time", 0);
        if (results.isEmpty() && fixtures.isEmpty()) {
            lastTeams.setText(failed ? "Can't reach the fixtures feed" : "Loading…");
            lastMeta.setText("");
            nextTeams.setText("");
            nextMeta.setText("");
            footStatus.setText(failed ? "Will try again in 15 minutes" : "");
            return;
        }
        try {
            JSONObject last = pick(results, true);
            if (last != null) {
                lastTeams.setText(scoreLine(last));
                lastMeta.setText(dateOnly(last) + " · " + competition(last));
            } else {
                lastTeams.setText("No completed match found");
                lastMeta.setText("");
            }
            JSONObject next = pick(fixtures, false);
            if (next != null) {
                nextTeams.setText(fixtureLine(next));
                nextMeta.setText(kickoff(next) + " · " + competition(next));
            } else {
                nextTeams.setText("No fixture scheduled yet");
                nextMeta.setText("");
            }
        } catch (Exception e) {
            lastTeams.setText("Fixtures data unreadable");
            lastMeta.setText("");
            nextTeams.setText("");
            nextMeta.setText("");
        }
        boolean stale = failed || System.currentTimeMillis() - time > 2 * FOOTBALL_EVERY_MS + TICK_MS;
        footStatus.setText(stale && time > 0
                ? "Feed unavailable · last updated " + DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
                        .format(Instant.ofEpochMilli(time).atZone(DUBAI))
                : "");
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

    private static String scoreLine(JSONObject e) throws Exception {
        JSONObject h = side(e, "home"), a = side(e, "away");
        String line = teamName(h) + "  " + score(h) + " – " + score(a) + "  " + teamName(a);
        String hs = shootout(h), as = shootout(a);
        if (!hs.isEmpty() && !as.isEmpty() && !(hs.equals("0") && as.equals("0"))) {
            line += "  (pens " + hs + "–" + as + ")";
        }
        return line;
    }

    private static String fixtureLine(JSONObject e) throws Exception {
        JSONObject h = side(e, "home"), a = side(e, "away");
        boolean home = "Liverpool".equals(teamName(h));
        String opponent = home ? teamName(a) : teamName(h);
        return (home ? "vs " : "at ") + opponent + (home ? "  (home)" : "  (away)");
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
