package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure JVM-testable logic for parsing live process memory, task recency,
 * user-app eligibility filtering, and RAM formatting for the Recent Apps overlay.
 */
public final class RecentsLogic {

    public static final String SELF_PACKAGE = "com.gathin.tvhome";
    public static final String SECTION_PS = "===PS===";
    public static final String SECTION_LRU = "===LRU===";
    public static final String SECTION_RECENTS = "===RECENTS===";
    public static final int MIN_USER_APP_UID = 10000;
    public static final int FIRST_ISOLATED_UID = 90000;

    /**
     * Combined loopback shell command reading memory, live process RSS, LRU ranks,
     * and recent task order in one fast round-trip (~0.2s on TV).
     */
    public static final String SNAPSHOT_SHELL_COMMAND =
            "head -n 8 /proc/meminfo"
                    + "; echo '" + SECTION_PS + "'; ps -A -o PID,UID,RSS,NAME"
                    + "; echo '" + SECTION_LRU + "'; dumpsys activity lru"
                    + "; echo '" + SECTION_RECENTS + "'; dumpsys activity recents";

    private static final Set<String> EXCLUDED_PACKAGES = new HashSet<>();
    private static final Set<String> ALLOWED_SYSTEM_PACKAGES = new HashSet<>();

    static {
        Collections.addAll(EXCLUDED_PACKAGES,
                SELF_PACKAGE,
                "com.android.tv.settings",
                "flar2.homebutton",
                "com.neilturner.aerialviews");
        ALLOWED_SYSTEM_PACKAGES.add("com.android.vending");
    }

    private static final Pattern LRU_PROC_PATTERN =
            Pattern.compile("\\b(\\d+):([^/\\s]+)/([^\\s]+)");
    private static final Pattern RECENTS_HEADER_PATTERN =
            Pattern.compile("^\\s*\\*\\s+Recent\\s+#(\\d+):\\s+Task\\{[^}]*\\btype=([^\\s}]+)\\s+([^}]*)\\}");
    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile("(?:mActivityComponent|cmp)=([A-Za-z0-9_.]+)/");
    private static final Pattern AFFINITY_PKG_PATTERN =
            Pattern.compile("\\b[AI]=(?:\\d+:)?([A-Za-z0-9_.]+)");
    private static final Pattern LAST_ACTIVE_PATTERN =
            Pattern.compile("\\blastActiveTime=(\\d+)");

    private RecentsLogic() {}

    public static final class MemSummary {
        public final long totalKb;
        public final long availableKb;
        public final long usedKb;

        public MemSummary(long totalKb, long availableKb) {
            this.totalKb = Math.max(0L, totalKb);
            this.availableKb = Math.max(0L, Math.min(availableKb, this.totalKb > 0 ? this.totalKb : availableKb));
            this.usedKb = Math.max(0L, this.totalKb - this.availableKb);
        }

        public long totalMb() {
            return kbToMb(totalKb);
        }

        public long availableMb() {
            return kbToMb(availableKb);
        }

        public long usedMb() {
            return kbToMb(usedKb);
        }
    }

    public static final class ProcessAggregate {
        public final String baseName;
        public int uid = -1;
        public long totalRssKb = 0L;
        public int pidCount = 0;

        public ProcessAggregate(String baseName) {
            this.baseName = baseName;
        }
    }

    public static final class RecencyEntry {
        public final String packageName;
        public final int recentIndex;
        public final long lastActiveTime;
        public final int lruRank;

        public RecencyEntry(String packageName, int recentIndex, long lastActiveTime, int lruRank) {
            this.packageName = packageName;
            this.recentIndex = recentIndex;
            this.lastActiveTime = lastActiveTime;
            this.lruRank = lruRank;
        }
    }

    public static final class LaunchableApp {
        public final String packageName;
        public final String label;
        public final int uid;
        public final boolean systemApp;
        public final boolean updatedSystemApp;

