package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

/**
 * Preference helper for toggling the long-press Home Recent Apps menu in Launcher Settings.
 */
public final class RecentsPreferences {

    public static final String KEY_LONG_PRESS_HOME_RECENTS = "long_press_home_recents";

    private RecentsPreferences() {}

    public static boolean isEnabled(Context ctx) {
        if (ctx == null) return true;
        UserManager users = ctx.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) return false;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_LONG_PRESS_HOME_RECENTS, true);
        } catch (IllegalStateException ignored) {
            return true;
        }
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LONG_PRESS_HOME_RECENTS, enabled)
                .apply();
    }

    public static boolean hasCustomChoice(SharedPreferences prefs) {
        return prefs != null && prefs.contains(KEY_LONG_PRESS_HOME_RECENTS);
    }

    public static void clearChoice(SharedPreferences.Editor editor) {
        if (editor != null) editor.remove(KEY_LONG_PRESS_HOME_RECENTS);
    }
}

