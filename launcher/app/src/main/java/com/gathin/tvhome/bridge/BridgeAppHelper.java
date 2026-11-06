package com.gathin.tvhome.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AtomicFile;
import android.util.Log;

import com.gathin.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicates the launcher's app listing, ordering, and visibility rules read-only.
 */
public final class BridgeAppHelper {
    private static final String TAG = "BridgeAppHelper";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final Map<String, Boolean> ACTION_VIEW_CACHE = new ConcurrentHashMap<>();

    public static class AppEntry {
        public final String packageName;
        public final String activityName;
        public final String label;
        public final boolean isDock;
        public final int dockPosition; // 0-based if isDock, -1 otherwise
        public final boolean launchOnly;

        public AppEntry(String packageName, String activityName, String label, boolean isDock, int dockPosition, boolean launchOnly) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.isDock = isDock;
            this.dockPosition = dockPosition;
            this.launchOnly = launchOnly;
        }

        public String getComponentKey() {
            if (activityName == null || activityName.isEmpty()) return packageName;
            if (activityName.startsWith(packageName + ".")) {
                return packageName + "/." + activityName.substring(packageName.length() + 1);
            }
            if (activityName.startsWith(".")) {
                return packageName + "/" + activityName;
            }
            return packageName + "/" + activityName;
        }
    }

    private BridgeAppHelper() {}

    private static Set<String> readTextFileLines(File file) {
        if (!file.exists()) return Collections.emptySet();
        try {
            AtomicFile atomicFile = new AtomicFile(file);
            try (FileInputStream in = atomicFile.openRead()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                String content = out.toString(StandardCharsets.UTF_8.name());
                String[] lines = content.split("\n");
                Set<String> set = new HashSet<>();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) set.add(trimmed);
                }
                return set;
            }
        } catch (IOException e) {
            Log.w(TAG, "Error reading " + file.getName(), e);
            return Collections.emptySet();
        }
    }

    private static List<String> readThemeStringList(Context context, String key, List<String> fallback) {
        File themeFile = new File(context.getFilesDir(), "theme/config.json");
        if (!themeFile.exists()) return fallback;
        try (FileInputStream in = new FileInputStream(themeFile)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (json.has(key)) {
                JSONArray arr = json.getJSONArray(key);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
                return list;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static Map<String, String> readThemeStringMap(Context context, String key) {
        Map<String, String> map = new HashMap<>();
        File themeFile = new File(context.getFilesDir(), "theme/config.json");
        if (!themeFile.exists()) return map;
        try (FileInputStream in = new FileInputStream(themeFile)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            if (json.has(key)) {
                JSONObject obj = json.getJSONObject(key);
                for (java.util.Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, obj.getString(k));
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    public static class SavedOrderInfo {
        public final Map<String, Integer> ranks = new HashMap<>();
        public int docked = -1;
    }

    public static SavedOrderInfo readSavedOrder(Context context) {
        SavedOrderInfo info = new SavedOrderInfo();
        File orderFile = new File(context.getFilesDir(), "order.txt");
        if (!orderFile.exists()) return info;

        try {
            AtomicFile af = new AtomicFile(orderFile);
            String text = new String(af.readFully(), StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            if (lines.length < 2 || !lines[0].equals("tvhome-order 1") || !lines[1].startsWith("dock ")) {
                return info;
            }

            int docked = -1;
            int first = 2;
            if (lines.length > 2 && lines[2].startsWith("docked ")) {
                try {
                    docked = Integer.parseInt(lines[2].substring("docked ".length()).trim());
                } catch (NumberFormatException ignored) {}
                if (docked >= 0) {
                    first = 3;
                    info.docked = docked;
                }
            }

            int rank = 0;
            for (int i = first; i < lines.length; i++) {
                String k = lines[i].trim();
                if (k.isEmpty()) continue;
                if (!info.ranks.containsKey(k)) {
                    info.ranks.put(k, rank++);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read order.txt", e);
        }
        return info;
    }

    public static List<AppEntry> getLauncherAppList(Context context) {
        PackageManager pm = context.getPackageManager();
        Resources res = context.getResources();

        List<String> defaultDock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        final List<String> dockOrder = readThemeStringList(context, "dock_apps", defaultDock);

        List<String> hiddenList = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        hiddenList = readThemeStringList(context, "hidden_apps", hiddenList);
        Set<String> hidden = new HashSet<>(hiddenList);

        Set<String> added = readTextFileLines(new File(context.getFilesDir(), "library_apps.txt"));
        List<String> libraryConfig = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.library_apps)));
        libraryConfig = readThemeStringList(context, "library_apps", libraryConfig);
        added.addAll(libraryConfig);

        Set<String> removed = readTextFileLines(new File(context.getFilesDir(), "library_home_removed.txt"));

        Map<String, String> appLabels = readThemeStringMap(context, "app_labels");

        // Query Leanback Launcher apps
        List<ResolveInfo> found = new ArrayList<>();
        List<ResolveInfo> leanback = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        if (leanback != null) found.addAll(leanback);

        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        }

        // Add non-leanback library apps if explicitly added
        if (!added.isEmpty()) {
            List<ResolveInfo> standard = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            if (standard != null) {
                for (ResolveInfo ri : standard) {
                    if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                            && !tvPackages.contains(ri.activityInfo.packageName)) {
                        found.add(ri);
                        tvPackages.add(ri.activityInfo.packageName);
                    }
                }
            }
        }

        List<AppEntry> candidateEntries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(context.getPackageName())) continue;
            if (!ri.activityInfo.exported) continue;

            if (!isAppEnabled(pm, pkg, ri)) continue;

            boolean isTv = tvPackages.contains(pkg);
            boolean isExplicit = added.contains(pkg);
            boolean isRemoved = removed.contains(pkg);
            boolean isHidden = hidden.contains(pkg);

            boolean isVisible = !isRemoved && (isExplicit || (isTv && !isHidden));
            if (!isVisible) continue;

            String rawLabel = appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm));
            String label = cleanLabel(rawLabel, pkg);

            if (!seen.add(pkg + '\n' + label)) continue;

            boolean launchOnly = !hasActionViewFilter(context, pkg);

            candidateEntries.add(new AppEntry(pkg, ri.activityInfo.name, label, false, -1, launchOnly));
        }

        // Read saved order from order.txt
        SavedOrderInfo savedOrder = readSavedOrder(context);
        Map<String, Integer> savedRanks = savedOrder.ranks;
        int savedDocked = savedOrder.docked;

        // Sort entries strictly according to launcher's AppAdapter logic
        Collections.sort(candidateEntries, (a, b) -> {
            Integer rankA = savedRanks.get(a.getComponentKey());
            Integer rankB = savedRanks.get(b.getComponentKey());
            if (rankA != null && rankB != null) return Integer.compare(rankA, rankB);
            if (rankA != null) return -1;
            if (rankB != null) return 1;

            int fa = dockOrder.indexOf(a.packageName);
            int fb = dockOrder.indexOf(b.packageName);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }

            return a.label.compareToIgnoreCase(b.label);
        });

        // Determine dock status for each entry
        List<AppEntry> result = new ArrayList<>();
        int dockIndex = 0;
        for (AppEntry entry : candidateEntries) {
            boolean isDock;
            if (savedDocked >= 0) {
                Integer rank = savedRanks.get(entry.getComponentKey());
                isDock = (rank != null && rank < savedDocked);
            } else {
                isDock = dockOrder.contains(entry.packageName);
            }

            if (isDock) {
                result.add(new AppEntry(entry.packageName, entry.activityName, entry.label, true, dockIndex++, entry.launchOnly));
            } else {
                result.add(new AppEntry(entry.packageName, entry.activityName, entry.label, false, -1, entry.launchOnly));
            }
        }

        return result;
    }

    public static boolean hasActionViewFilter(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        Boolean cached = ACTION_VIEW_CACHE.get(pkg);
        if (cached != null) return cached;

        boolean foundView = false;
        try {
            Context pkgCtx = context.createPackageContext(pkg, 0);
            XmlResourceParser parser = pkgCtx.getAssets().openXmlResourceParser("AndroidManifest.xml");
            int eventType = parser.getEventType();
            boolean inActivity = false;
            boolean inFilter = false;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                        inActivity = true;
                    } else if (inActivity && "intent-filter".equals(tag)) {
                        inFilter = true;
                    } else if (inFilter && "action".equals(tag)) {
                        String actionName = parser.getAttributeValue(ANDROID_NS, "name");
                        if (Intent.ACTION_VIEW.equals(actionName)) {
                            foundView = true;
                            break;
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("intent-filter".equals(tag)) {
                        inFilter = false;
                    } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                        inActivity = false;
                    }
                }
                eventType = parser.next();
            }
            parser.close();
        } catch (Exception e) {
            // Fallback: check queryIntentActivities with broad schemes
            try {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setPackage(pkg);
                viewIntent.setData(android.net.Uri.parse("https://youtube.com"));
                List<ResolveInfo> resolves = context.getPackageManager().queryIntentActivities(viewIntent, 0);
                if (resolves != null && !resolves.isEmpty()) foundView = true;
            } catch (Exception ignored) {}
        }

        ACTION_VIEW_CACHE.put(pkg, foundView);
        return foundView;
    }

    public static boolean isAppEnabled(PackageManager pm, String pkg, ResolveInfo ri) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            if (!appInfo.enabled) return false;
            int appSetting = pm.getApplicationEnabledSetting(pkg);
            if (appSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || appSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || appSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            if (ri != null && ri.activityInfo != null && !ri.activityInfo.isEnabled()) {
                return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isPackageInAppList(Context context, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        for (AppEntry entry : getLauncherAppList(context)) {
            if (entry.packageName.equals(pkg)) return true;
        }
        return false;
    }

    public static byte[] getAppIconPng(Context context, String packageName) {
        if (!isPackageInAppList(context, packageName)) return null;
        try {
            PackageManager pm = context.getPackageManager();
            Drawable drawable = pm.getApplicationIcon(packageName);
            if (drawable == null) return null;

            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                width = 192;
                height = 192;
            } else {
                width = Math.min(width, 192);
                height = Math.min(height, 192);
            }

            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "Error generating icon PNG for " + packageName, e);
            return null;
        }
    }

    public static String cleanLabel(String s, String fallback) {
        if (s == null) return fallback;
        StringBuilder b = new StringBuilder(Math.min(s.length(), 48));
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            boolean blank = c == ' ' || Character.isISOControl(c) || Character.isWhitespace(c) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SPACE_SEPARATOR;
            if (blank) {
                if (!space) b.append(' ');
                space = true;
                continue;
            }
            boolean pair = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            if (b.length() + (pair ? 2 : 1) > 40) break;
            b.append(c);
            if (pair) b.append(s.charAt(++i));
            space = false;
        }
        String out = b.toString().trim();
        return out.isEmpty() ? fallback : out;
    }
}
