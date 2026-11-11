package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecentsLogicTest {

    @Test
    public void parseMemInfo_readsAvailableAndFormatsHeader() {
        String mem = ""
                + "MemTotal:        1827496 kB\n"
                + "MemFree:          242988 kB\n"
                + "MemAvailable:     703484 kB\n"
                + "Buffers:            1256 kB\n"
                + "Cached:           647612 kB\n";

        RecentsLogic.MemSummary summary = RecentsLogic.parseMemInfo(mem);
        assertEquals(1827496L, summary.totalKb);
        assertEquals(703484L, summary.availableKb);
        assertEquals(1785L, summary.totalMb());
        assertEquals(687L, summary.availableMb());
        assertEquals("Free: 687 MB / 1.7 GB", RecentsLogic.formatMemoryHeader(summary));
    }

    @Test
    public void parseSnapshot_filtersLiveAppsAggregatesSubprocessesAndSortsByRecency() {
        String raw = ""
                + "MemTotal:        1827496 kB\n"
                + "MemAvailable:     528968 kB\n"
                + RecentsLogic.SECTION_PS + "\n"
                + "  PID   UID    RSS NAME\n"
                + "    1     0   3324 init\n"
                + "    2     0      0 [kthreadd]\n"
                + " 4352  1000 120000 com.mitv.livetv\n"
                + " 3186 10133  28000 flar2.homebutton\n"
                + "29751 10141  45488 com.neilturner.aerialviews\n"
                + "22289 10146  95000 com.gathin.tvhome\n"
                + "23657  1000  54000 com.android.tv.settings\n"
                + "11001 10135 160000 io.gh.reisxd.tizentube.cobalt\n"
                + "11055 99001  44800 io.gh.reisxd.tizentube.cobalt:sandboxed_process0\n"
                + "12001 u0_a78  81920 com.android.vending\n"
                + "13001 10140  51200 com.netmirror.tv\n"
                + RecentsLogic.SECTION_LRU + "\n"
                + "  #35: fg TOP 22289:com.gathin.tvhome/u0a146 act:activities\n"
                + "  #34: fg BTOP 11001:io.gh.reisxd.tizentube.cobalt/u0a135 act:activities\n"
                + "  #20: cch CEM 13001:com.netmirror.tv/u0a140 act:activities\n"
                + "  #15: cch CEM 12001:com.android.vending/u0a78\n"
                + RecentsLogic.SECTION_RECENTS + "\n"
                + "  * Recent #0: Task{111 #2210 type=standard I=com.gathin.tvhome/.RecentsActivity}\n"
                + "    mActivityComponent=com.gathin.tvhome/.RecentsActivity\n"
                + "  * Recent #1: Task{222 #2086 type=standard A=10135:io.gh.reisxd.tizentube.cobalt}\n"
                + "    mActivityComponent=io.gh.reisxd.tizentube.cobalt/dev.cobalt.app.MainActivity\n"
                + "    lastActiveTime=20000\n"
                + "  * Recent #2: Task{333 #2154 type=home A=10146:com.gathin.tvhome}\n"
                + "    mActivityComponent=com.gathin.tvhome/.MainActivity\n"
                + "  * Recent #3: Task{444 #2090 type=standard A=10140:com.netmirror.tv}\n"
                + "    mActivityComponent=com.netmirror.tv/.MainActivity\n"
                + "    lastActiveTime=19000\n"
                + "  * Recent #4: Task{555 #2050 type=standard A=10150:org.xbmc.kodi}\n"
                + "    mActivityComponent=org.xbmc.kodi/.Splash\n"
                + "    lastActiveTime=10000\n";

        List<RecentsLogic.LaunchableApp> installed = Arrays.asList(
                new RecentsLogic.LaunchableApp("com.gathin.tvhome", "CleanYourTV", 10146, false, false),
                new RecentsLogic.LaunchableApp("com.android.tv.settings", "Settings", 1000, true, false),
                new RecentsLogic.LaunchableApp("flar2.homebutton", "Button Mapper", 10133, false, false),
                new RecentsLogic.LaunchableApp("com.neilturner.aerialviews", "Aerial Views", 10141, false, false),
                new RecentsLogic.LaunchableApp("com.mitv.livetv", "Live TV", 1000, true, false),
                new RecentsLogic.LaunchableApp("com.google.android.youtube.tv", "YouTube", 10075, true, true),
                new RecentsLogic.LaunchableApp("com.android.vending", "Google Play Store", 10078, true, true),
                new RecentsLogic.LaunchableApp("io.gh.reisxd.tizentube.cobalt", "TizenTube Cobalt", 10135, false, false),
                new RecentsLogic.LaunchableApp("com.netmirror.tv", "NetMirror", 10140, false, false),
                new RecentsLogic.LaunchableApp("org.xbmc.kodi", "Kodi", 10150, false, false) // evicted, no PID
        );

        RecentsLogic.Snapshot snapshot = RecentsLogic.parseSnapshot(raw);
        assertEquals("io.gh.reisxd.tizentube.cobalt", snapshot.foregroundPackageBeforeRecents);

        List<RecentsLogic.RunningApp> apps = RecentsLogic.buildRunningApps(snapshot, installed);
        assertEquals(3, apps.size());

        // Card 0: most recent foreground app (TizenTube) with main + sandboxed RSS aggregated
        assertEquals("io.gh.reisxd.tizentube.cobalt", apps.get(0).packageName);
        assertEquals(204800L, apps.get(0).rssKb);
        assertEquals("200 MB", RecentsLogic.formatRamCompact(apps.get(0).rssKb));

        // Card 1: NetMirror (Recent #3)
        assertEquals("com.netmirror.tv", apps.get(1).packageName);
        assertEquals("50 MB", RecentsLogic.formatRamCompact(apps.get(1).rssKb));

        // Card 2: Play Store (live via LRU, no Recent header)
        assertEquals("com.android.vending", apps.get(2).packageName);
        assertEquals("80 MB", RecentsLogic.formatRamCompact(apps.get(2).rssKb));
    }

    @Test
    public void parseSnapshot_identifiesHomeForegroundWhenOpenedFromHome() {
        String raw = ""
                + RecentsLogic.SECTION_RECENTS + "\n"
                + "  * Recent #0: Task{111 #2210 type=standard I=com.gathin.tvhome/.RecentsActivity}\n"
                + "  * Recent #1: Task{222 #2154 type=home A=10146:com.gathin.tvhome}\n"
                + "  * Recent #2: Task{333 #2090 type=standard A=10140:com.netmirror.tv}\n";

        RecentsLogic.Snapshot snapshot = RecentsLogic.parseSnapshot(raw);
        assertEquals(RecentsLogic.SELF_PACKAGE, snapshot.foregroundPackageBeforeRecents);
    }

    @Test
    public void formatAndPolicyHelpers_behaveCorrectly() {
        assertEquals("1.1 GB", RecentsLogic.formatRamCompact(1126L * 1024L));
        assertEquals("1 MB", RecentsLogic.formatRamCompact(400L));
        assertEquals(0L, RecentsLogic.kbToMb(0L));

        assertTrue(RecentsLogic.shouldInterceptHomeHold(true, true, 0));
        assertFalse(RecentsLogic.shouldInterceptHomeHold(false, true, 0));
        assertFalse(RecentsLogic.shouldInterceptHomeHold(true, false, 0));
        assertFalse(RecentsLogic.shouldInterceptHomeHold(true, true, 1));

        RecentsLogic.MemSummary before = new RecentsLogic.MemSummary(1800000L, 500000L);
        RecentsLogic.MemSummary after = new RecentsLogic.MemSummary(1800000L, 704800L);
        assertEquals(200L, RecentsLogic.estimateFreedMb(before, after, 100000L));
        assertEquals(150L, RecentsLogic.estimateFreedMb(before, before, 150L * 1024L));
    }
}

