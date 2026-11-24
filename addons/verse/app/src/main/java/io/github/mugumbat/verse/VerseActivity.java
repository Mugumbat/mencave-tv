package io.github.mugumbat.verse;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen verse of the day (NIV), from Bible Gateway's free verse-of-the-day feed.
 * The last verse is kept, so the screen still shows something when the TV is offline.
 */
public class VerseActivity extends Activity {

    private static final String FEED = "https://www.biblegateway.com/votd/get/?format=json&version=NIV";
    private static final String PREFS = "verse";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView verseView;
    private TextView refView;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(96);
        root.setPadding(pad, dp(64), pad, dp(64));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0E1218, 0xFF1C2233, 0xFF3A2F45});
        root.setBackground(bg);

        TextView label = text(18, 0xFFE2B45A, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setText("VERSE OF THE DAY");
        label.setLetterSpacing(0.2f);
        root.addView(label);

        verseView = text(44, 0xFFF5F5F7, Typeface.SERIF);
        verseView.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(24);
        root.addView(verseView, vp);

        refView = text(24, 0xFFC9D1DC, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(24);
        root.addView(refView, rp);

        statusView = text(16, 0xFF8A94A3, Typeface.DEFAULT);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(40);
        root.addView(statusView, sp);

        root.setFocusable(true);
        setContentView(root);
        showSaved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String today = today();
        if (!today.equals(prefs().getString("day", ""))) fetch(today);
    }

    private void showSaved() {
        SharedPreferences p = prefs();
        String verse = p.getString("text", "");
        if (verse.isEmpty()) {
            verseView.setText("Loading today's verse…");
            refView.setText("");
        } else {
            verseView.setText(verse);
            refView.setText(p.getString("ref", "") + " · NIV");
        }
        statusView.setText("Verse of the Day from Bible Gateway");
    }

    private void fetch(String today) {
        new Thread(() -> {
            try {
                JSONObject votd = new JSONObject(get(FEED)).getJSONObject("votd");
                String raw = votd.optString("text", votd.optString("content", ""));
                String ref = votd.optString("display_ref", votd.optString("reference", ""));
                String clean = strip(raw);
                if (clean.isEmpty()) throw new IllegalStateException("empty verse");
                prefs().edit().putString("text", clean).putString("ref", strip(ref))
                        .putString("day", today).apply();
                main.post(this::showSaved);
            } catch (Exception e) {
                main.post(() -> {
                    if (prefs().getString("text", "").isEmpty()) {
                        verseView.setText("Can't reach Bible Gateway right now.");
                    }
                    statusView.setText("Offline: showing the last saved verse. It tries again next time you open this.");
                });
            }
        }).start();
    }

    private static String strip(String html) {
        String s = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        return s.replace(' ', ' ').trim();
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "MenCaveTV/1.0");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        } finally {
            c.disconnect();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