        public LaunchableApp(String packageName, String label, int uid,
                boolean systemApp, boolean updatedSystemApp) {
            this.packageName = packageName;
            this.label = label == null ? packageName : label;
            this.uid = uid;
            this.systemApp = systemApp;
            this.updatedSystemApp = updatedSystemApp;
        }
    }

    public static final class RunningApp {
        public final String packageName;
        public final String label;
        public final long rssKb;
        public final int pidCount;
        public final int recentIndex;
        public final long lastActiveTime;
        public final int lruRank;

        public RunningApp(String packageName, String label, long rssKb, int pidCount,
                int recentIndex, long lastActiveTime, int lruRank) {
            this.packageName = packageName;
            this.label = label;
            this.rssKb = rssKb;
            this.pidCount = pidCount;
            this.recentIndex = recentIndex;
            this.lastActiveTime = lastActiveTime;
            this.lruRank = lruRank;
        }

        public long ramMb() {
            return kbToMb(rssKb);
        }
    }

    public static final class Snapshot {
        public final MemSummary memSummary;
        public final Map<String, ProcessAggregate> processesByPkg;
        public final Map<String, RecencyEntry> recencyByPkg;
        public final String foregroundPackageBeforeRecents;

        public Snapshot(MemSummary memSummary,
                Map<String, ProcessAggregate> processesByPkg,
                Map<String, RecencyEntry> recencyByPkg,
                String foregroundPackageBeforeRecents) {
            this.memSummary = memSummary;
            this.processesByPkg = processesByPkg;
            this.recencyByPkg = recencyByPkg;
            this.foregroundPackageBeforeRecents = foregroundPackageBeforeRecents;
        }
    }

    public static long kbToMb(long kb) {
        if (kb <= 0L) return 0L;
        return Math.max(1L, Math.round(kb / 1024.0));
    }

    public static String formatRamCompact(long kb) {
        long mb = kbToMb(kb);
        if (mb >= 1024L) {
            return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        }
        return mb + " MB";
    }

    public static String formatMemoryHeader(MemSummary mem) {
        if (mem == null || mem.totalKb <= 0L) return "";
        return "Free: " + mem.availableMb() + " MB / "
                + String.format(Locale.US, "%.1f GB", mem.totalMb() / 1024.0);
    }

    public static boolean isExcludedPackage(String pkg) {
        return pkg == null || pkg.isEmpty() || EXCLUDED_PACKAGES.contains(pkg);
    }

    public static boolean isEligiblePackage(String pkg, int uid, boolean systemApp,
            boolean launchable) {
        if (!launchable || isExcludedPackage(pkg)) return false;
        if (uid > 0 && uid < MIN_USER_APP_UID) return false;
        return !systemApp || ALLOWED_SYSTEM_PACKAGES.contains(pkg);
    }

    public static boolean shouldInterceptHomeHold(boolean homeLongPressHome,
            boolean recentsEnabled, int repeatCount) {
        return homeLongPressHome && recentsEnabled && repeatCount == 0;
    }

    public static Snapshot parseSnapshot(String rawOutput) {
        if (rawOutput == null) rawOutput = "";
        int psIdx = rawOutput.indexOf(SECTION_PS);
        int lruIdx = rawOutput.indexOf(SECTION_LRU);
        int recIdx = rawOutput.indexOf(SECTION_RECENTS);

        String memSection = sliceBefore(rawOutput, 0, firstPositive(psIdx, lruIdx, recIdx));
        String psSection = psIdx >= 0
                ? sliceBetween(rawOutput, psIdx + SECTION_PS.length(), firstPositive(lruIdx, recIdx))
                : "";
        String lruSection = lruIdx >= 0
                ? sliceBetween(rawOutput, lruIdx + SECTION_LRU.length(), recIdx)
                : "";
        String recentsSection = recIdx >= 0
                ? rawOutput.substring(recIdx + SECTION_RECENTS.length())
                : "";

        MemSummary mem = parseMemInfo(memSection);
        Map<String, ProcessAggregate> processes = parsePs(psSection);
        RecentsParseResult recentsResult = parseRecentsAndLru(lruSection, recentsSection);
        return new Snapshot(mem, processes, recentsResult.recencyByPkg, recentsResult.foregroundPackage);
    }

