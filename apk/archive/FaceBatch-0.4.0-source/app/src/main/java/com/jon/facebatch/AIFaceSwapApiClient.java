package com.jon.facebatch;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reproduces the AIFaceSwap single-photo workflow used by Face Over's route 0.
 * No user API key or account is used. The website supplies a short-lived theme
 * version, cookies, and presigned upload URLs at runtime.
 */
public final class AIFaceSwapApiClient {
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String BASE = "https://aifaceswap.io/";
    private static final String UPLOAD_URL = BASE + "api/upload_file";
    private static final String GENERATE_URL = BASE + "api/generate_face_v1";
    private static final String STATUS_URL = BASE + "api/check_status";
    private static final String RESULT_BASE = "https://art-global.faceai.art/";

    private static final String APP_ID = "aifaceswap_v1";
    private static final String SECRET = "1H5tRtzsBkqXcaJ";
    private static final String PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCwlO+boC6cwRo3UfXVBadaYwcX"
                    + "0zKS2fuVNY2qZ0dgwb1NJ+/Q9FeAosL4ONiosD71on3PVYqRUlL5045mvH2K9i8b"
                    + "AFVMEip7E6RMK6tKAAif7xzZrXnP1GZ5Rijtqdgwh+YmzTo39cuBCsZqK9oEoeQ3"
                    + "r/myG9S+9cR5huTuFQIDAQAB";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    private static final int BUFFER = 64 * 1024;
    private static volatile WebSession cachedSession;

    private AIFaceSwapApiClient() {
    }

    public static void resetSession() {
        synchronized (LOCK) {
            cachedSession = null;
        }
    }

