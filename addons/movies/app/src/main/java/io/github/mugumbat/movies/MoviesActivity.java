package io.github.mugumbat.movies;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Popular titles available on Netflix, Disney+ and Apple TV in the UAE, from TMDB's discover API
 * (popularity among TMDB users, filtered by where JustWatch says each title streams). Not the
 * services' own top 10 lists, which they do not publish to other apps.
 */
public class MoviesActivity extends Activity {

    private static final String REGION = "AE";
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMG = "https://image.tmdb.org/t/p/";

    /** Tab name, TMDB watch provider id, package the posters open. */
    private static final String[][] SOURCES = {
            {"Netflix", "8", "com.netflix.ninja"},
            {"Disney+", "337", "com.disney.disneyplus"},
            {"Apple TV", "350", "com.apple.atve.androidtv.appletv"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<Integer, List<Title>> cache = new HashMap<>();

    private ImageView backdrop;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroOverview;
    private LinearLayout row;
    private HorizontalScrollView rowScroll;
    private final List<Button> tabs = new ArrayList<>();
    private TextView status;
    private int current = 0;
    private int loadToken = 0;

    static final class Title {
        String name;
        String year;
        String overview;
        String poster;
        String backdrop;
        double rating;
        double popularity;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1218);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99050709, 0x33050709, 0xEE050709}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(64), dp(40), dp(64), dp(32));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = text(16, 0xFF6FA8FF, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setText("POPULAR IN THE UAE");
        label.setLetterSpacing(0.2f);
        column.addView(label);

        heroTitle = text(40, 0xFFF5F5F7, Typeface.create("sans-serif-medium", Typeface.BOLD));
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(heroTitle, wrap(dp(12)));

        heroMeta = text(18, 0xFFC9D1DC, Typeface.DEFAULT);
        column.addView(heroMeta, wrap(dp(6)));

        heroOverview = text(18, 0xFFDCE2EA, Typeface.DEFAULT);
        heroOverview.setMaxLines(3);
        heroOverview.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(820), ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(10);
        column.addView(heroOverview, op);

        View spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SOURCES.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(SOURCES[i][0]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            b.setFocusable(true);
            b.setOnClickListener(v -> select(index));
            b.setOnFocusChangeListener((v, has) -> styleTabs());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            bp.rightMargin = dp(12);
            tabRow.addView(b, bp);
            tabs.add(b);
        }
        status = text(16, 0xFF8A94A3, Typeface.DEFAULT);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        sp.leftMargin = dp(16);
        tabRow.addView(status, sp);
        column.addView(tabRow);

        rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(0, dp(16), 0, dp(16));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        rowScroll.setClipChildren(false);
        rowScroll.addView(row);
        column.setClipChildren(false);
        column.addView(rowScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        setContentView(root);
        styleTabs();

        if (BuildConfig.TMDB_KEY.isEmpty()) {
            heroTitle.setText("No TMDB key in this build");
            heroOverview.setText("Add the TMDB_API_KEY secret to the GitHub repository and run the build again.");
            return;
        }
        select(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private void select(int index) {
        current = index;
        styleTabs();
        List<Title> have = cache.get(index);
        if (have != null) {
            show(have);
            return;
        }
        final int token = ++loadToken;
        status.setText("Loading…");
        row.removeAllViews();
        final String provider = SOURCES[index][1];
        pool.execute(() -> {
            try {
                List<Title> list = new ArrayList<>();
                list.addAll(discover("movie", provider));
                list.addAll(discover("tv", provider));
                Collections.sort(list, (a, b) -> Double.compare(b.popularity, a.popularity));
                final List<Title> top = new ArrayList<>(list.subList(0, Math.min(20, list.size())));
                main.post(() -> {
                    cache.put(index, top);
                    if (token == loadToken) show(top);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token == loadToken) status.setText("Can't reach TMDB. Check the internet and press OK on a tab to retry.");
                });
            }
        });
    }

    private List<Title> discover(String kind, String provider) throws Exception {
        String url = API + "discover/" + kind
                + "?watch_region=" + REGION
                + "&with_watch_providers=" + provider
                + "&with_watch_monetization_types=flatrate"
                + "&sort_by=popularity.desc&include_adult=false&language=en-US&page=1";
        JSONArray results = new JSONObject(getText(url)).optJSONArray("results");
        List<Title> out = new ArrayList<>();
        if (results == null) return out;
        boolean tv = kind.equals("tv");
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String poster = o.optString("poster_path", "");
            if (poster.isEmpty() || poster.equals("null")) continue;
            Title t = new Title();
            t.name = o.optString(tv ? "name" : "title", "");
            String date = o.optString(tv ? "first_air_date" : "release_date", "");
            t.year = date.length() >= 4 ? date.substring(0, 4) : "";
            t.overview = o.optString("overview", "");
            t.poster = poster;
            String bd = o.optString("backdrop_path", "");
            t.backdrop = bd.equals("null") ? "" : bd;
            t.rating = o.optDouble("vote_average", 0);
            t.popularity = o.optDouble("popularity", 0);
            out.add(t);
        }
        return out;
    }

    private void show(List<Title> list) {
        row.removeAllViews();
        status.setText(list.isEmpty() ? "Nothing found for the UAE right now." : "");
        for (int i = 0; i < list.size(); i++) {
            final Title t = list.get(i);
            final int rank = i + 1;
            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClipChildren(false);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0xFF1E2531);
            shape.setCornerRadius(dp(12));
            card.setBackground(shape);
            card.setClipToOutline(true);

            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(poster, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView badge = text(16, 0xFFF5F5F7, Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setText("#" + rank);
            badge.setShadowLayer(6, 0, 0, 0xFF000000);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            bp.leftMargin = dp(10);
            bp.topMargin = dp(8);
            card.addView(badge, bp);

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(170), dp(255));
            cp.rightMargin = dp(22);
            row.addView(card, cp);

            loadImage(IMG + "w342" + t.poster, poster);

            card.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
                v.setElevation(has ? dp(12) : 0);
                if (has) hero(t);
            });
            card.setOnClickListener(v -> openService());
        }
        if (!list.isEmpty()) {
            hero(list.get(0));
            row.getChildAt(0).requestFocus();
        }
    }

    private void hero(Title t) {
        heroTitle.setText(t.name);
        StringBuilder meta = new StringBuilder(SOURCES[current][0]);
        if (!t.year.isEmpty()) meta.append(" · ").append(t.year);
        if (t.rating > 0) meta.append(" · ★ ").append(String.format(java.util.Locale.US, "%.1f", t.rating));
        heroMeta.setText(meta);
        heroOverview.setText(t.overview);
        if (!t.backdrop.isEmpty()) loadImage(IMG + "w780" + t.backdrop, backdrop);
    }

    private void openService() {
        String pkg = SOURCES[current][2];
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, SOURCES[current][0] + " isn't installed on this TV", Toast.LENGTH_LONG).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void styleTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            Button b = tabs.get(i);
            boolean on = i == current;
            boolean focus = b.hasFocus();
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(24));
            pill.setColor(on ? 0xFFF5F5F7 : (focus ? 0x66FFFFFF : 0x9912161E));
            if (focus && !on) pill.setStroke(dp(2), 0xFFF5F5F7);
            b.setBackground(pill);
            b.setTextColor(on ? 0xFF0E1218 : 0xFFEEF1F5);
            b.setPadding(dp(22), 0, dp(22), 0);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Up from the posters lands on the selected tab, not the first one.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && row.hasFocus()) {
            tabs.get(current).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void loadImage(final String url, final ImageView into) {
        into.setTag(url);
        Bitmap hit = images.get(url);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                Bitmap bmp;
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (bmp == null) return;
                final Bitmap done = bmp;
                images.put(url, done);
                main.post(() -> {
                    if (url.equals(into.getTag())) into.setImageBitmap(done);
                });
            } catch (Exception ignored) {
                // A missing image leaves the card's dark fill.
            }
        });
    }

    private static String getText(String address) throws Exception {
        String key = BuildConfig.TMDB_KEY;
        boolean bearer = key.startsWith("eyJ");
        String url = bearer ? address : address + "&api_key=" + key;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
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

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
