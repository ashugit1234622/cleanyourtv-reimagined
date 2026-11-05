package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles fetching weather information in the background and displaying it beside the greeting.
 * Supports Weatherstack API with a placeholder, and falls back to Open-Meteo for live preview.
 */
public final class WeatherService {
    private static final String TAG = "TvHome.Weather";

    // Placeholder for Weatherstack API Key (can also be configured in config.json)
    public static final String PLACEHOLDER_API_KEY = "YOUR_WEATHERSTACK_API_KEY_HERE";
    public static final String DEFAULT_LOCATION = "18.748060,73.407219"; // Lonavala

    private static final long CACHE_EXPIRY_MS = 30 * 60 * 1000L; // 30 minutes
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static String cachedWeather = null;
    private static long lastFetchTime = 0;
    private static boolean isFetching = false;

    private WeatherService() {}

    public static void updateWeather(Context context, TextView weatherView) {
        if (weatherView == null) return;

        // Display cached result immediately if available
        if (cachedWeather != null && !cachedWeather.isEmpty()) {
            weatherView.setText(cachedWeather);
            weatherView.setVisibility(View.VISIBLE);
        }

        long now = SystemClock.elapsedRealtime();
        if (isFetching || (cachedWeather != null && (now - lastFetchTime) < CACHE_EXPIRY_MS)) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        isFetching = true;
        executor.execute(() -> {
            String apiKey = getApiKey(appContext);
            String location = getLocation(appContext);

            String result = fetchWeather(apiKey, location);
            isFetching = false;
            if (result != null && !result.isEmpty()) {
                cachedWeather = result;
                lastFetchTime = SystemClock.elapsedRealtime();
                mainHandler.post(() -> {
                    weatherView.setText(result);
                    weatherView.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private static String getApiKey(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            File file = new File(dir, "config.json");
            if (file.exists()) {
                String content = readStream(new FileInputStream(file));
                JSONObject json = new JSONObject(content);
                String key = json.optString("weatherstack_api_key", "").trim();
                if (!key.isEmpty() && !PLACEHOLDER_API_KEY.equals(key)) {
                    return key;
                }
            }
        } catch (Exception ignored) {}
        return PLACEHOLDER_API_KEY;
    }

    private static String getLocation(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            File file = new File(dir, "config.json");
            if (file.exists()) {
                String content = readStream(new FileInputStream(file));
                JSONObject json = new JSONObject(content);
                String loc = json.optString("weather_location", "").trim();
                if (!loc.isEmpty()) return loc;
            }
        } catch (Exception ignored) {}
        return DEFAULT_LOCATION;
    }

    private static String fetchWeather(String apiKey, String location) {
        // If user hasn't supplied their Weatherstack key yet, use Open-Meteo for live real-time Lonavala weather
        if (PLACEHOLDER_API_KEY.equals(apiKey)) {
            return fetchOpenMeteo(location);
        }

        // Weatherstack API request
        try {
            String urlStr = "http://api.weatherstack.com/current?access_key=" + apiKey + "&query=" + location;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200) {
                String jsonStr = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(jsonStr);
                if (json.has("current")) {
                    JSONObject current = json.getJSONObject("current");
                    int temp = current.getInt("temperature");
                    String desc = "";
                    if (current.has("weather_descriptions")) {
                        JSONArray descs = current.getJSONArray("weather_descriptions");
                        if (descs.length() > 0) desc = descs.getString(0);
                    }
                    String locName = "LONAVALA";
                    if (json.has("location")) {
                        JSONObject loc = json.getJSONObject("location");
                        if (loc.has("name")) locName = loc.getString("name").toUpperCase();
                    }
                    return "· " + temp + "°C " + desc.toUpperCase() + " · " + locName;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Weatherstack fetch failed, trying fallback: " + e.getMessage());
        }

        // Fallback to Open-Meteo if Weatherstack call failed or quota was reached
        return fetchOpenMeteo(location);
    }

    private static String fetchOpenMeteo(String location) {
        try {
            String[] parts = location.split(",");
            String lat = parts.length > 0 ? parts[0].trim() : "18.748060";
            String lon = parts.length > 1 ? parts[1].trim() : "73.407219";

            String urlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current_weather=true";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200) {
                String jsonStr = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(jsonStr);
                if (json.has("current_weather")) {
                    JSONObject cw = json.getJSONObject("current_weather");
                    double temp = cw.getDouble("temperature");
                    int wCode = cw.optInt("weathercode", 0);
                    String desc = weatherCodeToDescription(wCode);
                    return "· " + Math.round(temp) + "°C " + desc + " · LONAVALA";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Open-Meteo fetch failed: " + e.getMessage());
        }
        return null;
    }

    private static String weatherCodeToDescription(int code) {
        switch (code) {
            case 0: return "SUNNY";
            case 1: return "MAINLY CLEAR";
            case 2: return "PARTLY CLOUDY";
            case 3: return "OVERCAST";
            case 45:
            case 48: return "FOG";
            case 51:
            case 53:
            case 55: return "DRIZZLE";
            case 61:
            case 63:
            case 65: return "RAIN";
            case 80:
            case 81:
            case 82: return "SHOWERS";
            case 95:
            case 96:
            case 99: return "THUNDERSTORM";
            default: return "CLEAR";
        }
    }

    private static String readStream(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }
}
