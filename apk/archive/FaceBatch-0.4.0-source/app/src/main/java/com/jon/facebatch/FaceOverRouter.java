package com.jon.facebatch;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Reproduces the lightweight, no-user-account login performed by Face Over at startup.
 * The service returns a backend routing list for single-face swaps.
 */
public final class FaceOverRouter {
    private static final String LOGIN_URL =
            "https://faceover.tech/api/faceover2/users/android/v1/login.php";

    // SHA-256 of the original Face Over signing certificate. The modded APK deliberately
    // restores this certificate identity before the app computes its startup key.
    private static final String ORIGINAL_APP_KEY =
            "34673AF0DEDD5664B1D225E03696191C89CD4DABCBE730278E42D4A1250C230D";

    private static volatile long cachedAt;
    private static volatile int cachedRoute = Integer.MIN_VALUE;

    private FaceOverRouter() {
    }

    public static void resetCache() {
        synchronized (FaceOverRouter.class) {
            cachedRoute = Integer.MIN_VALUE;
            cachedAt = 0L;
        }
    }

    public static int singleRoute(Context context) throws Exception {
        long now = System.currentTimeMillis();
        if (cachedRoute != Integer.MIN_VALUE && now - cachedAt < 10L * 60L * 1000L) {
            return cachedRoute;
        }
        synchronized (FaceOverRouter.class) {
            now = System.currentTimeMillis();
            if (cachedRoute != Integer.MIN_VALUE && now - cachedAt < 10L * 60L * 1000L) {
                return cachedRoute;
            }
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) {
                androidId = "facebatch";
            }
            String form = "key=" + enc(ORIGINAL_APP_KEY)
                    + "&device_id=" + enc(androidId);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(LOGIN_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] body = form.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                    out.flush();
                } finally {
                    out.close();
                }
                int status = connection.getResponseCode();
                InputStream in = status >= 200 && status < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(in);
                if (status < 200 || status >= 300) {
                    throw new ApiClient.ApiException("Face Over routing login returned HTTP "
                            + status + ": " + compact(response), status);
                }
                Object parsed = new JSONTokener(response).nextValue();
                if (!(parsed instanceof JSONObject)) {
                    throw new ApiClient.ApiException("Face Over routing login returned unexpected data.");
                }
                JSONObject root = (JSONObject) parsed;
                JSONArray list = root.optJSONArray("single_type");
                if (list == null) list = root.optJSONArray("singleType");
                if (list == null) {
                    JSONObject data = root.optJSONObject("data");
                    if (data != null) {
                        list = data.optJSONArray("single_type");
                        if (list == null) list = data.optJSONArray("singleType");
                    }
                }
                if (list == null || list.length() == 0) {
                    throw new ApiClient.ApiException("Face Over did not return a single-face backend route. "
                            + compact(response));
                }
                int route = parseRoute(list.opt(0));
                cachedRoute = route;
                cachedAt = System.currentTimeMillis();
                return route;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    public static String routeLabel(int route) {
        if (route == 0) return "Deepfake / AIFaceSwap";
        if (route == 3) return "TaoAnhDep";
        if (route == 2) return "FJoy / Magicut";
        return "FJoy / Magicut";
    }

    private static int parseRoute(Object value) throws ApiClient.ApiException {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            throw new ApiClient.ApiException("Face Over returned an unreadable backend route: " + value);
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static String compact(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 500 ? value.substring(0, 500) + "…" : value;
    }
}