    public static MemSummary parseMemInfo(String section) {
        if (section == null || section.isEmpty()) return new MemSummary(0L, 0L);
        long totalKb = 0L;
        long availableKb = -1L;
        long freeKb = 0L;
        long buffersKb = 0L;
        long cachedKb = 0L;
        for (String line : section.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            long value = parseFirstLong(line.substring(colon + 1));
            if (value < 0L) continue;
            switch (key) {
                case "MemTotal":
                    totalKb = value;
                    break;
                case "MemAvailable":
                    availableKb = value;
                    break;
                case "MemFree":
                    freeKb = value;
                    break;
                case "Buffers":
                    buffersKb = value;
                    break;
                case "Cached":
                    cachedKb = value;
                    break;
                default:
                    break;
            }
        }
        if (availableKb < 0L) {
            availableKb = freeKb + buffersKb + cachedKb;
        }
        return new MemSummary(totalKb, availableKb);
    }

    public static Map<String, ProcessAggregate> parsePs(String section) {
        Map<String, ProcessAggregate> byPkg = new LinkedHashMap<>();
        if (section == null || section.isEmpty()) return byPkg;
        for (String rawLine : section.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("PID")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            int pid = parsePositiveInt(parts[0], -1);
            if (pid <= 0) continue;
            int uid = parseUidToken(parts[1]);
            long rssKb = parseFirstLong(parts[2]);
            if (rssKb < 0L) continue;
            String procName = parts[3];
            if (procName.startsWith("[")) continue;
            String basePkg = baseProcessName(procName);
            if (basePkg.isEmpty()) continue;

            ProcessAggregate agg = byPkg.get(basePkg);
            if (agg == null) {
                agg = new ProcessAggregate(basePkg);
                byPkg.put(basePkg, agg);
            }
            if (procName.equals(basePkg) && uid >= 0 && uid < FIRST_ISOLATED_UID) {
                agg.uid = uid;
            } else if (agg.uid < 0 && uid >= 0 && uid < FIRST_ISOLATED_UID) {
                agg.uid = uid;
            }
            agg.totalRssKb += rssKb;
            agg.pidCount++;
        }
        return byPkg;
    }

    private static final class RecentsParseResult {
        final Map<String, RecencyEntry> recencyByPkg;
        final String foregroundPackage;

        RecentsParseResult(Map<String, RecencyEntry> recencyByPkg, String foregroundPackage) {
            this.recencyByPkg = recencyByPkg;
            this.foregroundPackage = foregroundPackage;
        }
    }

