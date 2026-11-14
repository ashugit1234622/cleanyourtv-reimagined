package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import java.util.Objects;

/**
 * Immutable model holding current media playback metadata, state, and artwork.
 */
public final class NowPlayingModel {
    private final String title;
    private final String artist;
    private final String packageName;
    private final String appName;
    private final Drawable appIcon;
    private final Bitmap artBitmap;
    private final String artUri;
    private final boolean isPlaying;
    private final long durationMs;
    private final long positionMs;

    public NowPlayingModel(String title,
                           String artist,
                           String packageName,
                           String appName,
                           Drawable appIcon,
                           Bitmap artBitmap,
                           boolean isPlaying,
                           long durationMs,
                           long positionMs) {
        this(title, artist, packageName, appName, appIcon, artBitmap, null, isPlaying, durationMs, positionMs);
    }

    public NowPlayingModel(String title,
                           String artist,
                           String packageName,
                           String appName,
                           Drawable appIcon,
                           Bitmap artBitmap,
                           String artUri,
                           boolean isPlaying,
                           long durationMs,
                           long positionMs) {
        this.title = title != null ? title.trim() : "";
        this.artist = artist != null ? artist.trim() : "";
        this.packageName = packageName != null ? packageName.trim() : "";
        this.appName = appName != null ? appName.trim() : "";
        this.appIcon = appIcon;
        this.artBitmap = artBitmap;
        this.artUri = artUri != null ? artUri.trim() : "";
        this.isPlaying = isPlaying;
        this.durationMs = durationMs;
        this.positionMs = positionMs;
    }

    public NowPlayingModel withArtBitmap(Bitmap bitmap) {
        return new NowPlayingModel(title, artist, packageName, appName, appIcon, bitmap, artUri, isPlaying, durationMs, positionMs);
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public Bitmap getArtBitmap() {
        return artBitmap;
    }

    public String getArtUri() {
        return artUri;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public String getFormattedSubtitle() {
        if (!artist.isEmpty() && !appName.isEmpty()) {
            return artist + " \u2022 " + appName;
        } else if (!artist.isEmpty()) {
            return artist;
        } else {
            return appName;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NowPlayingModel that = (NowPlayingModel) o;
        return isPlaying == that.isPlaying &&
                durationMs == that.durationMs &&
                positionMs == that.positionMs &&
                Objects.equals(title, that.title) &&
                Objects.equals(artist, that.artist) &&
                Objects.equals(packageName, that.packageName) &&
                Objects.equals(appName, that.appName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, artist, packageName, appName, isPlaying, durationMs, positionMs);
    }

    @Override
    public String toString() {
        return "NowPlayingModel{" +
                "title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", packageName='" + packageName + '\'' +
                ", appName='" + appName + '\'' +
                ", isPlaying=" + isPlaying +
                '}';
    }
}