    public static File execute(Context context, SwapJob job, File workDirectory,
                               ApiClient.Cancellation cancellation,
                               ApiClient.ConnectionMonitor monitor) throws Exception {
        synchronized (LOCK) {
            if (!workDirectory.exists() && !workDirectory.mkdirs()) {
                throw new ApiClient.ApiException("Could not create the temporary working folder.");
            }
            ApiClient.ApiException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                checkCancelled(cancellation);
                if (attempt > 0) {
                    cachedSession = null;
                    sleep(900L, cancellation);
                }
                try {
                    WebSession session = getSession(attempt > 0, cancellation, monitor);
                    return executeOnce(context, job, session, workDirectory, cancellation, monitor);
                } catch (ApiClient.ApiException e) {
                    last = e;
                    if (attempt > 0 || !shouldRefreshSession(e)) {
                        throw e;
                    }
                }
            }
            throw last == null
                    ? new ApiClient.ApiException("AIFaceSwap failed without a server response.")
                    : last;
        }
    }

    private static File executeOnce(Context context, SwapJob job, WebSession session,
                                    File workDirectory, ApiClient.Cancellation cancellation,
                                    ApiClient.ConnectionMonitor monitor) throws Exception {
        File target = copyUriToTemp(context, Uri.parse(job.targetUri), workDirectory,
                "hq_target_", cancellation);
        File donor = copyUriToTemp(context, Uri.parse(job.sourceUri), workDirectory,
                "hq_face_", cancellation);
        try {
            String targetUpload = requestUploadUrl(session, cancellation, monitor);
            uploadImage(targetUpload, target, session, cancellation, monitor);
            String targetPath = extractRelativePath(targetUpload);

            String donorUpload = requestUploadUrl(session, cancellation, monitor);
            uploadImage(donorUpload, donor, session, cancellation, monitor);
            String donorPath = extractRelativePath(donorUpload);

            String taskId = submitGeneration(targetPath, donorPath, session,
                    cancellation, monitor);
            return pollResult(taskId, md5Hex(baseName(targetPath) + ":" + baseName(donorPath)),
                    session, workDirectory, cancellation, monitor);
        } finally {
            target.delete();
            donor.delete();
        }
    }

    private static WebSession getSession(boolean force, ApiClient.Cancellation cancellation,
                                         ApiClient.ConnectionMonitor monitor) throws Exception {
        long now = System.currentTimeMillis();
        WebSession existing = cachedSession;
        if (!force && existing != null && now - existing.createdAt < 8L * 60L * 1000L) {
            return existing;
        }
        HttpURLConnection connection = null;
        try {
            connection = open(BASE, "GET", 25_000, 120_000, monitor);
            browserHeaders(connection);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            int status = connection.getResponseCode();
            String html = readResponseText(connection, status, 4 * 1024 * 1024);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException("AIFaceSwap setup returned HTTP " + status
                        + (html.isEmpty() ? "" : ": " + compact(html)), status);
            }
            checkCancelled(cancellation);
            String theme = parseThemeVersion(html);
            String cookies = collectCookies(connection);
            if (theme.isEmpty()) {
                throw new ApiClient.ApiException("AIFaceSwap setup did not expose a theme version. "
                        + "The website may have changed. Response: " + compact(html));
            }
            // Jsoup in the original app permits an empty cookie map. Some deployments do
            // not issue a cookie on the first page load, so do not fail solely for that reason.
            WebSession session = new WebSession(theme, cookies, System.currentTimeMillis());
            cachedSession = session;
            return session;
        } finally {
            close(connection, monitor);
        }
    }

    private static String requestUploadUrl(WebSession session,
                                           ApiClient.Cancellation cancellation,
                                           ApiClient.ConnectionMonitor monitor) throws Exception {
        JSONObject body = new JSONObject();
        body.put("file_name", randomHex(32) + ".jpg");
        body.put("type", "image");
        JSONObject response = postJson(UPLOAD_URL, body.toString(), basicHeaders(session),
                cancellation, monitor, "AIFaceSwap upload-URL request");
        int code = response.optInt("code", 0);
        JSONObject data = response.optJSONObject("data");
        String url = data == null ? "" : string(data.opt("url"));
        if ((code != 0 && code != 200) || url.isEmpty()) {
            throw apiError("AIFaceSwap did not provide an upload URL", response, code);
        }
        return url;
    }

    private static void uploadImage(String signedUrl, File file, WebSession session,
                                    ApiClient.Cancellation cancellation,
                                    ApiClient.ConnectionMonitor monitor) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(signedUrl, "PUT", 25_000, 150_000, monitor);
            browserHeaders(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("theme-version", session.themeVersion);
            connection.setRequestProperty("Cookie", session.cookies);
            connection.setRequestProperty("x-code", String.valueOf(System.currentTimeMillis()));
            connection.setRequestProperty("Content-Type", "image/jpg");
            connection.setRequestProperty("x-oss-storage-class", "Standard");
            connection.setFixedLengthStreamingMode(file.length());
            OutputStream out = new BufferedOutputStream(connection.getOutputStream(), BUFFER);
            InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER);
            try {
                copy(in, out, cancellation);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            int status = connection.getResponseCode();
            String text = readResponseText(connection, status, 1024 * 1024);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException("AIFaceSwap image upload returned HTTP " + status
                        + (text.isEmpty() ? "" : ": " + compact(text)), status);
            }
        } finally {
            close(connection, monitor);
        }
    }

    private static String submitGeneration(String targetPath, String donorPath,
                                           WebSession session,
                                           ApiClient.Cancellation cancellation,
                                           ApiClient.ConnectionMonitor monitor) throws Exception {
        Signature signature = createSignature();
        String fp = fakeFingerprint();
        String fp1 = aesCbc(APP_ID + ":" + fp, signature.aesSecret);
        String nonce = md5Hex(baseName(targetPath) + ":" + baseName(donorPath));

        JSONObject plain = new JSONObject();
        plain.put("source_image", targetPath);
        plain.put("face_image", donorPath);
        plain.put("type_1", 0);
        plain.put("type_2", 0);

        JSONObject request = new JSONObject();
        request.put("request_type", 0);
        request.put("data", encryptGenerationJson(plain.toString(), session.themeVersion));

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.putAll(basicHeaders(session));
        headers.put("x-guide", signature.xGuide);
        headers.put("x-sign", signature.xSign);
        headers.put("nonce", nonce);
        headers.put("fp", fp);
        headers.put("fp1", fp1);

        JSONObject response = postJson(GENERATE_URL, request.toString(), headers,
                cancellation, monitor, "AIFaceSwap generation request");
        int code = response.optInt("code", 0);
        JSONObject data = response.optJSONObject("data");
        String taskId = data == null ? "" : string(data.opt("task_id"));
        if ((code != 0 && code != 200) || taskId.isEmpty()) {
            throw apiError("AIFaceSwap rejected the generation request", response, code);
        }
        return taskId;
    }

    private static File pollResult(String taskId, String nonce, WebSession session,
                                   File workDirectory, ApiClient.Cancellation cancellation,
                                   ApiClient.ConnectionMonitor monitor) throws Exception {
        for (int attempt = 0; attempt < 90; attempt++) {
            sleep(2000L, cancellation);
            JSONObject request = new JSONObject();
            request.put("task_id", taskId);
            request.put("nonce", nonce);
            JSONObject response = postJson(STATUS_URL, request.toString(), basicHeaders(session),
                    cancellation, monitor, "AIFaceSwap status request");
            int code = response.optInt("code", 0);
            JSONObject data = response.optJSONObject("data");
            String result = data == null ? "" : string(data.opt("result_image"));
            if (code == 200 && !result.isEmpty()) {
                return download(resolveResultUrl(result), workDirectory, cancellation, monitor);
            }
            if (isTerminalFailure(response, code)) {
                throw apiError("AIFaceSwap generation failed", response, code);
            }
        }
        throw new ApiClient.ApiException("AIFaceSwap did not finish within three minutes.");
    }

    private static JSONObject postJson(String url, String json, Map<String, String> headers,
                                       ApiClient.Cancellation cancellation,
                                       ApiClient.ConnectionMonitor monitor,
                                       String stage) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", 25_000, 150_000, monitor);
            browserHeaders(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() != null) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
            checkCancelled(cancellation);
            int status = connection.getResponseCode();
            String text = readResponseText(connection, status, 4 * 1024 * 1024);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException(stage + " returned HTTP " + status
                        + (text.isEmpty() ? "" : ": " + compact(text)), status);
            }
            Object parsed;
            try {
                parsed = new JSONTokener(text).nextValue();
            } catch (Exception e) {
                throw new ApiClient.ApiException(stage + " returned non-JSON data: " + compact(text));
            }
            if (!(parsed instanceof JSONObject)) {
                throw new ApiClient.ApiException(stage + " returned unexpected data: " + compact(text));
            }
            return (JSONObject) parsed;
        } finally {
            close(connection, monitor);
        }
    }

    private static LinkedHashMap<String, String> basicHeaders(WebSession session) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("theme-version", session.themeVersion);
        headers.put("Cookie", session.cookies);
        headers.put("x-code", String.valueOf(System.currentTimeMillis()));
        return headers;
    }

    private static Signature createSignature() throws Exception {
        String aesSecret = randomAlphaNumeric(16);
        String xGuide = rsaEncrypt(aesSecret.getBytes(StandardCharsets.UTF_8));
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String signatureNonce = UUID.randomUUID().toString();
        String signingPlaintext = APP_ID + ":" + SECRET + ":" + timestamp + ":"
                + signatureNonce + ":" + xGuide;
        String xSign = aesCbc(signingPlaintext, aesSecret);
        return new Signature(aesSecret, xGuide, xSign);
    }

    private static String encryptGenerationJson(String json, String themeVersion) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(themeVersion.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
        return Base64.encodeToString(joined, Base64.NO_WRAP);
    }

    private static String aesCbc(String plain, String keyAndIv) throws Exception {
        byte[] bytes = keyAndIv.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(bytes, "AES"),
                new IvParameterSpec(bytes));
        return Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);
    }

    private static String rsaEncrypt(byte[] data) throws Exception {
        byte[] keyBytes = Base64.decode(PUBLIC_KEY, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP);
    }

    private static String fakeFingerprint() throws Exception {
        String raw = Build.BRAND + Build.MODEL + Build.VERSION.SDK_INT
                + Build.FINGERPRINT + UUID.randomUUID().toString();
        return md5Hex(raw);
    }

    private static String parseThemeVersion(String html) {
        if (html == null) return "";
        String[] patterns = new String[]{
                "(?is)<[^>]*id\\s*=\\s*['\"]theme-version['\"][^>]*data-kt-theme-version\\s*=\\s*['\"]([^'\"]+)['\"]",
                "(?is)<[^>]*data-kt-theme-version\\s*=\\s*['\"]([^'\"]+)['\"][^>]*id\\s*=\\s*['\"]theme-version['\"]",
                "(?is)data-kt-theme-version\\s*=\\s*['\"]([^'\"]+)['\"]",
                "(?is)theme-version\\s*[:=]\\s*['\"]([^'\"]+)['\"]"
        };
        for (String regex : patterns) {
            Matcher matcher = Pattern.compile(regex).matcher(html);
            if (matcher.find()) return htmlDecode(matcher.group(1).trim());
        }
        return "";
    }

    private static String collectCookies(HttpURLConnection connection) {
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return "";
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            List<String> headers = entry.getValue();
            if (headers == null) continue;
            for (String header : headers) {
                if (header == null) continue;
                int semicolon = header.indexOf(';');
                String pair = (semicolon >= 0 ? header.substring(0, semicolon) : header).trim();
                int equals = pair.indexOf('=');
                if (equals > 0) values.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (joined.length() > 0) joined.append("; ");
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return joined.toString();
    }

    private static String extractRelativePath(String uploadUrl) throws Exception {
        Matcher matcher = Pattern.compile("(aifaceswap/upload_res/[^?]+)").matcher(uploadUrl);
        if (matcher.find()) return matcher.group(1);
        URL url = new URL(uploadUrl);
        String path = url.getPath();
        while (path.startsWith("/")) path = path.substring(1);
        if (!path.isEmpty()) return path;
        throw new ApiClient.ApiException("AIFaceSwap returned an unreadable upload URL.");
    }

    private static String resolveResultUrl(String result) throws Exception {
        String value = result.trim();
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://")) {
            throw new ApiClient.ApiException("AIFaceSwap returned an insecure result URL.");
        }
        if (value.startsWith("//")) return "https:" + value;
        while (value.startsWith("/")) value = value.substring(1);
        return new URL(new URL(RESULT_BASE), value).toString();
    }

    private static File download(String url, File workDirectory,
                                 ApiClient.Cancellation cancellation,
                                 ApiClient.ConnectionMonitor monitor) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(url, "GET", 25_000, 150_000, monitor);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String body = readResponseText(connection, status, 1024 * 1024);
                throw new ApiClient.ApiException("AIFaceSwap result download returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + compact(body)), status);
            }
            File file = File.createTempFile("aifaceswap_result_", ".bin", workDirectory);
            InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(new FileOutputStream(file), BUFFER);
            try {
                copy(in, out, cancellation);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            if (!ApiClient.looksLikeImage(file)) {
                file.delete();
                throw new ApiClient.ApiException("AIFaceSwap returned a result URL that was not an image.");
            }
            return file;
        } finally {
            close(connection, monitor);
        }
    }

    private static File copyUriToTemp(Context context, Uri uri, File dir, String prefix,
                                      ApiClient.Cancellation cancellation) throws Exception {
        File file = File.createTempFile(prefix, ".bin", dir);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            file.delete();
            throw new ApiClient.ApiException("Could not open a selected image.");
        }
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file), BUFFER);
        try {
            copy(new BufferedInputStream(in, BUFFER), out, cancellation);
            out.flush();
        } finally {
            in.close();
            out.close();
        }
        return file;
    }

    private static HttpURLConnection open(String url, String method, int connectTimeout,
                                          int readTimeout, ApiClient.ConnectionMonitor monitor)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Connection", "close");
        if (monitor != null) monitor.opened(connection);
        return connection;
    }

    private static void browserHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", BASE);
        connection.setRequestProperty("Origin", "https://aifaceswap.io");
        connection.setRequestProperty("Accept", "*/*");
    }

    private static String readResponseText(HttpURLConnection connection, int status, int maxBytes)
            throws Exception {
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1 && out.size() < maxBytes) {
                out.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void close(HttpURLConnection connection, ApiClient.ConnectionMonitor monitor) {
        if (connection == null) return;
        if (monitor != null) monitor.closed(connection);
        connection.disconnect();
    }

    private static void copy(InputStream in, OutputStream out,
                             ApiClient.Cancellation cancellation) throws Exception {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) != -1) {
            checkCancelled(cancellation);
            out.write(buffer, 0, read);
        }
    }

    private static void checkCancelled(ApiClient.Cancellation cancellation)
            throws ApiClient.ApiException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ApiClient.ApiException("Batch cancelled.");
        }
    }

    private static void sleep(long millis, ApiClient.Cancellation cancellation) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            checkCancelled(cancellation);
            Thread.sleep(Math.min(250L, Math.max(1L, end - System.currentTimeMillis())));
        }
    }

    private static boolean shouldRefreshSession(ApiClient.ApiException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
        return e.httpStatus == 401 || e.httpStatus == 403 || e.httpStatus == 419
                || message.contains("theme") || message.contains("cookie")
                || message.contains("signature") || message.contains("x-sign")
                || message.contains("x-guide");
    }

    private static boolean isTerminalFailure(JSONObject response, int code) {
        if (code >= 400) return true;
        String status = string(response.opt("status")).toLowerCase(Locale.US);
        JSONObject data = response.optJSONObject("data");
        if (status.isEmpty() && data != null) status = string(data.opt("status")).toLowerCase(Locale.US);
        return status.equals("failed") || status.equals("error") || status.equals("cancelled")
                || status.equals("canceled") || status.equals("refunded");
    }

    private static ApiClient.ApiException apiError(String prefix, JSONObject response, int code) {
        String message = string(response.opt("message"));
        if (message.isEmpty()) message = string(response.opt("msg"));
        if (message.isEmpty()) {
            JSONObject error = response.optJSONObject("error");
            if (error != null) message = string(error.opt("message"));
        }
        return new ApiClient.ApiException(prefix + (code != 0 ? " (code " + code + ")" : "")
                + (message.isEmpty() ? ": " + compact(response.toString()) : ": " + message));
    }

    private static String baseName(String path) {
        String value = path == null ? "" : path;
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        return value;
    }

    private static String randomAlphaNumeric(int length) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return result.toString();
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        RANDOM.nextBytes(bytes);
        String value = hex(bytes);
        return value.length() > length ? value.substring(0, length) : value;
    }

    private static String md5Hex(String value) throws Exception {
        return hex(MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String string(Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static String compact(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 700 ? value.substring(0, 700) + "…" : value;
    }

    private static String htmlDecode(String value) {
        return value.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static final class WebSession {
        final String themeVersion;
        final String cookies;
        final long createdAt;

        WebSession(String themeVersion, String cookies, long createdAt) {
            this.themeVersion = themeVersion;
            this.cookies = cookies;
            this.createdAt = createdAt;
        }
    }

    private static final class Signature {
        final String aesSecret;
        final String xGuide;
        final String xSign;

        Signature(String aesSecret, String xGuide, String xSign) {
            this.aesSecret = aesSecret;
            this.xGuide = xGuide;
            this.xSign = xSign;
        }
    }
}