    private static RecentsParseResult parseRecentsAndLru(String lruSection, String recentsSection) {
        Map<String, Integer> lruRanks = parseLruRanks(lruSection);
        Map<String, RecencyEntry> entries = new HashMap<>();
        String foregroundPkg = null;

        if (recentsSection != null && !recentsSection.isEmpty()) {
            int currentRecentIdx = -1;
            String currentType = "";
            String currentPkg = null;
            boolean currentIsSelfOverlay = false;
            long currentLastActive = 0L;

            for (String line : recentsSection.split("\\R")) {
                Matcher header = RECENTS_HEADER_PATTERN.matcher(line);
                if (header.find()) {
                    foregroundPkg = recordRecentTask(entries, foregroundPkg,
                            currentRecentIdx, currentType, currentPkg, currentIsSelfOverlay,
                            currentLastActive, lruRanks);
                    currentRecentIdx = parsePositiveInt(header.group(1), currentRecentIdx + 1);
                    currentType = header.group(2);
                    currentPkg = null;
                    currentIsSelfOverlay = false;
                    currentLastActive = 0L;
                    String headerRest = header.group(3);
                    if (isSelfOverlayComponent(headerRest)) {
                        currentIsSelfOverlay = true;
                    }
                    Matcher aff = AFFINITY_PKG_PATTERN.matcher(headerRest);
                    if (aff.find()) {
                        currentPkg = aff.group(1);
                    }
                    continue;
                }
                if (currentRecentIdx < 0) continue;

                if (isSelfOverlayComponent(line)) {
                    currentIsSelfOverlay = true;
                }
                Matcher cmp = COMPONENT_PATTERN.matcher(line);
                if (cmp.find()) {
                    currentPkg = cmp.group(1);
                }
                Matcher lastActive = LAST_ACTIVE_PATTERN.matcher(line);
                if (lastActive.find()) {
                    currentLastActive = Math.max(currentLastActive, parsePositiveLong(lastActive.group(1), 0L));
                }
            }
            foregroundPkg = recordRecentTask(entries, foregroundPkg,
                    currentRecentIdx, currentType, currentPkg, currentIsSelfOverlay,
                    currentLastActive, lruRanks);
        }

        // Fill LRU ranks for live running apps without a recent task header (e.g., started via service).
        for (Map.Entry<String, Integer> lru : lruRanks.entrySet()) {
            String pkg = lru.getKey();
            if (!entries.containsKey(pkg)) {
                entries.put(pkg, new RecencyEntry(pkg, Integer.MAX_VALUE, 0L, lru.getValue()));
            }
        }
        return new RecentsParseResult(entries, foregroundPkg);
    }

    private static String recordRecentTask(Map<String, RecencyEntry> entries,
            String foregroundPkg, int recentIdx, String type, String pkg,
            boolean isSelfOverlay, long lastActiveTime, Map<String, Integer> lruRanks) {
        if (recentIdx < 0 || pkg == null || pkg.isEmpty() || isSelfOverlay) return foregroundPkg;
        boolean isHome = "home".equals(type) || SELF_PACKAGE.equals(pkg);
        if (foregroundPkg == null) {
            if (isHome) {
                foregroundPkg = SELF_PACKAGE;
            } else {
                foregroundPkg = pkg;
            }
        }
        if ("home".equals(type) || "dream".equals(type) || isExcludedPackage(pkg)) {
            return foregroundPkg;
        }
        RecencyEntry prev = entries.get(pkg);
        int lruRank = lruRanks.containsKey(pkg) ? lruRanks.get(pkg) : Integer.MAX_VALUE;
        if (prev == null || recentIdx < prev.recentIndex) {
            entries.put(pkg, new RecencyEntry(pkg, recentIdx, lastActiveTime, lruRank));
        }
        return foregroundPkg;
    }

    private static boolean isSelfOverlayComponent(String line) {
        return line.contains(SELF_PACKAGE + "/.RecentsActivity")
                || line.contains(SELF_PACKAGE + "/.QuickSettingsActivity")
                || line.contains(SELF_PACKAGE + "/.LauncherSettingsActivity");
    }

    public static Map<String, Integer> parseLruRanks(String lruSection) {
        Map<String, Integer> ranks = new HashMap<>();
        if (lruSection == null || lruSection.isEmpty()) return ranks;
        int rank = 0;
        for (String line : lruSection.split("\\R")) {
            Matcher m = LRU_PROC_PATTERN.matcher(line);
            if (!m.find()) continue;
            String basePkg = baseProcessName(m.group(2));
            if (!basePkg.isEmpty() && !ranks.containsKey(basePkg)) {
                ranks.put(basePkg, rank);
            }
            rank++;
        }
        return ranks;
    }

