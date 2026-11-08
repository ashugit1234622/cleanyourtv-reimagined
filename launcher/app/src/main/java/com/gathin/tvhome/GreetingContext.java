package com.gathin.tvhome;

import java.time.LocalDateTime;

/**
 * Context parameters provided to GreetingEngine for evaluating and selecting an intro greeting.
 * Fully decoupled from UI and Android Context for deterministic JVM unit testing.
 */
public final class GreetingContext {
    public final String trigger;          // "boot", "wake", "screensaver"
    public final LocalDateTime now;       // Current local date & time
    public final long gapMinutes;         // Minutes since screen went off / idle
    public final long screensaverMinutes; // Duration of screensaver if wake was from dream
    public final boolean bridgeOn;        // Whether phone bridge is currently active
    public final String bootClass;        // "overnight_morning" or "other"
    public final String forcedTier;       // Debug hook: "none", "A", "R", "S", or null

    public GreetingContext(String trigger, LocalDateTime now, long gapMinutes,
                           long screensaverMinutes, boolean bridgeOn, String bootClass,
                           String forcedTier) {
        this.trigger = trigger != null ? trigger.trim().toLowerCase() : "boot";
        this.now = now != null ? now : LocalDateTime.now();
        this.gapMinutes = gapMinutes;
        this.screensaverMinutes = screensaverMinutes;
        this.bridgeOn = bridgeOn;
        this.bootClass = bootClass != null ? bootClass.trim().toLowerCase() : "other";
        this.forcedTier = forcedTier != null ? forcedTier.trim() : null;
    }
}

