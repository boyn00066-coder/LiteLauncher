package com.lite.tvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Lite Launcher: a TV home screen made of horizontal rows (Inputs, TV Apps, Mobile Apps,
 * Settings) over a generated wallpaper. Plain Android framework only, no libraries.
 *
 * Remote: D-pad to move, OK to open, long-press OK (or MENU) for App info / uninstall.
 */
public class MainActivity extends Activity {

    private static final int APP = 0;
    private static final int MOBILE = 1;
    private static final int TOOL = 2;
    private static final int INPUT = 3;

    private static final int TILE_W = 176;
    private static final int TILE_H = 99;

    private static final int ACCENT = 0xFF2DD4BF;
    private static final int APP_TILE = 0xFF1B2733;
    private static final long ANIM_MS = 160;

    // One shared interpolator: no allocation on every remote key press.
    private static final Interpolator EASE = new DecelerateInterpolator(1.6f);

    // ------------------------------------------------------------------ model

    private static final class Entry {
        final int kind;
        final String label;
        final String glyph;
        final int color;
        final ComponentName component;
        final String category;
        final Intent[] intents;
        final String inputId;

        private Entry(int kind, String label, String glyph, int color, ComponentName component,
                      String category, Intent[] intents, String inputId) {
            this.kind = kind;
            this.label = label;
            this.glyph = glyph;
            this.color = color;
            this.component = component;
            this.category = category;
            this.intents = intents;
            this.inputId = inputId;
        }

        static Entry app(int kind, String label, ComponentName cn, String category) {
            return new Entry(kind, label, null, APP_TILE, cn, category, null, null);
        }

        static Entry tool(String label, String glyph, int color, Intent... intents) {
            return new Entry(TOOL, label, glyph, color, null, null, intents, null);
        }

        static Entry input(String label, String glyph, String inputId) {
            return new Entry(INPUT, label, glyph, 0xFF1D7C94, null, null, null, inputId);
        }

        boolean isApp() {
            return kind == APP || kind == MOBILE;
        }

        String key() {
            if (component != null) {
                return component.flattenToShortString();
            }
            return (kind == INPUT ? "input:" + inputId : "tool:" + label);
        }
    }

    private static final class Scan {
        final List<Entry> inputs = new ArrayList<Entry>();
        final List<Entry> apps = new ArrayList<Entry>();
        final List<Entry> mobile = new ArrayList<Entry>();
        final List<Entry> tools = new ArrayList<Entry>();

        String snapshot() {
            StringBuilder sb = new StringBuilder();
            append(sb, inputs);
            append(sb, apps);
            append(sb, mobile);
            return sb.toString();
        }

        private static void append(StringBuilder sb, List<Entry> list) {
            for (Entry e : list) {
                sb.append(e.key()).append('|').append(e.label).append('\n');
            }
            sb.append("--\n");
        }
    }

    private static final class Img {
        final Drawable drawable;
        final boolean banner;

        Img(Drawable drawable, boolean banner) {
            this.drawable = drawable;
            this.banner = banner;
        }
    }

    private static final class Cell {
        final Entry entry;
        final ImageView icon;

        Cell(Entry entry, ImageView icon) {
            this.entry = entry;
            this.icon = icon;
        }
    }

    // ------------------------------------------------------------------ state

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Cell> cells = new ArrayList<Cell>();
    private String snapshot = "";
    private boolean iconsReleased;