    public static List<RunningApp> buildRunningApps(Snapshot snapshot, List<LaunchableApp> launchableApps) {
        if (snapshot == null || launchableApps == null || launchableApps.isEmpty()) {
            return Collections.emptyList();
        }
        List<RunningApp> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LaunchableApp app : launchableApps) {
            if (app == null || !seen.add(app.packageName)) continue;
            if (!isEligiblePackage(app.packageName, app.uid, app.systemApp, true)) continue;

            ProcessAggregate proc = snapshot.processesByPkg.get(app.packageName);
            if (proc == null || proc.pidCount <= 0) continue;

            int effectiveUid = proc.uid > 0 ? proc.uid : app.uid;
            if (effectiveUid > 0 && effectiveUid < MIN_USER_APP_UID) continue;

            RecencyEntry rec = snapshot.recencyByPkg.get(app.packageName);
            int recentIdx = rec != null ? rec.recentIndex : Integer.MAX_VALUE;
            long lastActive = rec != null ? rec.lastActiveTime : 0L;
            int lruRank = rec != null ? rec.lruRank : Integer.MAX_VALUE;

            out.add(new RunningApp(app.packageName, app.label, proc.totalRssKb, proc.pidCount,
                    recentIdx, lastActive, lruRank));
        }

        Collections.sort(out, new Comparator<RunningApp>() {
            @Override
            public int compare(RunningApp a, RunningApp b) {
                if (a.recentIndex != b.recentIndex) {
                    return Integer.compare(a.recentIndex, b.recentIndex);
                }
                if (a.lastActiveTime != b.lastActiveTime) {
                    return Long.compare(b.lastActiveTime, a.lastActiveTime);
                }
                if (a.lruRank != b.lruRank) {
                    return Integer.compare(a.lruRank, b.lruRank);
                }
                if (a.rssKb != b.rssKb) {
                    return Long.compare(b.rssKb, a.rssKb);
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    public static long estimateFreedMb(MemSummary before, MemSummary after, long closedAppsRssKb) {
        if (before != null && after != null && after.availableKb > before.availableKb) {
            long deltaKb = after.availableKb - before.availableKb;
            if (deltaKb >= 1024L) {
                return kbToMb(deltaKb);
            }
        }
        return kbToMb(closedAppsRssKb);
    }

    static String baseProcessName(String procName) {
        if (procName == null) return "";
        int colon = procName.indexOf(':');
        return (colon > 0 ? procName.substring(0, colon) : procName).trim();
    }

    static int parseUidToken(String token) {
        if (token == null || token.isEmpty()) return -1;
        if (Character.isDigit(token.charAt(0))) {
            return parsePositiveInt(token, -1);
        }
        // Formats like u0_a146 or u0a146 or u0_i5
        int aIdx = token.indexOf("_a");
        if (aIdx >= 0) {
            int appId = parsePositiveInt(token.substring(aIdx + 2), -1);
            return appId >= 0 ? MIN_USER_APP_UID + appId : -1;
        }
        int u0aIdx = token.indexOf("u0a");
        if (u0aIdx >= 0) {
            int appId = parsePositiveInt(token.substring(u0aIdx + 3), -1);
            return appId >= 0 ? MIN_USER_APP_UID + appId : -1;
        }
        return -1;
    }

    private static String sliceBefore(String s, int start, int end) {
        if (end < 0 || end > s.length()) return s.substring(start);
        return s.substring(start, end);
    }

    private static String sliceBetween(String s, int start, int end) {
        if (start >= s.length()) return "";
        if (end < 0 || end <= start) return s.substring(start);
        return s.substring(start, end);
    }

    private static int firstPositive(int... values) {
        int best = -1;
        for (int v : values) {
            if (v >= 0 && (best < 0 || v < best)) best = v;
        }
        return best;
    }

    private static long parseFirstLong(String text) {
        if (text == null) return -1L;
        String trimmed = text.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) end++;
        if (end == 0) return -1L;
        try {
            return Long.parseLong(trimmed.substring(0, end));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static int parsePositiveInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parsePositiveLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
