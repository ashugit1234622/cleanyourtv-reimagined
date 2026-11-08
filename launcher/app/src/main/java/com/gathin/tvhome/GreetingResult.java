package com.gathin.tvhome;

/**
 * Result returned by GreetingEngine after selecting and interpolating a greeting.
 */
public final class GreetingResult {
    public final String id;      // Selected message ID (e.g. "m001")
    public final String text;    // Final display text (interpolated, capitalized, clean)
    public final String tier;    // "R", "A", "S", or "N"
    public final boolean isNamed;// Whether a name was included
    public final String pool;    // "birthday", "festival", "rare", "tip", "normal"

    public GreetingResult(String id, String text, String tier, boolean isNamed, String pool) {
        this.id = id != null ? id : "";
        this.text = text != null ? text : "";
        this.tier = tier != null ? tier : "N";
        this.isNamed = isNamed;
        this.pool = pool != null ? pool : "normal";
    }

    @Override
    public String toString() {
        return "[" + id + "] tier=" + tier + " named=" + isNamed + " pool=" + pool + " text=\"" + text + "\"";
    }
}

