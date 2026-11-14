package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.*;

public class NowPlayingModelTest {

    @Test
    public void testFormattedSubtitleWithArtistAndApp() {
        NowPlayingModel model = new NowPlayingModel(
                "Blinding Lights",
                "The Weeknd",
                "com.spotify.tv.android",
                "Spotify",
                null,
                null,
                true,
                200000L,
                45000L
        );

        assertEquals("Blinding Lights", model.getTitle());
        assertEquals("The Weeknd", model.getArtist());
        assertEquals("Spotify", model.getAppName());
        assertEquals("The Weeknd • Spotify", model.getFormattedSubtitle());
        assertTrue(model.isPlaying());
    }

    @Test
    public void testFormattedSubtitleWithEmptyArtist() {
        NowPlayingModel model = new NowPlayingModel(
                "Lofi Hip Hop Radio",
                "",
                "com.teamsmart.videomanager.tv",
                "SmartTube",
                null,
                null,
                true,
                0L,
                0L
        );

        assertEquals("SmartTube", model.getFormattedSubtitle());
    }

    @Test
    public void testFormattedSubtitleWithEmptyApp() {
        NowPlayingModel model = new NowPlayingModel(
                "Audio Track",
                "Unknown Artist",
                "com.example.player",
                "",
                null,
                null,
                false,
                0L,
                0L
        );

        assertEquals("Unknown Artist", model.getFormattedSubtitle());
    }

    @Test
    public void testArtUriAndWithArtBitmap() {
        NowPlayingModel model = new NowPlayingModel(
                "Track",
                "Artist",
                "org.smarttube.stable",
                "SmartTube",
                null,
                null,
                "https://i.ytimg.com/vi/123/hq720.jpg",
                true,
                1000L,
                200L
        );

        assertEquals("https://i.ytimg.com/vi/123/hq720.jpg", model.getArtUri());
        assertNull(model.getArtBitmap());

        NowPlayingModel updated = model.withArtBitmap(null);
        assertEquals(model.getArtUri(), updated.getArtUri());
        assertEquals(model.getTitle(), updated.getTitle());
    }
}
