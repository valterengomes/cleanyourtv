package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Home as it is now, for the LWNGE dashboard's preview (read-only, through ThemeProvider as "home.json"): the dock,
 * the recent row, the grid and the Shelves rows in Home's own order, each app with its name and its tile art (the
 * banner, or the icon and the card colour behind it). Made on request from the Home that is running; nothing is
 * stored. Home not running, or its tiles let go under memory pressure: no file, and the dashboard shows sample apps.
 */
final class HomeExport {
    static final String FILE_NAME = "home.json";
    /** Enough for the preview: a few grid rows, and the start of each shelf. */
    private static final int MAX_GRID = 21, MAX_SHELF = 12;
    private static final int BANNER_W = 240, BANNER_H = 135, ICON = 96;

    private static WeakReference<AppAdapter> home = new WeakReference<>(null);

    private HomeExport() {}

    /** The running Home's tiles (MainActivity). */
    static void attach(AppAdapter adapter) {
        home = new WeakReference<>(adapter);
    }

    /** One app as the export needs it; the drawables are private copies, safe to draw off the main thread. */
    private static final class App {
        final String pkg, label;
        final Drawable banner, icon;
        /** The tile's colour (the focus glow), and the card colour behind an icon tile. */
        final int accent, tint;

        App(AppAdapter adapter, AppAdapter.Entry e) {
            pkg = e.pkg;
            label = e.label;
            banner = copy(e.banner);
            icon = banner == null ? copy(e.icon) : null;
            accent = adapter.colorOf(e);
            tint = banner == null ? AppAdapter.tintedCard(accent) : 0;
        }
    }

    /** The places on Home, as indexes into the app list; -1 is an empty dock slot. */
    private static final class Snapshot {
        final List<App> apps = new ArrayList<>();
        final List<Integer> dock = new ArrayList<>(), recent = new ArrayList<>(), grid = new ArrayList<>();
        final List<String> shelfTitles = new ArrayList<>();
        final List<List<Integer>> shelves = new ArrayList<>();
        final List<Integer> shelfCounts = new ArrayList<>();
        int dockSize;
    }

    /** A pipe the caller reads home.json from; written on a thread of its own. */
    static ParcelFileDescriptor open(Context ctx) throws FileNotFoundException {
        Snapshot snap = snapshot(ctx);
        if (snap == null) throw new FileNotFoundException(FILE_NAME);
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            throw new FileNotFoundException(FILE_NAME);
        }
        ParcelFileDescriptor out = pipe[1];
        new Thread(() -> {
            try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(out)) {
                os.write(json(snap).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | JSONException | RuntimeException ignored) {
                // the reader sees a short file and falls back to sample apps
            }
        }, "home-export").start();
        return pipe[0];
    }

    /** Reads Home's tiles on the main thread, where they change; null when Home has none. */
    private static Snapshot snapshot(Context ctx) {
        AppAdapter adapter = home.get();
        if (adapter == null) return null;
        Snapshot[] result = new Snapshot[1];
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = take(ctx, adapter);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    private static Snapshot take(Context ctx, AppAdapter adapter) {
        List<AppAdapter.Entry> items = adapter.entries();
        if (items.isEmpty()) return null;
        Snapshot snap = new Snapshot();
        Map<AppAdapter.Entry, Integer> index = new IdentityHashMap<>();
        int dockSize = adapter.gridStart(), recentEnd = dockSize + adapter.recentCount();
        snap.dockSize = dockSize;
        for (int i = 0; i < items.size(); i++) {
            AppAdapter.Entry e = items.get(i);
            if (i < dockSize) {
                snap.dock.add(e.kind == AppAdapter.Entry.EMPTY ? -1 : add(snap, index, adapter, e));
            } else if (e.kind == AppAdapter.Entry.APP || e.kind == AppAdapter.Entry.CARD) {
                if (i < recentEnd) snap.recent.add(add(snap, index, adapter, e));
                else if (snap.grid.size() < MAX_GRID) snap.grid.add(add(snap, index, adapter, e));
            }
        }
        List<List<AppAdapter.Entry>> groups = Shelves.groups(ctx, adapter.homeApps());
        for (int s = 0; s < groups.size(); s++) {
            if (groups.get(s).isEmpty()) continue;
            List<Integer> row = new ArrayList<>();
            for (AppAdapter.Entry e : groups.get(s)) {
                if (row.size() == MAX_SHELF) break;
                row.add(add(snap, index, adapter, e));
            }
            snap.shelfTitles.add(ctx.getString(Shelves.title(s)));
            snap.shelves.add(row);
            snap.shelfCounts.add(groups.get(s).size());
        }
        return snap;
    }

    private static int add(Snapshot snap, Map<AppAdapter.Entry, Integer> index, AppAdapter adapter, AppAdapter.Entry e) {
        Integer at = index.get(e);
        if (at != null) return at;
        snap.apps.add(new App(adapter, e));
        index.put(e, snap.apps.size() - 1);
        return snap.apps.size() - 1;
    }

    private static String json(Snapshot snap) throws JSONException {
        JSONArray apps = new JSONArray();
        for (App a : snap.apps) {
            JSONObject o = new JSONObject().put("pkg", a.pkg).put("label", a.label).put("accent", hex(a.accent));
            if (a.banner != null) {
                o.put("banner", webp(a.banner, BANNER_W, BANNER_H));
            } else {
                o.put("icon", webp(a.icon, ICON, ICON));
                o.put("tint", hex(a.tint));
            }
            apps.put(o);
        }
        JSONArray shelves = new JSONArray();
        for (int s = 0; s < snap.shelves.size(); s++) {
            shelves.put(new JSONObject().put("title", snap.shelfTitles.get(s)).put("count", snap.shelfCounts.get(s)).put("apps", new JSONArray(snap.shelves.get(s))));
        }
        return new JSONObject()
                .put("version", 1)
                .put("dock_size", snap.dockSize)
                .put("dock", new JSONArray(snap.dock))
                .put("recent", new JSONArray(snap.recent))
                .put("grid", new JSONArray(snap.grid))
                .put("shelves", shelves)
                .put("apps", apps)
                .toString();
    }

    /** The art drawn as the tile shows it (a banner cropped to the tile's shape), as base64 WebP; "" for none. */
    private static String webp(Drawable d, int w, int h) {
        if (d == null) return "";
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            // centerCrop, like the tile's ImageView
            float scale = Math.max(w / (float) iw, h / (float) ih);
            int sw = Math.round(iw * scale), sh = Math.round(ih * scale);
            d.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(new Canvas(bmp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.WEBP, 85, out);
        bmp.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState state = d.getConstantState();
        return state != null ? state.newDrawable().mutate() : null;
    }
}
