package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.*;

public class NowPlayingControllerTest {

    @Test
    public void testResolveFriendlyAppName() {
        assertEquals("SmartTube", NowPlayingController.resolveFriendlyAppName(null, "com.teamsmart.videomanager.tv"));
        assertEquals("SmartTube", NowPlayingController.resolveFriendlyAppName(null, "com.liskovsoft.smarttubetv.beta"));
        assertEquals("Spotify", NowPlayingController.resolveFriendlyAppName(null, "com.spotify.tv.android"));
        assertEquals("YouTube", NowPlayingController.resolveFriendlyAppName(null, "com.google.android.youtube.tv"));
        assertEquals("TizenTube", NowPlayingController.resolveFriendlyAppName(null, "io.gh.reisxd.tizentube.cobalt"));
        assertEquals("YouTube Music", NowPlayingController.resolveFriendlyAppName(null, "com.google.android.youtube.tvmusic"));
        assertEquals("JioHotstar", NowPlayingController.resolveFriendlyAppName(null, "in.startv.hotstar"));
        assertEquals("Netflix", NowPlayingController.resolveFriendlyAppName(null, "com.netflix.ninja"));
    }

    @Test
    public void testListenerRegistration() {
        NowPlayingController controller = NowPlayingController.getInstance();
        final boolean[] called = {false};
        NowPlayingController.Listener listener = model -> called[0] = true;

        controller.addListener(listener);
        assertTrue("Listener should be called immediately on registration", called[0]);

        controller.removeListener(listener);
    }
}
