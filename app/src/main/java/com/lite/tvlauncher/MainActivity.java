package com.lite.tvlauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

/**
 * Minimal TV launcher. One activity, no libraries, no wallpaper, no background services.
 *
 * Remote: D-pad to move, OK to open, long-press OK (or MENU) for App info / uninstall.
 * The first tiles are shortcuts to Settings, Developer options and Wi-Fi.
 */
public class MainActivity extends Activity {

    private static final class Entry {
        final String label;
        final String glyph;
        final ComponentName component;
        final String category;
        final Intent[] toolIntents;

        Entry(String label, ComponentName component, String category) {
            this.label = label;
            this.glyph = null;
            this.component = component;
            this.category = category;
            this.toolIntents = null;
        }

        Entry(String label, String glyph, Intent[] toolIntents) {
            this.label = label;
            this.glyph = glyph;
            this.component = null;
            this.category = null;
            this.toolIntents = toolIntents;
        }

        boolean isTool() {
            return toolIntents != null;
        }

        String key() {
            return component != null ? component.flattenToShortString() : "tool:" + label;
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

    private static final class Holder {
        FrameLayout frame;
        ImageView icon;
        TextView glyph;
        TextView label;
    }

    private final List<Entry> items = new ArrayList<Entry>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String snapshot = "";
    private boolean longHandled;

    private PackageManager pm;
    private ExecutorService loader;
    private LruCache<String, Img> cache;
    private GridView grid;
    private Adapter adapter;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        loader = Executors.newSingleThreadExecutor();
        cache = new LruCache<String, Img>(40);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        longHandled = false;
        reload();
        grid.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while we are already running: jump back to the top.
        grid.setSelection(0);
        grid.requestFocus();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // We just got covered by another app: hand our icon memory back to it.
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            cache.evictAll();
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
        // A launcher never exits. Back just returns to the first tile.
        grid.setSelection(0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
                if (!longHandled) {
                    longHandled = true;
                    showAppInfoForSelected();
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && longHandled) {
                longHandled = false;
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                showAppInfoForSelected();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(27), dp(48), dp(20));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF141B25, 0xFF090C11}));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xFF9AA7B8);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(date, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adapter = new Adapter();
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(176));
        grid.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(10));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(18), 0, dp(18));
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(true);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(items.get(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showAppInfo(items.get(position));
                return true;
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private View previous;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (previous != null) {
                    previous.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                if (view != null) {
                    view.animate().scaleX(1.07f).scaleY(1.07f).setDuration(120).start();
                }
                previous = view;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (previous != null) {
                    previous.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                previous = null;
            }
        });

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = dp(8);
        root.addView(grid, glp);
        return root;
    }

    private View makeCell() {
        Holder h = new Holder();

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(8), dp(8), dp(8), dp(8));

        GradientDrawable selected = new GradientDrawable();
        selected.setCornerRadius(dp(10));
        selected.setColor(0x33FFFFFF);
        selected.setStroke(dp(3), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        cell.setBackground(states);

        h.frame = new FrameLayout(this);
        h.icon = new ImageView(this);
        h.glyph = new TextView(this);
        h.glyph.setGravity(Gravity.CENTER);
        h.glyph.setTextColor(Color.WHITE);
        h.glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        h.glyph.setTypeface(Typeface.DEFAULT_BOLD);
        h.glyph.setVisibility(View.GONE);
        h.frame.addView(h.icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        h.frame.addView(h.glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.addView(h.frame, new LinearLayout.LayoutParams(dp(160), dp(90)));

        h.label = new TextView(this);
        h.label.setTextColor(0xFFE6EBF2);
        h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        h.label.setSingleLine(true);
        h.label.setEllipsize(TextUtils.TruncateAt.END);
        h.label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(160),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(6);
        cell.addView(h.label, llp);

        cell.setTag(h);
        return cell;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View cell = convertView != null ? convertView : makeCell();
            Holder h = (Holder) cell.getTag();
            Entry e = items.get(position);

            cell.setScaleX(1f);
            cell.setScaleY(1f);
            h.label.setText(e.label);

            GradientDrawable frameBg = new GradientDrawable();
            frameBg.setCornerRadius(dp(6));

            if (e.isTool()) {
                frameBg.setColor(0xFF2A4A6B);
                h.frame.setBackground(frameBg);
                h.icon.setTag(null);
                h.icon.setImageDrawable(null);
                h.glyph.setText(e.glyph);
                h.glyph.setVisibility(View.VISIBLE);
            } else {
                frameBg.setColor(0xFF1B222C);
                h.frame.setBackground(frameBg);
                h.glyph.setVisibility(View.GONE);
                bindIcon(h.icon, e);
            }
            return cell;
        }
    }

    // ------------------------------------------------------------------- icons

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
            // fall through to default icon
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
            iv.setPadding(dp(16), dp(16), dp(16), dp(16));
        }
        iv.setImageDrawable(img.drawable);
    }

    // ------------------------------------------------------------- app list

    private void reload() {
        List<Entry> fresh = scan();
        StringBuilder sb = new StringBuilder();
        for (Entry e : fresh) {
            sb.append(e.key()).append('|').append(e.label).append('\n');
        }
        String snap = sb.toString();
        if (snap.equals(snapshot)) {
            return;
        }
        snapshot = snap;
        items.clear();
        items.addAll(fresh);
        adapter.notifyDataSetChanged();
    }

    private List<Entry> scan() {
        List<Entry> out = new ArrayList<Entry>();

        out.add(new Entry("Settings", "SET", new Intent[]{
                new Intent(Settings.ACTION_SETTINGS)}));
        out.add(new Entry("Developer options", "DEV", new Intent[]{
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.DevelopmentSettings")),
                new Intent().setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$DevelopmentSettingsActivity"))}));
        out.add(new Entry("Wi-Fi", "WIFI", new Intent[]{
                new Intent(Settings.ACTION_WIFI_SETTINGS)}));

        Intent lean = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent norm = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        List<Entry> apps = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();
        Set<String> leanPackages = new HashSet<String>();

        for (ResolveInfo ri : pm.queryIntentActivities(lean, 0)) {
            if (addApp(apps, seen, ri, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
                leanPackages.add(ri.activityInfo.packageName);
            }
        }
        // Apps with no TV entry point (phone-style apps) still show up, once each.
        for (ResolveInfo ri : pm.queryIntentActivities(norm, 0)) {
            if (!leanPackages.contains(ri.activityInfo.packageName)) {
                addApp(apps, seen, ri, Intent.CATEGORY_LAUNCHER);
            }
        }

        Collections.sort(apps, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        out.addAll(apps);
        return out;
    }

    private boolean addApp(List<Entry> apps, Set<String> seen, ResolveInfo ri, String category) {
        if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) {
            return false;
        }
        ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        if (!seen.add(cn.flattenToString())) {
            return false;
        }
        CharSequence label = ri.loadLabel(pm);
        apps.add(new Entry(label != null ? label.toString() : cn.getPackageName(), cn, category));
        return true;
    }

    // ---------------------------------------------------------------- actions

    private void launch(Entry e) {
        if (e.isTool()) {
            for (Intent i : e.toolIntents) {
                if (tryStart(i)) {
                    return;
                }
            }
            toast("Not available on this TV");
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

    private void showAppInfoForSelected() {
        int p = grid.getSelectedItemPosition();
        if (p >= 0 && p < items.size()) {
            showAppInfo(items.get(p));
        }
    }

    private void showAppInfo(Entry e) {
        if (e.isTool()) {
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
}