    private PackageManager pm;
    private ExecutorService loader;
    private LruCache<String, Img> cache;
    private ScrollView scroll;
    private LinearLayout content;
    private View firstTarget;

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        // Icon loading runs at background priority so it can never stall the remote.
        loader = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, "icon-loader");
            }
        });
        cache = new LruCache<String, Img>(60);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Scan scan = scan();
        String snap = scan.snapshot();
        if (!snap.equals(snapshot)) {
            snapshot = snap;
            rebuild(scan);
        } else if (iconsReleased) {
            for (Cell c : cells) {
                if (c.entry.isApp()) {
                    bindIcon(c.icon, c.entry);
                }
            }
        }
        iconsReleased = false;
        if (getCurrentFocus() == null && firstTarget != null) {
            firstTarget.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goHome();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Covered by another app: give our icon memory back to it.
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            for (Cell c : cells) {
                c.icon.setTag(null);
                c.icon.setImageDrawable(null);
            }
            cache.evictAll();
            iconsReleased = true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
        cache.evictAll();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof Entry) {
                showAppInfo((Entry) f.getTag());
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void goHome() {
        scroll.smoothScrollTo(0, 0);
        if (firstTarget != null) {
            firstTarget.requestFocus();
        }
    }

    // --------------------------------------------------------------------- UI

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new Backdrop(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(56), dp(64), 0, dp(48));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setGravity(Gravity.END);
        clock.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setGravity(Gravity.END);
        clock.addView(date);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = dp(28);
        clp.rightMargin = dp(56);
        root.addView(clock, clp);

        TextView hint = new TextView(this);
        hint.setText("Hold OK on an app for options");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hlp.leftMargin = dp(70);
        hlp.bottomMargin = dp(18);
        root.addView(hint, hlp);
        return root;
    }

    private void rebuild(Scan scan) {
        content.removeAllViews();
        cells.clear();
        firstTarget = null;

        int index = 0;
        if (!scan.inputs.isEmpty()) {
            addSection("Inputs", scan.inputs, index++);
        }
        if (!scan.apps.isEmpty()) {
            addSection("TV Apps", scan.apps, index++);
        }
        if (!scan.mobile.isEmpty()) {
            addSection("Mobile Apps", scan.mobile, index++);
        }
        addSection("Settings", scan.tools, index);

        // Start on the first app, like a normal home screen.
        for (Cell c : cells) {
            if (c.entry.isApp()) {
                firstTarget = (View) c.icon.getParent().getParent();
                break;
            }
        }
        if (firstTarget == null && !cells.isEmpty()) {
            firstTarget = (View) cells.get(0).icon.getParent().getParent();
        }

        // Gentle fade-in instead of rows popping into place.
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(300).start();
    }

    private void addSection(String title, List<Entry> list, int index) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), 0, 0, 0);

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(ACCENT);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(10);
        header.addView(dot, dlp);

        TextView headerText = new TextView(this);
        headerText.setText(title);
        headerText.setTextColor(0xE6FFFFFF);
        headerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        headerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(headerText);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(index == 0 ? 0 : 8);
        content.addView(header, hlp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setFocusable(false);
        hs.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setSmoothScrollingEnabled(true);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, dp(14), dp(56), dp(28));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (Entry e : list) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(14);
            lp.rightMargin = dp(14);
            row.addView(makeCell(e, index), lp);
        }
        hs.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View makeCell(final Entry e, final int sectionIndex) {
        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setLongClickable(true);
        cell.setTag(e);

        final FrameLayout tile = new FrameLayout(this);
        // Soft diagonal gradient from the tile color to a darker shade of it.
        GradientDrawable tileBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(e.color), darken(e.color)});
        tileBg.setCornerRadius(dp(12));
        tile.setBackground(tileBg);
        roundClip(tile, dp(12));

        ImageView icon = new ImageView(this);
        tile.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!e.isApp()) {
            TextView glyph = new TextView(this);
            glyph.setText(e.glyph);
            glyph.setGravity(Gravity.CENTER);
            glyph.setTextColor(Color.WHITE);
            glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            glyph.setTypeface(Typeface.DEFAULT_BOLD);
            tile.addView(glyph, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // White focus ring: follows the cell's focused state.
        View ring = new View(this);
        GradientDrawable ringOn = new GradientDrawable();
        ringOn.setCornerRadius(dp(12));
        ringOn.setColor(Color.TRANSPARENT);
        ringOn.setStroke(dp(3), 0xFFE8FFFB);
        StateListDrawable ringStates = new StateListDrawable();
        ringStates.addState(new int[]{android.R.attr.state_focused}, ringOn);
        ringStates.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        ring.setBackground(ringStates);
        ring.setDuplicateParentStateEnabled(true);
        tile.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cell.addView(tile, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));

        final TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.START);
        label.setAlpha(0f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(TILE_W),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        cell.addView(label, llp);

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.15f : 1f;
                // withLayer(): the tile is animated as one GPU texture, which is
                // much smoother on a weak TV chip.
                tile.animate().scaleX(s).scaleY(s).setDuration(ANIM_MS)
                        .setInterpolator(EASE).withLayer().start();
                label.animate().alpha(hasFocus ? 1f : 0f).translationY(hasFocus ? dp(8) : 0)
                        .setDuration(ANIM_MS).setInterpolator(EASE).start();
                cell.animate().translationZ(hasFocus ? dp(8) : 0).setDuration(ANIM_MS).start();
                if (hasFocus && sectionIndex == 0) {
                    scroll.smoothScrollTo(0, 0);
                }
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(e);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showAppInfo(e);
                return true;
            }
        });

        cells.add(new Cell(e, icon));
        if (e.isApp()) {
            bindIcon(icon, e);
        }
        return cell;
    }

    private static int lighten(int c) {
        return Color.rgb(Math.min(255, Color.red(c) + 22), Math.min(255, Color.green(c) + 22),
                Math.min(255, Color.blue(c) + 22));
    }

    private static int darken(int c) {
        return Color.rgb(Math.round(Color.red(c) * 0.72f), Math.round(Color.green(c) * 0.72f),
                Math.round(Color.blue(c) * 0.72f));
    }

    private void roundClip(View v, final int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    // ------------------------------------------------------------------ icons

    private void bindIcon(final ImageView iv, final Entry e) {
        final String key = e.key();
        iv.setTag(key);
        Img hit = cache.get(key);
        if (hit != null) {
            applyImg(iv, hit);
            return;
        }
        iv.setImageDrawable(null);
        if (loader.isShutdown()) {
            return;
        }
        loader.execute(new Runnable() {
            @Override
            public void run() {
                final Img img = loadImg(e);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        cache.put(key, img);
                        if (key.equals(iv.getTag())) {
                            applyImg(iv, img);
                        }
                    }
                });
            }
        });
    }

    private Img loadImg(Entry e) {
        Drawable d = null;
        boolean banner = false;
        try {
            ActivityInfo ai = pm.getActivityInfo(e.component, 0);
            d = ai.loadBanner(pm);
            if (d == null && ai.applicationInfo != null) {
                d = ai.applicationInfo.loadBanner(pm);
            }
            if (d != null) {
                banner = true;
            } else {
                d = ai.loadIcon(pm);
            }
        } catch (Exception ignored) {
            // fall through to the default icon
        }
        if (d == null) {
            d = pm.getDefaultActivityIcon();
        }
        return new Img(d, banner);
    }

    private void applyImg(ImageView iv, Img img) {
        if (img.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(0, 0, 0, 0);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(18), dp(18), dp(18), dp(18));
        }
        iv.setImageDrawable(img.drawable);
    }

    // ------------------------------------------------------------- scanning

    private Scan scan() {
        Scan s = new Scan();

        s.inputs.addAll(scanInputs());

        Intent lean = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent norm = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<String>();
        Set<String> leanPackages = new HashSet<String>();

        for (ResolveInfo ri : pm.queryIntentActivities(lean, 0)) {
            if (addApp(s.apps, seen, ri, APP, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                leanPackages.add(ri.activityInfo.packageName);
            }
        }
        // Phone-style apps with no TV entry point go to their own row.
        for (ResolveInfo ri : pm.queryIntentActivities(norm, 0)) {
            if (!leanPackages.contains(ri.activityInfo.packageName)) {
                addApp(s.mobile, seen, ri, MOBILE, Intent.CATEGORY_LAUNCHER);
            }
        }
        sortByLabel(s.apps);
        sortByLabel(s.mobile);

        s.tools.add(Entry.tool("Settings", "SET", 0xFF3B6EA5,
                new Intent(Settings.ACTION_SETTINGS)));
        s.tools.add(Entry.tool("Developer options", "DEV", 0xFF1F8A82,
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.DevelopmentSettings")),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$DevelopmentSettingsActivity"))));
        s.tools.add(Entry.tool("Wi-Fi", "WIFI", 0xFF2A7FB8,
                new Intent(Settings.ACTION_WIFI_SETTINGS)));
        return s;
    }

    private List<Entry> scanInputs() {
        List<Entry> out = new ArrayList<Entry>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) {
                return out;
            }
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput()) {
                    continue;
                }
                CharSequence l = info.loadLabel(this);
                String label = l != null ? l.toString() : info.getId();
                String glyph = info.getType() == TvInputInfo.TYPE_HDMI ? "HDMI" : "INPUT";
                out.add(Entry.input(label, glyph, info.getId()));
            }
        } catch (Exception ignored) {
            // no inputs row on a TV that won't tell us
        }
        sortByLabel(out);
        return out;
    }

    private boolean addApp(List<Entry> list, Set<String> seen, ResolveInfo ri, int kind,
                           String category) {
        if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) {
            return false;
        }
        ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        if (!seen.add(cn.flattenToString())) {
            return false;
        }
        CharSequence label = ri.loadLabel(pm);
        list.add(Entry.app(kind, label != null ? label.toString() : cn.getPackageName(),
                cn, category));
        return true;
    }

    private void sortByLabel(List<Entry> list) {
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    // ---------------------------------------------------------------- actions

    private void launch(Entry e) {
        if (e.kind == TOOL) {
            for (Intent i : e.intents) {
                if (tryStart(i)) {
                    return;
                }
            }
            toast("Not available on this TV");
            return;
        }
        if (e.kind == INPUT) {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(e.inputId));
            if (!tryStart(i)) {
                toast("Use the SOURCE button on your remote for this input");
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(e.category)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!tryStart(i)) {
            toast("Can't open " + e.label);
        }
    }

    private void showAppInfo(Entry e) {
        if (!e.isApp()) {
            return;
        }
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.component.getPackageName(), null));
        if (!tryStart(i)) {
            toast("App info isn't available on this TV");
        }
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------- wallpaper

    /**
     * Generated wallpaper: a deep purple gradient with soft color glows and a dark scrim
     * on the left for text. Rendered once into a small bitmap and scaled, so drawing it
     * every frame is a single cheap blit.
     */
    private static final class Backdrop extends View {
        private Bitmap bmp;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        Backdrop(Context context) {
            super(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            dst.set(0, 0, w, h);
            if (w <= 0 || h <= 0) {
                return;
            }
            int bw = 480;
            int bh = Math.max(1, Math.round(480f * h / w));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint();

            // Deep navy base.
            p.setShader(new LinearGradient(0, 0, 0, bh,
                    new int[]{0xFF04101C, 0xFF082234, 0xFF030911}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);

            // Teal glow, upper right.
            p.setShader(new RadialGradient(bw * 0.78f, bh * 0.30f, bw * 0.60f,
                    new int[]{0xD01FB5A5, 0x80167C98, 0x00167C98},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);

            // Cyan-blue glow, lower right.
            p.setShader(new RadialGradient(bw * 0.97f, bh * 0.80f, bw * 0.46f,
                    new int[]{0xA02A7FE0, 0x002A7FE0}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);

            // Deep blue glow, bottom middle.
            p.setShader(new RadialGradient(bw * 0.55f, bh * 1.05f, bw * 0.50f,
                    new int[]{0x801D4ED8, 0x001D4ED8}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);

            p.setShader(new LinearGradient(0, 0, bw * 0.75f, 0,
                    new int[]{0xD9000000, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);

            if (bmp != null) {
                bmp.recycle();
            }
            bmp = b;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dst, paint);
            }
        }
    }
}
