package com.jon.facebatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
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
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Interoperability implementation of the FJoy/Magicut single-face backend used by Face Over.
 * It follows the request flow observed in the supplied APK without bundling any code from it.
 */
public final class FJoyApiClient {
    private static final String PACKAGE_NAME = "com.video.reface.app.faceplay.deepface.photo";
    private static final String APP_VERSION = "1.1.8.1";
    private static final String APP_ID = "TX014";
    private static final String CHANNEL = "GOOGLEPLAY";
    private static final String LANG = "en-US";
    private static final String COUNTRY = "US";
    private static final String VERSION = "59";
    private static final String FUNCTION_TAG = "changefacepic";
    private static final String S3_DIRECTORY = "changefacepic/TX014";
    private static final Object EXECUTION_LOCK = new Object();
    private static final String SESSION_PREFS = "facebatch_fjoy_session_v3";
    private static final String[] ALL_SESSION_PREFS = new String[]{
            "facebatch_fjoy_session", "facebatch_fjoy_session_v1",
            "facebatch_fjoy_session_v2", SESSION_PREFS
    };
    private static final String KEY_UID = "uid";
    private static final String KEY_APP_UUID = "app_uuid";
    private static final String KEY_DEVICE_UUID = "device_uuid";
    private static final String KEY_USER_PSEUDO_ID = "user_pseudo_id";
    private static final String KEY_DEVICE_AD_ID = "device_ad_id";
    private static final String KEY_PHONE_MODEL = "phone_model";
    private static final String KEY_PHONE_BRAND = "phone_brand";
    private static final String KEY_OS_VERSION = "os_version";
    private static final String KEY_SUCCESS_COUNT = "success_count";
    private static final int MAX_SWAPS_PER_SESSION = 3;
    private static final long BATCH_START_COOLDOWN_MS = 2000L;
    private static final long ROTATION_COOLDOWN_MS = 8000L;
    private static volatile long sessionCooldownUntilMs = 0L;
    private static final String BASE = "https://aicup-v2.magicutapp.com/";
    private static final String REGISTER_URL =
            "https://analytics.enjoymobiserver.com/vsAnalytics/1.0.1/clientDevice/registerNewDevice.html?osType=1";
    private static final String GET_REGISTER_URL =
            "https://apis.videoshowapp.com/zone/1.0.1/point/user/getNewUserPointInfo.htm?osType=1";
    private static final String ADD_COIN_URL =
            "https://apis.videoshowapp.com/zone/1.0.1/point/task/doAddTaskPoint.htm?osType=1";
    private static final String GET_UUID_URL = BASE + "v4/getUuid";
    private static final String CHANGE_FACE_URL = BASE + "v3/changeFacePic";
    private static final String STATUS_BASE = BASE + "v4/downLoad/";
    private static final String BROWSER_UA = AppSettings.DEFAULT_USER_AGENT;
    private static final String DES_EDE_KEY = "NTMyMzExc2RmXXXXXXXXXXXX";
    private static final String RSA_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCblAv5qv/3dlYilRI23jRWhJIWivzvVEtyOEVpIUp9JGQC8479Me0pRb/ZFUzm1U7rqoBI0ByaN+SfEbhpCAaPGuR7E71qe18NNDKUhgUsOGUHr6clTPjzjHl46wS8I8hOzioH6Z9Op3hkbkPJC469EyulfvH8BuEH9myuSzaf/wIDAQAB";
    private static final int BUFFER = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FJoyApiClient() {
    }

    /**
     * Starts a new FJoy installation-style session for a new user-initiated batch.
     * This clears only FaceBatch's reconstructed FJoy identity. It does not clear
     * selections, app settings, output history, or any other FaceBatch data.
     */
    public static void resetForNewBatch(Context context) {
        synchronized (EXECUTION_LOCK) {
            hardResetStoredSession(context, BATCH_START_COOLDOWN_MS);
        }
    }

    /**
     * Discards the FJoy identity after a batch so the next run cannot accidentally inherit it.
     * This does not erase FaceBatch settings, selections, or downloaded results.
     */
    public static void discardAfterBatch(Context context) {
        synchronized (EXECUTION_LOCK) {
            hardResetStoredSession(context, 0L);
        }
    }

