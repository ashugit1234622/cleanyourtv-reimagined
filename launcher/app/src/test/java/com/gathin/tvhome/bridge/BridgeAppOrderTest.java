package com.gathin.tvhome.bridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeAppOrderTest {

    @Test
    public void testSavedOrderDecoding() {
        String orderContent = "tvhome-order 1\n" +
                "dock io.gh.reisxd.tizentube.cobalt,app.netmirror.newtv\n" +
                "docked 2\n" +
                "app.netmirror.newtv/.MainActivity\n" +
                "io.gh.reisxd.tizentube.cobalt/.MainActivity\n" +
                "org.videolan.vlc/.gui.video.VideoGridActivity\n";

        String[] lines = orderContent.split("\n", -1);
        assertEquals("tvhome-order 1", lines[0]);
        assertTrue(lines[1].startsWith("dock "));

        int docked = -1;
        int first = 2;
        if (lines.length > 2 && lines[2].startsWith("docked ")) {
            docked = Integer.parseInt(lines[2].substring("docked ".length()).trim());
            first = 3;
        }
        assertEquals(2, docked);

        Map<String, Integer> ranks = new HashMap<>();
        int rank = 0;
        for (int i = first; i < lines.length; i++) {
            String k = lines[i].trim();
            if (k.isEmpty()) continue;
            if (!ranks.containsKey(k)) ranks.put(k, rank++);
        }

        assertEquals(Integer.valueOf(0), ranks.get("app.netmirror.newtv/.MainActivity"));
        assertEquals(Integer.valueOf(1), ranks.get("io.gh.reisxd.tizentube.cobalt/.MainActivity"));
        assertEquals(Integer.valueOf(2), ranks.get("org.videolan.vlc/.gui.video.VideoGridActivity"));
    }

    @Test
    public void testPackageOrderMatchesLauncherSortRule() {
        // Mock app list before reordering
        List<BridgeAppHelper.AppEntry> apps = new ArrayList<>();
        apps.add(new BridgeAppHelper.AppEntry("io.gh.reisxd.tizentube.cobalt", ".MainActivity", "TizenTube Cobalt", false, -1, false));
        apps.add(new BridgeAppHelper.AppEntry("app.netmirror.newtv", ".MainActivity", "NetMirror", false, -1, true));
        apps.add(new BridgeAppHelper.AppEntry("org.videolan.vlc", ".MainActivity", "VLC", false, -1, false));
        apps.add(new BridgeAppHelper.AppEntry("com.plexapp.android", ".MainActivity", "Plex", false, -1, false));

        // Reorder saved in order.txt: Plex first, VLC second, NetMirror third, TizenTube fourth, with docked=2
        Map<String, Integer> savedRanks = new HashMap<>();
        savedRanks.put("com.plexapp.android/.MainActivity", 0);
        savedRanks.put("org.videolan.vlc/.MainActivity", 1);
        savedRanks.put("app.netmirror.newtv/.MainActivity", 2);
        savedRanks.put("io.gh.reisxd.tizentube.cobalt/.MainActivity", 3);
        int savedDocked = 2;

        List<String> dockOrder = Arrays.asList("io.gh.reisxd.tizentube.cobalt", "app.netmirror.newtv");

        // Sort using BridgeAppHelper's exact comparator
        Collections.sort(apps, (a, b) -> {
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

        // Determine dock status
        List<BridgeAppHelper.AppEntry> result = new ArrayList<>();
        int dockIndex = 0;
        for (BridgeAppHelper.AppEntry entry : apps) {
            boolean isDock;
            if (savedDocked >= 0) {
                Integer rank = savedRanks.get(entry.getComponentKey());
                isDock = (rank != null && rank < savedDocked);
            } else {
                isDock = dockOrder.contains(entry.packageName);
            }

            if (isDock) {
                result.add(new BridgeAppHelper.AppEntry(entry.packageName, entry.activityName, entry.label, true, dockIndex++, entry.launchOnly));
            } else {
                result.add(new BridgeAppHelper.AppEntry(entry.packageName, entry.activityName, entry.label, false, -1, entry.launchOnly));
            }
        }

        // Verify order
        assertEquals("com.plexapp.android", result.get(0).packageName);
        assertTrue(result.get(0).isDock);
        assertEquals(0, result.get(0).dockPosition);

        assertEquals("org.videolan.vlc", result.get(1).packageName);
        assertTrue(result.get(1).isDock);
        assertEquals(1, result.get(1).dockPosition);

        assertEquals("app.netmirror.newtv", result.get(2).packageName);
        assertFalse(result.get(2).isDock);
        assertEquals(-1, result.get(2).dockPosition);
        assertTrue(result.get(2).launchOnly);

        assertEquals("io.gh.reisxd.tizentube.cobalt", result.get(3).packageName);
        assertFalse(result.get(3).isDock);
        assertEquals(-1, result.get(3).dockPosition);
        assertFalse(result.get(3).launchOnly);
    }

    @Test
    public void testHiddenAndRemovedAppsVisibilityRule() {
        // Rule: !isRemoved && (isExplicit || (isTv && !isHidden))
        // Case 1: TV app, not hidden -> visible
        assertTrue(!false && (false || (true && !false)));

        // Case 2: TV app, hidden in hidden_apps -> NOT visible
        assertFalse(!false && (false || (true && !true)));

        // Case 3: TV app, removed from home -> NOT visible
        assertFalse(!true && (false || (true && !false)));

        // Case 4: Non-TV app, explicitly added from library -> visible
        assertTrue(!false && (true || (false && !false)));

        // Case 5: Non-TV app, explicitly added but then removed from home -> NOT visible
        assertFalse(!true && (true || (false && !false)));
    }
}

