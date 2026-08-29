package com.jon.facebatch;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ApiClient {
    private static final int BUFFER = 64 * 1024;
    private static final long MAX_TEXT_BYTES = 72L * 1024L * 1024L;

    private ApiClient() {
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    public interface ConnectionMonitor {
        void opened(HttpURLConnection connection);
        void closed(HttpURLConnection connection);
    }

    public static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int httpStatus;
        public final int retryAfterSeconds;

        public ApiException(String message) {
            this(message, 0, 0);
        }

        public ApiException(String message, int httpStatus) {
            this(message, httpStatus, 0);
        }

        public ApiException(String message, int httpStatus, int retryAfterSeconds) {
            super(message);
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }
    }

    private static final class ResponseFile {
        final File file;
        final String contentType;
        final int status;

        ResponseFile(File file, String contentType, int status) {
            this.file = file;
            this.contentType = contentType == null ? "" : contentType;
            this.status = status;
        }
    }

    public static File execute(Context context, SwapJob job, AppSettings.Snapshot settings,
                               File workingDirectory, Cancellation cancellation,
                               ConnectionMonitor monitor) throws Exception {
        if (settings.endpoint == null || settings.endpoint.trim().isEmpty()) {
            throw new ApiException("The API endpoint is blank.");
        }
        if (!settings.endpoint.startsWith("https://")) {
            throw new ApiException("This build requires an HTTPS API endpoint.");
        }
        if (!workingDirectory.exists() && !workingDirectory.mkdirs()) {
            throw new ApiException("Could not create the temporary working folder.");
        }

        ResponseFile initial = postMultipart(context, job, settings, workingDirectory,
                cancellation, monitor);
        if (isImageContentType(initial.contentType) || looksLikeImage(initial.file)) {
            return initial.file;
        }

        String text = readTextAndDelete(initial.file);
        if (AppSettings.MODE_POLLING.equals(settings.responseMode)) {
            return handlePolling(text, settings, workingDirectory, cancellation, monitor);
        }
        return resolveTextPayload(text, initial.contentType, settings.resultPath,
                settings.endpoint, settings, workingDirectory, cancellation, monitor, 0);
    }

    private static ResponseFile postMultipart(Context context, SwapJob job,
                                               AppSettings.Snapshot settings,
                                               File workingDirectory,
                                               Cancellation cancellation,
                                               ConnectionMonitor monitor) throws Exception {
        Uri donorUri = Uri.parse(job.sourceUri);
        Uri targetUri = Uri.parse(job.targetUri);
        String donorField = settings.swapMappings ? settings.targetField : settings.sourceField;
        String targetField = settings.swapMappings ? settings.sourceField : settings.targetField;

        String boundary = "FaceBatch-" + UUID.randomUUID().toString();
        List<MultipartPart> parts = new ArrayList<>();
        parts.add(MultipartPart.file(context, donorField, donorUri, job.sourceName, boundary, settings.uploadMimeType));
        parts.add(MultipartPart.file(context, targetField, targetUri, job.targetName, boundary, settings.uploadMimeType));
        for (Map.Entry<String, String> entry : settings.formFields().entrySet()) {
            parts.add(MultipartPart.text(entry.getKey(), entry.getValue(), boundary));
        }
        byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        long length = closing.length;
        boolean knownLength = true;
        for (MultipartPart part : parts) {
            if (part.contentLength < 0) {
                knownLength = false;
                break;
            }
            length += part.header.length + part.contentLength + 2;
        }

        HttpURLConnection connection = null;
        try {
            connection = openConnection(settings.endpoint, "POST", settings, monitor);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (knownLength) {
                connection.setFixedLengthStreamingMode(length);
            } else {
                connection.setChunkedStreamingMode(BUFFER);
            }
            connection.connect();
            OutputStream output = new BufferedOutputStream(connection.getOutputStream(), BUFFER);
            byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
            try {
                byte[] buffer = new byte[BUFFER];
                for (MultipartPart part : parts) {
                    checkCancelled(cancellation);
                    output.write(part.header);
                    if (part.textBytes != null) {
                        output.write(part.textBytes);
                    } else {
                        InputStream input = new BufferedInputStream(
                                context.getContentResolver().openInputStream(part.uri), BUFFER);
                        try {
                            copy(input, output, buffer, cancellation);
                        } finally {
                            input.close();
                        }
                    }
                    output.write(crlf);
                }
                output.write(closing);
                output.flush();
            } finally {
                output.close();
            }
            return readConnectionResponse(connection, workingDirectory, cancellation);
        } finally {
            closeConnection(connection, monitor);
        }
    }

    private static File handlePolling(String initialText, AppSettings.Snapshot settings,
                                      File workingDirectory, Cancellation cancellation,
                                      ConnectionMonitor monitor) throws Exception {
        Object initialJson = parseJson(initialText);
        if (initialJson == null) {
            throw new ApiException("The API polling profile expected JSON, but the first response was not JSON.");
        }
        String pollUrl = JsonPath.readString(initialJson, settings.pollUrlPath);
        if (pollUrl == null || pollUrl.trim().isEmpty()) {
            if (settings.pollUrlTemplate != null && !settings.pollUrlTemplate.trim().isEmpty()
                    && settings.pollIdPath != null && !settings.pollIdPath.trim().isEmpty()) {
                String pollId = JsonPath.readString(initialJson, settings.pollIdPath);
                if (pollId != null && !pollId.trim().isEmpty()) {
                    pollUrl = settings.pollUrlTemplate.replace("{id}", Uri.encode(pollId.trim()));
                }
            }
        }
        if (pollUrl == null || pollUrl.trim().isEmpty()) {
            String immediate = JsonPath.readString(initialJson, settings.resultPath);
            if (immediate != null && !immediate.trim().isEmpty()) {
                return resolveValue(immediate, settings.endpoint, settings, workingDirectory,
                        cancellation, monitor, 0);
            }
            throw new ApiException("The first API response did not contain the configured polling URL path: "
                    + settings.pollUrlPath);
        }
        pollUrl = absoluteUrl(settings.endpoint, pollUrl.trim());
        for (int attempt = 1; attempt <= settings.maxPolls; attempt++) {
            sleepWithCancellation(settings.pollIntervalSeconds * 1000L, cancellation);
            ResponseFile response = getToFile(pollUrl, settings, workingDirectory, cancellation, monitor);
            if (isImageContentType(response.contentType)) {
                return response.file;
            }
            String pollText = readTextAndDelete(response.file);
            Object json = parseJson(pollText);
            if (json == null) {
                throw new ApiException("Polling response " + attempt + " was not valid JSON.");
            }
            String status = JsonPath.readString(json, settings.pollStatusPath);
            if (status != null && status.trim().equalsIgnoreCase(settings.pollSuccessValue)) {
                String resultValue = JsonPath.readString(json, settings.resultPath);
                if (resultValue == null || resultValue.trim().isEmpty()) {
                    throw new ApiException("The completed polling response did not contain: "
                            + settings.resultPath);
                }
                return resolveValue(resultValue, pollUrl, settings, workingDirectory,
                        cancellation, monitor, 0);
            }
            if (status != null && isFailureStatus(status, settings.pollFailureValues)) {
                throw new ApiException("The API reported a failed job with status: " + status);
            }
        }
        throw new ApiException("The API job did not finish after " + settings.maxPolls + " status checks.");
    }

    private static boolean isFailureStatus(String status, String values) {
        if (values == null) return false;
        String[] tokens = values.split(",");
        for (String token : tokens) {
            if (status.trim().equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static File resolveTextPayload(String text, String contentType, String configuredPath,
                                           String baseUrl, AppSettings.Snapshot settings,
                                           File workingDirectory, Cancellation cancellation,
                                           ConnectionMonitor monitor, int depth) throws Exception {
        if (text == null) {
            throw new ApiException("The API returned an empty response.");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException("The API returned an empty response.");
        }

        Object json = parseJson(trimmed);
        if (json != null) {
            String value = JsonPath.readString(json, configuredPath);
            if ((value == null || value.trim().isEmpty()) && AppSettings.MODE_AUTO.equals(settings.responseMode)) {
                String[] fallbacks = new String[]{"result.image", "image", "url", "output_url", "data.url"};
                for (String fallback : fallbacks) {
                    value = JsonPath.readString(json, fallback);
                    if (value != null && !value.trim().isEmpty()) break;
                }
            }
            if (value == null || value.trim().isEmpty()) {
                throw new ApiException("The API response did not contain the configured result path: "
                        + configuredPath + ". Response: " + snippet(trimmed));
            }
            return resolveValue(value, baseUrl, settings, workingDirectory, cancellation, monitor, depth);
        }
        return resolveValue(trimmed, baseUrl, settings, workingDirectory, cancellation, monitor, depth);
    }

    private static File resolveValue(String rawValue, String baseUrl, AppSettings.Snapshot settings,
                                     File workingDirectory, Cancellation cancellation,
                                     ConnectionMonitor monitor, int depth) throws Exception {
        if (depth > 2) {
            throw new ApiException("The API result redirected through too many text responses.");
        }
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            try {
                Object unquoted = new JSONTokener(value).nextValue();
                if (unquoted instanceof String) {
                    value = (String) unquoted;
                }
            } catch (Exception ignored) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/")) {
            String url = absoluteUrl(baseUrl, value);
            if (!url.startsWith("https://")) {
                throw new ApiException("The generated image URL is not HTTPS.");
            }
            ResponseFile response = getToFile(url, settings, workingDirectory, cancellation, monitor);
            if (isImageContentType(response.contentType) || looksLikeImage(response.file)) {
                return response.file;
            }
            String text = readTextAndDelete(response.file);
            return resolveTextPayload(text, response.contentType, settings.resultPath, url,
                    settings, workingDirectory, cancellation, monitor, depth + 1);
        }
        if (value.regionMatches(true, 0, "data:image/", 0, 11)) {
            int comma = value.indexOf(',');
            if (comma < 0) {
                throw new ApiException("The API returned a malformed image data URI.");
            }
            String metadata = value.substring(0, comma);
            String payload = value.substring(comma + 1);
            if (!metadata.toLowerCase(Locale.US).contains(";base64")) {
                throw new ApiException("Only base64 image data URIs are supported.");
            }
            return decodeBase64(payload, workingDirectory);
        }
        if (looksLikeBase64(value)) {
            return decodeBase64(value, workingDirectory);
        }
        throw new ApiException("The API returned a result that was neither an image URL nor image data: "
                + snippet(value));
    }

    private static ResponseFile getToFile(String url, AppSettings.Snapshot settings,
                                          File workingDirectory, Cancellation cancellation,
                                          ConnectionMonitor monitor) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, "GET", settings, monitor);
            connection.connect();
            return readConnectionResponse(connection, workingDirectory, cancellation);
        } finally {
            closeConnection(connection, monitor);
        }
    }

    private static HttpURLConnection openConnection(String url, String method,
                                                     AppSettings.Snapshot settings,
                                                     ConnectionMonitor monitor) throws Exception {
        URL parsed = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(settings.connectTimeoutSeconds * 1000);
        connection.setReadTimeout(settings.readTimeoutSeconds * 1000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> header : settings.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (monitor != null) {
            monitor.opened(connection);
        }
        return connection;
    }

    private static void closeConnection(HttpURLConnection connection, ConnectionMonitor monitor) {
        if (connection == null) return;
        if (monitor != null) {
            monitor.closed(connection);
        }
        connection.disconnect();
    }

    private static ResponseFile readConnectionResponse(HttpURLConnection connection,
                                                       File workingDirectory,
                                                       Cancellation cancellation) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            throw new ApiException("HTTP " + status + " returned no response body.", status);
        }
        File file = File.createTempFile("response_", ".bin", workingDirectory);
        InputStream input = new BufferedInputStream(stream, BUFFER);
        OutputStream output = new BufferedOutputStream(new FileOutputStream(file), BUFFER);
        try {
            copy(input, output, new byte[BUFFER], cancellation);
            output.flush();
        } finally {
            try { input.close(); } catch (Exception ignored) {}
            try { output.close(); } catch (Exception ignored) {}
        }
        if (status < 200 || status >= 300) {
            String message;
            try {
                message = readTextAndDelete(file);
            } catch (Exception ignored) {
                file.delete();
                message = "";
            }
            int retryAfter = retryAfterSeconds(connection, message);
            throw new ApiException("HTTP " + status + (message.isEmpty() ? "" : ": " + snippet(message)),
                    status, retryAfter);
        }
        String type = connection.getContentType();
        return new ResponseFile(file, type, status);
    }

    private static int retryAfterSeconds(HttpURLConnection connection, String body) {
        int value = 0;
        try {
            String header = connection.getHeaderField("Retry-After");
            if (header != null) value = Math.max(value, Integer.parseInt(header.trim()));
        } catch (Exception ignored) {
        }
        try {
            Object parsed = parseJson(body == null ? "" : body);
            if (parsed instanceof JSONObject) {
                JSONObject object = (JSONObject) parsed;
                value = Math.max(value, object.optInt("seconds_left", 0));
                value = Math.max(value, object.optInt("retry_after", 0));
                value = Math.max(value, object.optInt("retryAfter", 0));
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private static void copy(InputStream input, OutputStream output, byte[] buffer,
                             Cancellation cancellation) throws Exception {
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled(cancellation);
            output.write(buffer, 0, read);
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws ApiException {
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ApiException("Batch cancelled.");
        }
    }

    private static void sleepWithCancellation(long millis, Cancellation cancellation) throws Exception {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            checkCancelled(cancellation);
            Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private static String readTextAndDelete(File file) throws Exception {
        if (file.length() > MAX_TEXT_BYTES) {
            file.delete();
            throw new ApiException("The API returned an unexpectedly large text response.");
        }
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), 1024 * 1024));
        try {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            input.close();
            file.delete();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Object parseJson(String value) {
        try {
            Object parsed = new JSONTokener(value).nextValue();
            return parsed instanceof JSONObject || parsed instanceof JSONArray ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File decodeBase64(String value, File workingDirectory) throws Exception {
        try {
            String compact = value.replaceAll("\\s", "");
            byte[] decoded;
            try {
                decoded = Base64.decode(compact, Base64.DEFAULT);
            } catch (IllegalArgumentException standardFailure) {
                decoded = Base64.decode(compact, Base64.URL_SAFE);
            }
            if (decoded.length < 16) {
                throw new IllegalArgumentException("too short");
            }
            File file = File.createTempFile("decoded_", ".bin", workingDirectory);
            FileOutputStream output = new FileOutputStream(file);
            try {
                output.write(decoded);
            } finally {
                output.close();
            }
            if (!looksLikeImage(file)) {
                file.delete();
                throw new ApiException("The API returned base64 data, but it was not a recognized image.");
            }
            return file;
        } catch (IllegalArgumentException e) {
            throw new ApiException("The API returned malformed base64 image data.");
        }
    }

    private static boolean looksLikeBase64(String value) {
        String compact = value.replaceAll("\\s", "");
        if (compact.length() < 128 || compact.length() % 4 == 1) {
            return false;
        }
        return compact.matches("[A-Za-z0-9+/=_-]+");
    }

    public static boolean looksLikeImage(File file) {
        try {
            FileInputStream input = new FileInputStream(file);
            byte[] h = new byte[16];
            int n;
            try {
                n = input.read(h);
            } finally {
                input.close();
            }
            if (n < 4) return false;
            boolean jpeg = (h[0] & 0xff) == 0xff && (h[1] & 0xff) == 0xd8;
            boolean png = n >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 0x50 && h[2] == 0x4e && h[3] == 0x47;
            boolean gif = h[0] == 'G' && h[1] == 'I' && h[2] == 'F';
            boolean webp = n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
            return jpeg || png || gif || webp;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isImageContentType(String type) {
        return type != null && type.toLowerCase(Locale.US).startsWith("image/");
    }

    private static String absoluteUrl(String base, String candidate) throws Exception {
        return new URL(new URL(base), candidate).toString();
    }

    private static String snippet(String value) {
        String singleLine = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 700 ? singleLine.substring(0, 700) + "…" : singleLine;
    }

    private static final class MultipartPart {
        final byte[] header;
        final byte[] textBytes;
        final Uri uri;
        final long contentLength;

        private MultipartPart(byte[] header, byte[] textBytes, Uri uri, long contentLength) {
            this.header = header;
            this.textBytes = textBytes;
            this.uri = uri;
            this.contentLength = contentLength;
        }

        static MultipartPart text(String name, String value, String boundary) {
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + escape(name) + "\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n";
            byte[] data = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return new MultipartPart(header.getBytes(StandardCharsets.UTF_8), data, null, data.length);
        }

        static MultipartPart file(Context context, String name, Uri uri, String displayName,
                                  String boundary, String configuredMime) {
            String filename = displayName == null || displayName.trim().isEmpty()
                    ? "image.jpg" : displayName;
            String mime = configuredMime == null ? "" : configuredMime.trim();
            if (mime.isEmpty() || mime.equalsIgnoreCase("auto")) {
                mime = UriTools.mimeType(context, uri);
            }
            if (mime.isEmpty()) {
                mime = "image/jpeg";
            }
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + escape(name)
                    + "\"; filename=\"" + escape(filename) + "\"\r\n"
                    + "Content-Type: " + mime + "\r\n\r\n";
            long size = UriTools.size(context, uri);
            return new MultipartPart(header.getBytes(StandardCharsets.UTF_8), null, uri,
                    size > 0 ? size : -1);
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "_").replace("\"", "_")
                    .replace("\r", "_").replace("\n", "_");
        }
    }
}