    public static File execute(Context context, SwapJob job, File workDirectory,
                               ApiClient.Cancellation cancellation,
                               ApiClient.ConnectionMonitor monitor) throws Exception {
        // FJoy ties its swap jobs to a points-system user. Preserve one stable identity and uid
        // only across jobs inside the current batch. BatchService clears that state before and
        // after each batch. A missing-user 1404 is still recoverable within a batch.
        synchronized (EXECUTION_LOCK) {
            // The user's live testing shows that a fourth result can complete but the identity
            // is then poisoned for later requests. Rotate conservatively after three completed
            // swaps, perform a reinstall-style local reset, and give the new server-side points
            // record time to propagate before submitting the next job.
            if (successfulSwapsInCurrentSession(context) >= MAX_SWAPS_PER_SESSION) {
                hardResetStoredSession(context, ROTATION_COOLDOWN_MS);
                waitForSessionCooldown(cancellation);
            }

            ApiClient.ApiException lastMissingUser = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                checkCancelled(cancellation);
                if (attempt == 1) {
                    hardResetStoredSession(context, ROTATION_COOLDOWN_MS);
                    waitForSessionCooldown(cancellation);
                } else if (attempt == 2) {
                    hardResetStoredSession(context, ROTATION_COOLDOWN_MS + 4000L);
                    waitForSessionCooldown(cancellation);
                }
                try {
                    return executeLocked(context, job, workDirectory, cancellation, monitor);
                } catch (ApiClient.ApiException e) {
                    if (!isMissingPointsUser(e) || attempt == 2) {
                        throw e;
                    }
                    lastMissingUser = e;
                }
            }
            throw lastMissingUser == null
                    ? new ApiClient.ApiException("FJoy could not establish a valid points-system user.")
                    : lastMissingUser;
        }
    }

    private static File executeLocked(Context context, SwapJob job, File workDirectory,
                                      ApiClient.Cancellation cancellation,
                                      ApiClient.ConnectionMonitor monitor) throws Exception {
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            throw new ApiClient.ApiException("Could not create the temporary working folder.");
        }
        checkCancelled(cancellation);
        waitForSessionCooldown(cancellation);

        Session session = prepareSession(context, cancellation, monitor);
        Identity identity = session.identity;
        String uid = session.uid;
        OtherData credentials = session.credentials;

        File target = copyUriToTemp(context, Uri.parse(job.targetUri), workDirectory,
                "target_", cancellation);
        File donor = copyUriToTemp(context, Uri.parse(job.sourceUri), workDirectory,
                "face_", cancellation);
        try {
            String targetName = objectName(uid);
            String targetUrl;
            try {
                targetUrl = uploadS3(target, targetName, credentials, cancellation, monitor);
            } catch (Exception e) {
                throw stage("FJoy target-image upload", e);
            }

            String donorName = objectName(uid);
            String donorUrl;
            try {
                donorUrl = uploadS3(donor, donorName, credentials, cancellation, monitor);
            } catch (Exception e) {
                throw stage("FJoy donor-image upload", e);
            }

            String taskId;
            try {
                taskId = submitSwap(identity, uid, targetUrl, donorUrl, cancellation, monitor);
            } catch (Exception e) {
                throw stage("FJoy swap submission", e);
            }
            try {
                File result = pollResult(taskId, credentials, workDirectory, cancellation, monitor);
                markSessionSuccessful(context);
                return result;
            } catch (Exception e) {
                throw stage("FJoy result polling", e);
            }
        } finally {
            target.delete();
            donor.delete();
        }
    }

    private static Session prepareSession(Context context,
                                          ApiClient.Cancellation cancellation,
                                          ApiClient.ConnectionMonitor monitor) throws Exception {
        Identity identity = loadOrCreateIdentity(context);
        String storedUid = sessionPrefs(context).getString(KEY_UID, "");
        if (storedUid == null) storedUid = "";
        storedUid = storedUid.trim();

        // A known uid should normally be reused. This avoids manufacturing a new points-system
        // user for every image pair and matches the behavior of an installed consumer app.
        if (!storedUid.isEmpty()) {
            try {
                IdResponse existing = getUuid(identity, storedUid, true, cancellation, monitor);
                if (existing.success && existing.other != null) {
                    String confirmedUid = existing.data == null || existing.data.trim().isEmpty()
                            ? storedUid : existing.data.trim();
                    saveStoredUid(context, confirmedUid);
                    return new Session(identity, confirmedUid, existing.other);
                }
            } catch (Exception ignored) {
                checkCancelled(cancellation);
            }
            // The stored uid no longer yields credentials. Re-bootstrap this same device identity
            // before considering a brand-new identity.
            clearStoredUid(context);
        }

        try {
            postEncrypted(REGISTER_URL, registrationJson(identity), identity, false, cancellation, monitor);
        } catch (Exception e) {
            throw stage("FJoy device registration", e);
        }
        try {
            postEncrypted(GET_REGISTER_URL, registrationJson(identity), identity, true, cancellation, monitor);
        } catch (Exception e) {
            throw stage("FJoy account initialization", e);
        }

        // Give the points service a brief moment to make the just-created user visible to the
        // swap cluster. The 1404 observed after otherwise-successful submissions is consistent
        // with eventual propagation between these services.
        sleep(1800L, cancellation);

        // Awarding/initializing the points record is best effort in the original app. Retry once
        // here because a missing points record is exactly what FJoy code 1404 reports.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                postEncrypted(ADD_COIN_URL, addCoinJson(identity), identity, true, cancellation, monitor);
                break;
            } catch (Exception ignored) {
                checkCancelled(cancellation);
                if (attempt == 0) sleep(1000L, cancellation);
            }
        }

        IdResponse first;
        try {
            first = getUuid(identity, "", false, cancellation, monitor);
        } catch (Exception e) {
            throw stage("FJoy user-id request", e);
        }
        if (!first.success) {
            throw new ApiClient.ApiException("FJoy initialization failed: " + first.message);
        }
        OtherData credentials = first.other;
        String uid = first.data == null ? "" : first.data.trim();
        if (credentials == null) {
            if (uid.isEmpty()) {
                throw new ApiClient.ApiException("FJoy did not return a user id or upload credentials.");
            }
            IdResponse second;
            try {
                second = getUuid(identity, uid, true, cancellation, monitor);
            } catch (Exception e) {
                throw stage("FJoy upload-credential request", e);
            }
            if (!second.success || second.other == null) {
                throw new ApiClient.ApiException("FJoy did not return upload credentials: " + second.message);
            }
            credentials = second.other;
            if (second.data != null && !second.data.trim().isEmpty()) {
                uid = second.data.trim();
            }
        }
        if (uid.isEmpty()) {
            throw new ApiClient.ApiException("FJoy returned upload credentials but no user id.");
        }

        saveStoredIdentity(context, identity);
        saveStoredUid(context, uid);
        sleep(1200L, cancellation);
        return new Session(identity, uid, credentials);
    }

    private static IdResponse getUuid(Identity identity, String uid, boolean includeFunctionTag,
                                      ApiClient.Cancellation cancellation,
                                      ApiClient.ConnectionMonitor monitor) throws Exception {
        LinkedHashMap<String, String> form = new LinkedHashMap<>();
        form.put("country", COUNTRY);
        form.put("uId", uid == null ? "" : uid);
        form.put("appVersion", APP_VERSION);
        form.put("appTime", String.valueOf(System.currentTimeMillis()));
        form.put("appId", APP_ID);
        form.put("pkgName", PACKAGE_NAME);
        if (includeFunctionTag) form.put("functionTag", FUNCTION_TAG);
        form.put("channelName", CHANNEL);
        form.put("lang", LANG);
        form.put("uuId", identity.appUuid);
        form.put("version", VERSION);
        form.put("isVip", "false");
        String text = requestForm(GET_UUID_URL, form, cancellation, monitor);
        JSONObject json = object(text, "FJoy id response");
        IdResponse response = new IdResponse();
        response.success = json.optBoolean("success", false);
        response.code = json.optInt("code", 0);
        response.data = stringOrEmpty(json.opt("data"));
        response.message = stringOrEmpty(json.opt("message"));
        response.other = parseOther(json.optJSONObject("other"));
        if (response.other == null) {
            JSONObject dataObj = json.optJSONObject("data");
            if (dataObj != null) response.other = parseOther(dataObj.optJSONObject("other"));
        }
        return response;
    }

    private static String submitSwap(Identity identity, String uid, String targetUrl,
                                     String donorUrl, ApiClient.Cancellation cancellation,
                                     ApiClient.ConnectionMonitor monitor) throws Exception {
        long signedTime = System.currentTimeMillis();
        JSONObject payload = new JSONObject();
        payload.put("uId", uid);
        payload.put("appTime", String.valueOf(signedTime));
        payload.put("modelFile", targetUrl);
        payload.put("imageFile", donorUrl);
        payload.put("pkgName", PACKAGE_NAME);
        payload.put("materialType", "0");
        payload.put("check", "0");
        payload.put("priority", "50");
        payload.put("picId", "0");
        payload.put("pointId", identity.appUuid);
        payload.put("consumeType", "swappictures");

        String decrypt = rsaEncrypt(payload.toString());
        String sign = md5Hex(PACKAGE_NAME + uid + signedTime);

        LinkedHashMap<String, String> parts = new LinkedHashMap<>();
        parts.put("country", COUNTRY);
        parts.put("uId", uid);
        parts.put("appVersion", APP_VERSION);
        parts.put("appTime", String.valueOf(System.currentTimeMillis()));
        parts.put("appId", APP_ID);
        parts.put("pkgName", PACKAGE_NAME);
        parts.put("channelName", CHANNEL);
        parts.put("lang", LANG);
        parts.put("uuId", identity.appUuid);
        parts.put("version", VERSION);
        parts.put("isVip", "false");
        parts.put("decrypt", decrypt);
        parts.put("sign", sign);

        String text = requestMultipartText(CHANGE_FACE_URL, parts, cancellation, monitor);
        JSONObject json = object(text, "FJoy submit response");
        int code = json.optInt("code", -1);
        String task = stringOrEmpty(json.opt("data")).trim();
        if (code != 0 || task.isEmpty()) {
            throw new ApiClient.ApiException("FJoy rejected the swap"
                    + (code >= 0 ? " (code " + code + ")" : "")
                    + ": " + stringOrEmpty(json.opt("message")));
        }
        return task;
    }

    private static File pollResult(String taskId, OtherData credentials, File workDirectory,
                                   ApiClient.Cancellation cancellation,
                                   ApiClient.ConnectionMonitor monitor) throws Exception {
        String statusUrl = STATUS_BASE + encodePathSegment(taskId);
        for (int i = 0; i < 60; i++) {
            sleep(2000L, cancellation);
            String text = requestGetText(statusUrl, cancellation, monitor);
            JSONObject json = object(text, "FJoy status response");
            int code = json.optInt("code", 0);
            if (code > 1000) {
                throw new ApiClient.ApiException("FJoy status failed with code " + code
                        + ": " + stringOrEmpty(json.opt("message")));
            }
            String result = stringOrEmpty(json.opt("data")).trim();
            if (!result.isEmpty() && !"null".equalsIgnoreCase(result)) {
                String resolved = result;
                if (resolved.startsWith("//")) {
                    resolved = "https:" + resolved;
                } else if (resolved.startsWith("/")) {
                    resolved = new URL(new URL(BASE), resolved).toString();
                } else if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
                    if (credentials.baseUrl != null && !credentials.baseUrl.trim().isEmpty()) {
                        String base = credentials.baseUrl.trim();
                        if (!base.endsWith("/")) base += "/";
                        resolved = new URL(new URL(base), resolved).toString();
                    }
                }
                if (resolved.startsWith("https://")) {
                    return download(resolved, workDirectory, cancellation, monitor);
                }
            }
        }
        throw new ApiClient.ApiException("FJoy did not finish the swap within two minutes.");
    }

    private static String uploadS3(File file, String filename, OtherData other,
                                   ApiClient.Cancellation cancellation,
                                   ApiClient.ConnectionMonitor monitor) throws Exception {
        if (other.accessKey.isEmpty() || other.secretKey.isEmpty() || other.bucketName.isEmpty()
                || other.region.isEmpty()) {
            throw new ApiClient.ApiException("FJoy returned incomplete upload credentials.");
        }
        // Face Over ignores the directory returned in the credential payload for this tool
        // and always uploads single-face images under this fixed application directory.
        String key = S3_DIRECTORY + "/" + filename;
        String encodedPath = encodeS3Path(key);
        String region = other.region.trim();
        String host = "us-east-1".equals(region)
                ? other.bucketName + ".s3.amazonaws.com"
                : other.bucketName + ".s3." + region + ".amazonaws.com";
        String url = "https://" + host + "/" + encodedPath;

        String payloadHash = sha256Hex(file);
        Date now = new Date();
        SimpleDateFormat dateTime = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        dateTime.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        String amzDate = dateTime.format(now);
        String date = day.format(now);
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-acl:public-read\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-acl;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n/" + encodedPath + "\n\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = awsSigningKey(other.secretKey, date, region, "s3");
        String signature = hex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + other.accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpURLConnection connection = null;
        try {
            connection = open(url, "PUT", 25_000, 150_000, monitor);
            connection.setDoOutput(true);
            connection.setRequestProperty("x-amz-acl", "public-read");
            connection.setRequestProperty("x-amz-content-sha256", payloadHash);
            connection.setRequestProperty("x-amz-date", amzDate);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
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
            if (status < 200 || status >= 300) {
                String body = readResponseText(connection, status);
                throw new ApiClient.ApiException("FJoy S3 upload returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + compact(body)), status);
            }
            return url;
        } finally {
            close(connection, monitor);
        }
    }

    private static String registrationJson(Identity identity) throws Exception {
        JSONObject json = new JSONObject();
        json.put("deviceUuid", identity.appUuid);
        json.put("appVersion", APP_VERSION);
        json.put("userPseudoId", identity.userPseudoId);
        json.put("uuId", identity.deviceUuid);
        json.put("phoneModel", identity.phoneModel);
        json.put("deviceAdId", identity.deviceAdId);
        json.put("osVersion", String.valueOf(identity.osVersion));
        json.put("requestId", identity.requestId);
        json.put("phoneBrand", identity.phoneBrand);
        json.put("pkgName", PACKAGE_NAME);
        json.put("channelName", CHANNEL);
        json.put("lang", LANG);
        return json.toString();
    }

    private static String addCoinJson(Identity identity) throws Exception {
        JSONObject json = new JSONObject();
        json.put("deviceUuid", identity.appUuid);
        json.put("uuId", identity.deviceUuid);
        json.put("userId", "");
        json.put("pkgName", PACKAGE_NAME);
        json.put("lang", LANG);
        json.put("versionName", APP_VERSION);
        json.put("channelName", CHANNEL);
        json.put("requestId", requestId());
        json.put("pointId", identity.appUuid);
        json.put("taskId", "24");
        return json.toString();
    }

    private static void postEncrypted(String url, String json, Identity identity,
                                      boolean includeDeviceIdentity,
                                      ApiClient.Cancellation cancellation,
                                      ApiClient.ConnectionMonitor monitor) throws Exception {
        byte[] encrypted = encryptDesEde(json);
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", 20_000, 120_000, monitor);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("charset", "utf-8");
            connection.setRequestProperty("User-Agent", analyticsUserAgent(identity));
            connection.setRequestProperty("x-uuid", identity.deviceUuid);
            if (includeDeviceIdentity) {
                connection.setRequestProperty("x-deviceuuid", identity.appUuid);
                connection.setRequestProperty("x-userid", "");
            }
            connection.setRequestProperty("x-openid", "");
            connection.setFixedLengthStreamingMode(encrypted.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(encrypted);
                out.flush();
            } finally {
                out.close();
            }
            checkCancelled(cancellation);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String body = readResponseText(connection, status);
                throw new ApiClient.ApiException("Face Over FJoy setup returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + compact(body)), status);
            }
            // Consume and discard the response so the connection finishes cleanly.
            readResponseText(connection, status);
        } finally {
            close(connection, monitor);
        }
    }

    private static String requestForm(String url, LinkedHashMap<String, String> form,
                                      ApiClient.Cancellation cancellation,
                                      ApiClient.ConnectionMonitor monitor) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", 20_000, 120_000, monitor);
            browserHeaders(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
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
            String response = readResponseText(connection, status);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException("FJoy returned HTTP " + status + ": " + compact(response), status);
            }
            return response;
        } finally {
            close(connection, monitor);
        }
    }

    private static String requestMultipartText(String url, LinkedHashMap<String, String> values,
                                               ApiClient.Cancellation cancellation,
                                               ApiClient.ConnectionMonitor monitor) throws Exception {
        String boundary = "FaceBatchFJoy-" + UUID.randomUUID();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + escape(entry.getKey()) + "\"\r\n\r\n";
            payload.write(header.getBytes(StandardCharsets.UTF_8));
            payload.write((entry.getValue() == null ? "" : entry.getValue()).getBytes(StandardCharsets.UTF_8));
            payload.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        payload.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] bytes = payload.toByteArray();
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", 20_000, 120_000, monitor);
            browserHeaders(connection);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
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
            String response = readResponseText(connection, status);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException("FJoy submit returned HTTP " + status + ": " + compact(response), status);
            }
            return response;
        } finally {
            close(connection, monitor);
        }
    }

    private static String requestGetText(String url, ApiClient.Cancellation cancellation,
                                         ApiClient.ConnectionMonitor monitor) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(url, "POST", 20_000, 120_000, monitor);
            browserHeaders(connection);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
            checkCancelled(cancellation);
            int status = connection.getResponseCode();
            String response = readResponseText(connection, status);
            if (status < 200 || status >= 300) {
                throw new ApiClient.ApiException("FJoy status returned HTTP " + status + ": " + compact(response), status);
            }
            return response;
        } finally {
            close(connection, monitor);
        }
    }

    private static File download(String url, File workDirectory,
                                 ApiClient.Cancellation cancellation,
                                 ApiClient.ConnectionMonitor monitor) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(url, "GET", 20_000, 150_000, monitor);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", BROWSER_UA);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String body = readResponseText(connection, status);
                throw new ApiClient.ApiException("FJoy result download returned HTTP " + status
                        + (body.isEmpty() ? "" : ": " + compact(body)), status);
            }
            File outFile = File.createTempFile("fjoy_result_", ".bin", workDirectory);
            InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER);
            OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER);
            try {
                copy(in, out, cancellation);
                out.flush();
            } finally {
                in.close();
                out.close();
            }
            if (!ApiClient.looksLikeImage(outFile)) {
                outFile.delete();
                throw new ApiClient.ApiException("FJoy returned a result URL, but it did not contain a recognized image.");
            }
            return outFile;
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
        connection.setDefaultUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Pragma", "no-cache");
        if (monitor != null) monitor.opened(connection);
        return connection;
    }

    private static void browserHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", BROWSER_UA);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Origin", "https://taoanhdep.com");
    }

    private static String readResponseText(HttpURLConnection connection, int status) throws Exception {
        InputStream input = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1 && out.size() < 2 * 1024 * 1024) {
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

    private static void checkCancelled(ApiClient.Cancellation cancellation) throws ApiClient.ApiException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ApiClient.ApiException("Batch cancelled.");
        }
    }

    private static void sleep(long ms, ApiClient.Cancellation cancellation) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancelled(cancellation);
            Thread.sleep(Math.min(250L, Math.max(1L, end - System.currentTimeMillis())));
        }
    }

    private static JSONObject object(String text, String label) throws Exception {
        Object parsed = new JSONTokener(text == null ? "" : text).nextValue();
        if (!(parsed instanceof JSONObject)) {
            throw new ApiClient.ApiException(label + " was not JSON: " + compact(text));
        }
        return (JSONObject) parsed;
    }

    private static OtherData parseOther(JSONObject json) {
        if (json == null) return null;
        OtherData o = new OtherData();
        o.accessKey = stringOrEmpty(json.opt("accessKey"));
        o.baseUrl = stringOrEmpty(json.opt("baseUrl"));
        o.bucketName = stringOrEmpty(json.opt("bucketName"));
        o.bucketType = stringOrEmpty(json.opt("bucketType"));
        o.dir = stringOrEmpty(json.opt("dir"));
        o.region = stringOrEmpty(json.opt("region"));
        o.secretKey = stringOrEmpty(json.opt("secretKey"));
        if (o.accessKey.isEmpty() && o.bucketName.isEmpty() && o.secretKey.isEmpty()) return null;
        return o;
    }

    private static String stringOrEmpty(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        return String.valueOf(value);
    }

    private static byte[] encryptDesEde(String plain) throws Exception {
        SecretKey key = new SecretKeySpec(DES_EDE_KEY.getBytes(StandardCharsets.UTF_8), "DESede");
        Cipher cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    }

    private static String rsaEncrypt(String plain) throws Exception {
        byte[] keyBytes = Base64.decode(RSA_PUBLIC_KEY, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] input = plain.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < input.length; offset += 117) {
            int count = Math.min(117, input.length - offset);
            byte[] encrypted = cipher.doFinal(input, offset, count);
            output.write(encrypted);
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static String md5Hex(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER);
        try {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) md.update(buffer, 0, read);
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String objectName(String uid) throws Exception {
        String seed = String.valueOf(System.currentTimeMillis()) + uid;
        String digest = sha256Hex(seed.getBytes(StandardCharsets.UTF_8));
        return digest.substring(0, 32) + ".webp";
    }

    private static byte[] awsSigningKey(String secret, String date, String region, String service)
            throws Exception {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String encodeS3Path(String key) throws Exception {
        String[] segments = key.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(awsEncode(segments[i]));
        }
        return out.toString();
    }

    private static String awsEncode(String value) throws Exception {
        String encoded = URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%7E", "~");
        return encoded;
    }

    private static String encodePathSegment(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String trimSlashes(String value) {
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
    }

    private static SharedPreferences sessionPrefs(Context context) {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
    }

    private static Identity loadOrCreateIdentity(Context context) {
        SharedPreferences p = sessionPrefs(context);
        String appUuid = p.getString(KEY_APP_UUID, "");
        String deviceUuid = p.getString(KEY_DEVICE_UUID, "");
        String userPseudoId = p.getString(KEY_USER_PSEUDO_ID, "");
        String deviceAdId = p.getString(KEY_DEVICE_AD_ID, "");
        String phoneModel = p.getString(KEY_PHONE_MODEL, "");
        String phoneBrand = p.getString(KEY_PHONE_BRAND, "");
        int osVersion = p.getInt(KEY_OS_VERSION, 0);
        if (isBlank(appUuid) || isBlank(deviceUuid) || isBlank(userPseudoId)
                || isBlank(deviceAdId) || isBlank(phoneModel) || isBlank(phoneBrand)
                || osVersion <= 0) {
            Identity created = Identity.create();
            saveStoredIdentity(context, created);
            return created;
        }
        Identity identity = new Identity();
        identity.appUuid = appUuid;
        identity.deviceUuid = deviceUuid;
        identity.userPseudoId = userPseudoId;
        identity.deviceAdId = deviceAdId;
        identity.phoneModel = phoneModel;
        identity.phoneBrand = phoneBrand;
        identity.osVersion = osVersion;
        identity.requestId = requestId();
        return identity;
    }

    private static void saveStoredIdentity(Context context, Identity identity) {
        sessionPrefs(context).edit()
                .putString(KEY_APP_UUID, identity.appUuid)
                .putString(KEY_DEVICE_UUID, identity.deviceUuid)
                .putString(KEY_USER_PSEUDO_ID, identity.userPseudoId)
                .putString(KEY_DEVICE_AD_ID, identity.deviceAdId)
                .putString(KEY_PHONE_MODEL, identity.phoneModel)
                .putString(KEY_PHONE_BRAND, identity.phoneBrand)
                .putInt(KEY_OS_VERSION, identity.osVersion)
                .commit();
    }

    private static void saveStoredUid(Context context, String uid) {
        sessionPrefs(context).edit().putString(KEY_UID, uid == null ? "" : uid.trim()).commit();
    }

    private static void clearStoredUid(Context context) {
        sessionPrefs(context).edit().remove(KEY_UID).commit();
    }

    private static void clearStoredSession(Context context) {
        hardResetStoredSession(context, 0L);
    }

    private static void hardResetStoredSession(Context context, long cooldownMs) {
        // Clear every preference namespace used by earlier FaceBatch FJoy builds, not merely
        // the current uid. deleteSharedPreferences also evicts Android's in-process cache for
        // that preference file, which more closely reproduces an uninstall/reinstall cycle.
        for (String name : ALL_SESSION_PREFS) {
            try {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            } catch (Exception ignored) {
            }
            try {
                context.deleteSharedPreferences(name);
            } catch (Exception ignored) {
            }
            try {
                File preferencesDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
                new File(preferencesDir, name + ".xml").delete();
                new File(preferencesDir, name + ".xml.bak").delete();
            } catch (Exception ignored) {
            }
        }

        // No cookies are intentionally used, but HttpURLConnection can inherit a process-wide
        // CookieManager. Clear it so a server cookie cannot outlive the reconstructed identity.
        try {
            CookieHandler handler = CookieHandler.getDefault();
            if (handler instanceof CookieManager) {
                ((CookieManager) handler).getCookieStore().removeAll();
            }
        } catch (Exception ignored) {
        }

        // Remove temporary uploads/results from the prior group. Built-in FJoy processing is
        // serial, so this runs only between jobs and cannot delete an active request's files.
        try {
            deleteRecursively(new File(context.getCacheDir(), "batch_work"));
        } catch (Exception ignored) {
        }

        long now = System.currentTimeMillis();
        long requestedUntil = now + Math.max(0L, cooldownMs);
        if (requestedUntil > sessionCooldownUntilMs) {
            sessionCooldownUntilMs = requestedUntil;
        }
    }

    private static void waitForSessionCooldown(ApiClient.Cancellation cancellation) throws Exception {
        while (true) {
            long remaining = sessionCooldownUntilMs - System.currentTimeMillis();
            if (remaining <= 0L) return;
            sleep(Math.min(remaining, 500L), cancellation);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        try {
            file.delete();
        } catch (Exception ignored) {
        }
    }

    private static int successfulSwapsInCurrentSession(Context context) {
        return Math.max(0, sessionPrefs(context).getInt(KEY_SUCCESS_COUNT, 0));
    }

    private static void markSessionSuccessful(Context context) {
        SharedPreferences preferences = sessionPrefs(context);
        int nextCount = Math.max(0, preferences.getInt(KEY_SUCCESS_COUNT, 0)) + 1;
        preferences.edit()
                .putInt(KEY_SUCCESS_COUNT, nextCount)
                .putLong("last_success_ms", System.currentTimeMillis())
                .commit();
    }

    private static boolean isMissingPointsUser(Exception exception) {
        String message = exception == null || exception.getMessage() == null
                ? "" : exception.getMessage();
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("code 1404")
                || lower.contains("1404:")
                || lower.contains("points-system user does not exist")
                || lower.contains("user does not exist")
                || message.contains("积分系统用户不存在")
                || message.contains("用户不存在");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static ApiClient.ApiException stage(String stage, Exception exception) {
        if (exception instanceof ApiClient.ApiException) {
            ApiClient.ApiException api = (ApiClient.ApiException) exception;
            String detail = api.getMessage() == null ? exception.getClass().getSimpleName() : api.getMessage();
            return new ApiClient.ApiException(stage + " failed: " + detail, api.httpStatus,
                    api.retryAfterSeconds);
        }
        String detail = exception.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = exception.getClass().getSimpleName();
        }
        return new ApiClient.ApiException(stage + " failed: " + detail);
    }

    private static String compact(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 650 ? value.substring(0, 650) + "…" : value;
    }

    private static String requestId() {
        return String.valueOf(System.nanoTime()) + RANDOM.nextInt(10000);
    }

    private static String analyticsUserAgent(Identity identity) {
        return CHANNEL + "/" + PACKAGE_NAME + "/" + APP_VERSION
                + " (Linux; U; Android " + identity.osVersion + "; "
                + identity.phoneModel + "/" + identity.phoneBrand + ")";
    }

    private static final class Identity {
        String appUuid;
        String deviceUuid;
        String userPseudoId;
        String deviceAdId;
        String phoneModel;
        String phoneBrand;
        String requestId;
        int osVersion;

        static Identity create() {
            Identity i = new Identity();
            String compact = UUID.randomUUID().toString().replace("-", "");
            i.appUuid = "jrxc_" + compact.substring(0, 16);
            i.deviceUuid = "jtc3_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            i.userPseudoId = hex(random);
            i.deviceAdId = UUID.randomUUID().toString();
            String[] models = {"SM-G9880", "MI-10T", "Pixel-6", "OnePlus-9", "Reno-5", "V21", "Realme-8", "P40-Pro"};
            String[] brands = {"samsung", "xiaomi", "oneplus", "google", "oppo", "vivo", "realme", "huawei"};
            i.phoneModel = models[RANDOM.nextInt(models.length)];
            i.phoneBrand = brands[RANDOM.nextInt(brands.length)];
            i.requestId = requestId();
            i.osVersion = 10 + RANDOM.nextInt(6);
            return i;
        }
    }

    private static final class Session {
        final Identity identity;
        final String uid;
        final OtherData credentials;

        Session(Identity identity, String uid, OtherData credentials) {
            this.identity = identity;
            this.uid = uid;
            this.credentials = credentials;
        }
    }

    private static final class IdResponse {
        boolean success;
        int code;
        String data;
        String message;
        OtherData other;
    }

    private static final class OtherData {
        String accessKey = "";
        String baseUrl = "";
        String bucketName = "";
        String bucketType = "";
        String dir = "";
        String region = "";
        String secretKey = "";
    }
}
