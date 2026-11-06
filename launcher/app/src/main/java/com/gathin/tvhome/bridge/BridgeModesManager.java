package com.gathin.tvhome.bridge;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BridgeModesManager {
    private static final String TAG = "BridgeModesManager";
    private static ClassLoader sMitvClassLoader;

    public static final Map<Integer, String> PICTURE_MODES = new LinkedHashMap<>();
    static {
        PICTURE_MODES.put(0, "Standard");
        PICTURE_MODES.put(3, "Movie");
        PICTURE_MODES.put(5, "Vivid");
        PICTURE_MODES.put(4, "Game");
        PICTURE_MODES.put(6, "Sport");
        PICTURE_MODES.put(21, "Filmmaker");
        PICTURE_MODES.put(2, "Monitor");
        PICTURE_MODES.put(1, "Custom");
    }

    public static final Map<Integer, String> SOUND_MODES = new LinkedHashMap<>();
    static {
        SOUND_MODES.put(0, "Standard");
        SOUND_MODES.put(1, "Movie");
        SOUND_MODES.put(5, "Music");
        SOUND_MODES.put(3, "Game");
        SOUND_MODES.put(2, "News");
        SOUND_MODES.put(6, "Sport");
        SOUND_MODES.put(4, "Custom");
    }

    private static synchronized ClassLoader getMitvClassLoader(Context context) {
        if (sMitvClassLoader == null) {
            try {
                sMitvClassLoader = new dalvik.system.PathClassLoader(
                        "/system/framework/mitvmiddlewareimpl.jar:/vendor/framework/com.mitv.mtvapiclientwrapper.jar",
                        context.getClassLoader()
                );
            } catch (Throwable t) {
                sMitvClassLoader = context.getClassLoader();
            }
        }
        return sMitvClassLoader;
    }

    private static Object getPictureManager(Context context) {
        try {
            Class<?> pmClass = null;
            try {
                pmClass = Class.forName("mitv.display.PictureSettingsManager");
            } catch (Throwable t) {
                try {
                    pmClass = Class.forName("mitv.display.PictureSettingsManager", true, getMitvClassLoader(context));
                } catch (Throwable ignored) {}
            }
            if (pmClass != null) {
                Method getInstance = pmClass.getMethod("getInstance");
                return getInstance.invoke(null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PictureSettingsManager error: " + t.getMessage());
        }
        return null;
    }

    private static Object getSoundManager(Context context) {
        try {
            Class<?> smClass = null;
            try {
                smClass = Class.forName("mitv.sound.SoundManager");
            } catch (Throwable t) {
                try {
                    smClass = Class.forName("mitv.sound.SoundManager", true, getMitvClassLoader(context));
                } catch (Throwable ignored) {}
            }
            if (smClass != null) {
                Method getInstance = smClass.getMethod("getInstance");
                return getInstance.invoke(null);
            }
            try {
                Class<?> implClass = Class.forName("mitv.impl.sound.SoundManagerImpl", true, getMitvClassLoader(context));
                Method getInstance = implClass.getMethod("getInstance");
                return getInstance.invoke(null);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "SoundManager error: " + t.getMessage());
        }
        return null;
    }

    public static int getPictureMode(Context context) {
        int mode = -1;
        try {
            mode = Settings.Global.getInt(context.getContentResolver(), "picture_mode");
        } catch (Throwable ignored) {
            try {
                mode = Settings.System.getInt(context.getContentResolver(), "picture_mode");
            } catch (Throwable ignored2) {}
        }

        if (mode < 0) {
            Object pm = getPictureManager(context);
            if (pm != null) {
                try {
                    Method m = null;
                    try {
                        m = pm.getClass().getMethod("getPictureModeValue");
                    } catch (Throwable t) {
                        try {
                            m = pm.getClass().getMethod("getSceneMode");
                        } catch (Throwable ignored) {}
                    }
                    if (m != null) {
                        Object val = m.invoke(pm);
                        if (val instanceof Number) {
                            mode = ((Number) val).intValue();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "getPictureMode reflection error", t);
                }
            }
        }
        return mode >= 0 ? mode : 0;
    }

    public static boolean setPictureMode(Context context, int modeId) {
        boolean ok = false;
        Object pm = getPictureManager(context);
        if (pm != null) {
            try {
                Method m = null;
                try {
                    m = pm.getClass().getMethod("setPictureModeValue", int.class);
                } catch (NoSuchMethodException e) {
                    try {
                        m = pm.getClass().getMethod("setSceneMode", int.class);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (m != null) {
                    m.invoke(pm, modeId);
                    ok = true;
                    Log.i(TAG, "Picture mode set to " + modeId + " via " + m.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "setPictureMode reflection failed", t);
            }
        }

        try {
            Settings.Global.putInt(context.getContentResolver(), "picture_mode", modeId);
            ok = true;
        } catch (Throwable ignored) {}
        return ok;
    }

    public static int getSoundMode(Context context) {
        int mode = 0;
        Object sm = getSoundManager(context);
        if (sm != null) {
            try {
                Method m = null;
                try {
                    m = sm.getClass().getMethod("getSoundMode");
                } catch (Throwable t) {
                    try {
                        m = sm.getClass().getMethod("getSoundModeHarman");
                    } catch (Throwable ignored) {}
                }
                if (m != null) {
                    Object val = m.invoke(sm);
                    if (val instanceof Number) {
                        mode = ((Number) val).intValue();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "getSoundMode reflection error", t);
            }
        }
        return mode;
    }

    public static boolean setSoundMode(Context context, int modeId) {
        boolean ok = false;
        Object sm = getSoundManager(context);
        if (sm != null) {
            try {
                Method m = null;
                try {
                    m = sm.getClass().getMethod("setSoundMode", int.class);
                } catch (NoSuchMethodException e) {
                    try {
                        m = sm.getClass().getMethod("setSoundModeHarman", int.class);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (m != null) {
                    m.invoke(sm, modeId);
                    ok = true;
                    Log.i(TAG, "Sound mode set to " + modeId + " via " + m.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "setSoundMode reflection failed", t);
            }
        }
        return ok;
    }

    public static JSONObject getDtsStatus(Context context) {
        JSONObject obj = new JSONObject();
        boolean vx = false, bass = false, clarity = false, surround = false;
        Object sm = getSoundManager(context);
        if (sm != null) {
            try {
                Method m = sm.getClass().getMethod("getDtsVirtualX");
                Object val = m.invoke(sm);
                if (val instanceof Number) {
                    int v = ((Number) val).intValue();
                    vx = (v == 1 || v == 4);
                } else if (val instanceof Boolean) {
                    vx = (Boolean) val;
                }
            } catch (Throwable ignored) {}

            try {
                Method m = sm.getClass().getMethod("getDtsBassEnhancement");
                Object val = m.invoke(sm);
                if (val instanceof Number) bass = ((Number) val).intValue() > 0;
                else if (val instanceof Boolean) bass = (Boolean) val;
            } catch (Throwable ignored) {}

            try {
                Method m = sm.getClass().getMethod("getDtsDialogClarity");
                Object val = m.invoke(sm);
                if (val instanceof Number) clarity = ((Number) val).intValue() > 0;
                else if (val instanceof Boolean) clarity = (Boolean) val;
            } catch (Throwable ignored) {}

            try {
                Method m = sm.getClass().getMethod("getDtsVirtualSurround");
                Object val = m.invoke(sm);
                if (val instanceof Number) surround = ((Number) val).intValue() > 0;
                else if (val instanceof Boolean) surround = (Boolean) val;
            } catch (Throwable ignored) {}
        }

        try {
            obj.put("virtualX", vx);
            obj.put("bass", bass);
            obj.put("clarity", clarity);
            obj.put("surround", surround);
        } catch (JSONException ignored) {}
        return obj;
    }

    public static boolean setDts(Context context, String feature, boolean enabled) {
        Object sm = getSoundManager(context);
        if (sm == null) return false;
        String methodName;
        switch (feature) {
            case "virtualX": methodName = "setDtsVirtualX"; break;
            case "bass": methodName = "setDtsBassEnhancement"; break;
            case "clarity": methodName = "setDtsDialogClarity"; break;
            case "surround": methodName = "setDtsVirtualSurround"; break;
            default: return false;
        }

        try {
            Method m = null;
            try {
                m = sm.getClass().getMethod(methodName, boolean.class);
                m.invoke(sm, enabled);
                Log.i(TAG, methodName + "(boolean " + enabled + ") succeeded");
                return true;
            } catch (NoSuchMethodException e) {
                m = sm.getClass().getMethod(methodName, int.class);
                m.invoke(sm, enabled ? 1 : 0);
                Log.i(TAG, methodName + "(int " + (enabled ? 1 : 0) + ") succeeded");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, methodName + " failed", t);
            return false;
        }
    }

    public static JSONObject getAllModesJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            // Picture
            int picId = getPictureMode(context);
            String picName = PICTURE_MODES.containsKey(picId) ? PICTURE_MODES.get(picId) : "Standard (" + picId + ")";
            JSONObject picObj = new JSONObject();
            picObj.put("currentId", picId);
            picObj.put("currentName", picName);
            JSONArray picOpts = new JSONArray();
            for (Map.Entry<Integer, String> entry : PICTURE_MODES.entrySet()) {
                JSONObject opt = new JSONObject();
                opt.put("id", entry.getKey());
                opt.put("name", entry.getValue());
                picOpts.put(opt);
            }
            picObj.put("options", picOpts);
            root.put("picture", picObj);

            // Sound
            int sndId = getSoundMode(context);
            String sndName = SOUND_MODES.containsKey(sndId) ? SOUND_MODES.get(sndId) : "Standard (" + sndId + ")";
            JSONObject sndObj = new JSONObject();
            sndObj.put("currentId", sndId);
            sndObj.put("currentName", sndName);
            JSONArray sndOpts = new JSONArray();
            for (Map.Entry<Integer, String> entry : SOUND_MODES.entrySet()) {
                JSONObject opt = new JSONObject();
                opt.put("id", entry.getKey());
                opt.put("name", entry.getValue());
                sndOpts.put(opt);
            }
            sndObj.put("options", sndOpts);
            root.put("sound", sndObj);

            // DTS
            root.put("dts", getDtsStatus(context));
        } catch (JSONException ignored) {}
        return root;
    }
}

