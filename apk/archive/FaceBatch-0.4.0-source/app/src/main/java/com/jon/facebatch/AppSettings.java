package com.jon.facebatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AppSettings {
    public static final String PREFS = "facebatch_settings";

    public static final String PROFILE_AUTO = "faceover_auto";
    public static final String PROFILE_AIFACE = "aifaceswap_hq";
    public static final String PROFILE_FJOY = "fjoy";
    public static final String PROFILE_TAO = "tao";
    public static final String PROFILE_PUBLIC_KEY = "tiemanhai_public";
    public static final String PROFILE_CUSTOM = "custom";

    // Backward-compatibility values used by FaceBatch 0.1 and 0.2.
    private static final String OLD_PROFILE_LEGACY = "legacy";
    private static final String OLD_PROFILE_OFFICIAL = "official";

    public static final String TAO_ENDPOINT = "https://api.taoanhdep.com/doi-mat";
    public static final String TAO_SOURCE_FIELD = "source";
    public static final String TAO_TARGET_FIELD = "target";
    public static final String TAO_ENHANCER_FIELD = "enhancer";
    public static final String TAO_NSFW_FIELD = "check-nsfw";
    public static final String TAO_RESULT_PATH = "result.image";
    public static final String TAO_ORIGIN = "https://taoanhdep.com";

    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36";
    public static final String DEFAULT_OUTPUT_FOLDER = "FaceBatch";
    public static final String DEFAULT_UPLOAD_MIME_TYPE = "image/jpeg";

    // This is a separate, documented public API. It is NOT the internal no-account
    // service used by the regular Face Over app, and it requires its own API key.
    public static final String PUBLIC_KEY_ENDPOINT = "https://tiemanhai.com/api/v1-thay-doi-khuon-mat.php";
    public static final String PUBLIC_KEY_SOURCE_FIELD = "face";
    public static final String PUBLIC_KEY_TARGET_FIELD = "image";
    public static final String PUBLIC_KEY_RESULT_PATH = "output_url";
    public static final String PUBLIC_KEY_POLL_URL_PATH = "poll_url";
    public static final String PUBLIC_KEY_POLL_STATUS_PATH = "status";
    public static final String PUBLIC_KEY_POLL_SUCCESS_VALUE = "success";
    public static final String PUBLIC_KEY_POLL_FAILURE_VALUES = "failed,error,cancelled,timeout,refunded";
    public static final String PUBLIC_KEY_POLL_ID_PATH = "id";
    public static final String PUBLIC_KEY_POLL_URL_TEMPLATE = "https://tiemanhai.com/api/v1-jobs-get.php?id={id}";
    public static final String PUBLIC_KEY_AUTH_HEADER_NAME = "Authorization";
    public static final String PUBLIC_KEY_EXTRA_FORM_FIELDS = "swap_all=true\ntarget_index=0\nai_label=false\noutput_format=jpg";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_JSON = "json";
    public static final String MODE_POLLING = "polling";

    private AppSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Snapshot load(Context context) {
        SharedPreferences p = prefs(context);
        Snapshot s = new Snapshot();
        String rawProfile = p.getString("profile", PROFILE_AIFACE);
        if (OLD_PROFILE_LEGACY.equals(rawProfile) || OLD_PROFILE_OFFICIAL.equals(rawProfile)
                || PROFILE_PUBLIC_KEY.equals(rawProfile)) {
            // Earlier builds exposed a separate public developer API in the main switch. It is
            // not the no-account backend used by Face Over, so move existing installs back to
            // the regular Face Over routing behavior.
            rawProfile = PROFILE_AIFACE;
            p.edit().putString("profile", PROFILE_AIFACE).apply();
        }
        if (!p.getBoolean("hq_engine_migrated_v1", false)) {
            if (PROFILE_AUTO.equals(rawProfile) || PROFILE_FJOY.equals(rawProfile)) {
                rawProfile = PROFILE_AIFACE;
            }
            p.edit().putBoolean("hq_engine_migrated_v1", true)
                    .putString("profile", rawProfile == null ? PROFILE_AIFACE : rawProfile)
                    .apply();
        }
        s.profile = rawProfile == null ? PROFILE_AIFACE : rawProfile;
        s.endpoint = p.getString("endpoint", TAO_ENDPOINT).trim();
        s.sourceField = nonBlank(p.getString("source_field", TAO_SOURCE_FIELD), TAO_SOURCE_FIELD);
        s.targetField = nonBlank(p.getString("target_field", TAO_TARGET_FIELD), TAO_TARGET_FIELD);
        s.swapMappings = p.getBoolean("swap_mappings", false);
        s.enhancerEnabled = p.getBoolean("enhancer_enabled", true);
        s.nsfwEnabled = p.getBoolean("nsfw_enabled", true);
        s.enhancerField = nullableTrim(p.getString("enhancer_field", TAO_ENHANCER_FIELD));
        s.nsfwField = nullableTrim(p.getString("nsfw_field", TAO_NSFW_FIELD));
        s.extraFormFields = p.getString("extra_form_fields", "");
        s.uploadMimeType = p.getString("upload_mime_type", DEFAULT_UPLOAD_MIME_TYPE).trim();
        s.responseMode = p.getString("response_mode", MODE_AUTO);
        s.resultPath = nonBlank(p.getString("result_path", TAO_RESULT_PATH), TAO_RESULT_PATH);
        s.pollUrlPath = p.getString("poll_url_path", "polling_url").trim();
        s.pollIdPath = p.getString("poll_id_path", "id").trim();
        s.pollUrlTemplate = p.getString("poll_url_template", "").trim();
        s.pollStatusPath = p.getString("poll_status_path", "status").trim();
        s.pollSuccessValue = p.getString("poll_success_value", "success").trim();
        s.pollFailureValues = p.getString("poll_failure_values", "failed,error,cancelled").trim();
        s.pollIntervalSeconds = clamp(parseInt(p.getString("poll_interval", "3"), 3), 1, 60);
        s.maxPolls = clamp(parseInt(p.getString("max_polls", "100"), 100), 1, 1000);
        s.origin = p.getString("origin", TAO_ORIGIN).trim();
        s.userAgent = p.getString("user_agent", DEFAULT_USER_AGENT).trim();
        s.authHeaderName = p.getString("auth_header_name", "").trim();
        s.authHeaderValue = p.getString("auth_header_value", "");
        s.extraHeaders = p.getString("extra_headers", "");
        s.concurrency = clamp(parseInt(p.getString("concurrency", "3"), 3), 1, 6);
        s.retries = clamp(parseInt(p.getString("retries", "1"), 1), 0, 5);
        s.connectTimeoutSeconds = clamp(parseInt(p.getString("connect_timeout", "25"), 25), 5, 180);
        s.readTimeoutSeconds = clamp(parseInt(p.getString("read_timeout", "150"), 150), 15, 900);
        s.outputFolder = sanitizeFolder(p.getString("output_folder", DEFAULT_OUTPUT_FOLDER));
        return s;
    }

    public static Snapshot taoSnapshot(Snapshot base) {
        Snapshot s = batchCopy(base);
        s.profile = PROFILE_TAO;
        s.endpoint = TAO_ENDPOINT;
        s.sourceField = TAO_SOURCE_FIELD;
        s.targetField = TAO_TARGET_FIELD;
        s.swapMappings = false;
        s.enhancerEnabled = base == null || base.enhancerEnabled;
        s.nsfwEnabled = base == null || base.nsfwEnabled;
        s.enhancerField = TAO_ENHANCER_FIELD;
        s.nsfwField = TAO_NSFW_FIELD;
        s.extraFormFields = "";
        s.uploadMimeType = DEFAULT_UPLOAD_MIME_TYPE;
        s.responseMode = MODE_AUTO;
        s.resultPath = TAO_RESULT_PATH;
        s.pollUrlPath = "polling_url";
        s.pollIdPath = "id";
        s.pollUrlTemplate = "";
        s.pollStatusPath = "status";
        s.pollSuccessValue = "success";
        s.pollFailureValues = "failed,error,cancelled";
        s.pollIntervalSeconds = 3;
        s.maxPolls = 100;
        s.origin = TAO_ORIGIN;
        s.userAgent = DEFAULT_USER_AGENT;
        s.authHeaderName = "";
        s.authHeaderValue = "";
        s.extraHeaders = "";
        return s;
    }

    private static Snapshot batchCopy(Snapshot base) {
        Snapshot s = new Snapshot();
        s.concurrency = base == null ? 3 : base.concurrency;
        s.retries = base == null ? 1 : base.retries;
        s.connectTimeoutSeconds = base == null ? 25 : base.connectTimeoutSeconds;
        s.readTimeoutSeconds = base == null ? 150 : base.readTimeoutSeconds;
        s.outputFolder = base == null ? DEFAULT_OUTPUT_FOLDER : base.outputFolder;
        return s;
    }

    public static void reset(Context context) {
        prefs(context).edit().clear().putString("profile", PROFILE_AIFACE)
                .putBoolean("hq_engine_migrated_v1", true).apply();
    }

    public static String sanitizeFolder(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return DEFAULT_OUTPUT_FOLDER;
        }
        value = value.replace('\\', '/');
        String[] segments = value.split("/");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            String clean = segment.replaceAll("[\\x00-\\x1F:*?\"<>|]", "_").trim();
            if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(clean);
        }
        return result.length() == 0 ? DEFAULT_OUTPUT_FOLDER : result.toString();
    }

    public static LinkedHashMap<String, String> parseNameValueLines(String value, char separator) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        String[] lines = value.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int index = trimmed.indexOf(separator);
            if (index <= 0) {
                continue;
            }
            String name = trimmed.substring(0, index).trim();
            String fieldValue = trimmed.substring(index + 1).trim();
            if (!name.isEmpty()) {
                result.put(name, fieldValue);
            }
        }
        return result;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String nullableTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class Snapshot {
        public String profile;
        public String endpoint;
        public String sourceField;
        public String targetField;
        public boolean swapMappings;
        public boolean enhancerEnabled;
        public boolean nsfwEnabled;
        public String enhancerField;
        public String nsfwField;
        public String extraFormFields;
        public String uploadMimeType;
        public String responseMode;
        public String resultPath;
        public String pollUrlPath;
        public String pollStatusPath;
        public String pollIdPath;
        public String pollUrlTemplate;
        public String pollSuccessValue;
        public String pollFailureValues;
        public int pollIntervalSeconds;
        public int maxPolls;
        public String origin;
        public String userAgent;
        public String authHeaderName;
        public String authHeaderValue;
        public String extraHeaders;
        public int concurrency;
        public int retries;
        public int connectTimeoutSeconds;
        public int readTimeoutSeconds;
        public String outputFolder;

        public LinkedHashMap<String, String> formFields() {
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            if (enhancerField != null && !enhancerField.trim().isEmpty()) {
                fields.put(enhancerField.trim(), Boolean.toString(enhancerEnabled));
            }
            if (nsfwField != null && !nsfwField.trim().isEmpty()) {
                fields.put(nsfwField.trim(), Boolean.toString(nsfwEnabled));
            }
            for (Map.Entry<String, String> entry : parseNameValueLines(extraFormFields, '=').entrySet()) {
                fields.put(entry.getKey(), entry.getValue());
            }
            return fields;
        }

        public LinkedHashMap<String, String> headers() {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "*/*");
            if (origin != null && !origin.isEmpty()) {
                headers.put("Origin", origin);
            }
            if (userAgent != null && !userAgent.isEmpty()) {
                headers.put("User-Agent", userAgent);
            }
            if (authHeaderName != null && !authHeaderName.isEmpty()
                    && authHeaderValue != null && !authHeaderValue.isEmpty()) {
                headers.put(authHeaderName, authHeaderValue);
            }
            for (Map.Entry<String, String> entry : parseNameValueLines(extraHeaders, ':').entrySet()) {
                headers.put(entry.getKey(), entry.getValue());
            }
            return headers;
        }
    }
}
