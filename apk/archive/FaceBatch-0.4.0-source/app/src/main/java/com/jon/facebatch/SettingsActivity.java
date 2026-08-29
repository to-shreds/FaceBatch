package com.jon.facebatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private String profile = AppSettings.PROFILE_AIFACE;

    private TextView profileStatus;
    private EditText endpoint;
    private EditText sourceField;
    private EditText targetField;
    private CheckBox swapMappings;
    private CheckBox enhancerEnabled;
    private CheckBox nsfwEnabled;
    private EditText enhancerField;
    private EditText nsfwField;
    private EditText extraFormFields;
    private EditText uploadMimeType;
    private EditText officialApiKey;

    private Spinner responseMode;
    private EditText resultPath;
    private EditText pollUrlPath;
    private EditText pollStatusPath;
    private EditText pollIdPath;
    private EditText pollUrlTemplate;
    private EditText pollSuccessValue;
    private EditText pollFailureValues;
    private EditText pollInterval;
    private EditText maxPolls;

    private EditText origin;
    private EditText userAgent;
    private EditText authHeaderName;
    private EditText authHeaderValue;
    private EditText extraHeaders;

    private Spinner concurrency;
    private Spinner retries;
    private EditText connectTimeout;
    private EditText readTimeout;
    private EditText outputFolder;
    private LinearLayout advanced;
    private Button advancedToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.prepareWindow(this);
        setContentView(buildContent());
        load(AppSettings.load(this));
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = Ui.row(this, Gravity.CENTER_VERTICAL);
        Button back = Ui.neutralButton(this, "Back");
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
        header.addView(back, backLp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        TextView title = Ui.text(this, "Settings", 25, Ui.INK, true);
        LinearLayout.LayoutParams titleLp = Ui.weighted(1);
        titleLp.leftMargin = Ui.dp(this, 14);
        header.addView(title, titleLp);
        content.addView(header, Ui.matchWrap());

        TextView intro = Ui.text(this,
                "AIFaceSwap HQ is the higher-quality no-key engine used by Face Over. Auto follows Face Over's routing. FJoy and Tao remain available as alternatives.",
                15, Ui.MUTED, false);
        LinearLayout.LayoutParams introLp = Ui.matchWrap();
        introLp.topMargin = Ui.dp(this, 18);
        content.addView(intro, introLp);

        content.addView(buildBatchCard(), top(18));
        content.addView(buildApiCard(), top(16));
        content.addView(buildAdvancedCard(), top(16));

        Button save = Ui.primaryButton(this, "Save settings");
        LinearLayout.LayoutParams saveLp = Ui.matchWrap();
        saveLp.topMargin = Ui.dp(this, 18);
        saveLp.height = Ui.dp(this, 56);
        content.addView(save, saveLp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndClose();
            }
        });

        Button reset = Ui.dangerButton(this, "Reset all settings");
        LinearLayout.LayoutParams resetLp = Ui.matchWrap();
        resetLp.topMargin = Ui.dp(this, 10);
        resetLp.height = Ui.dp(this, 48);
        content.addView(reset, resetLp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Reset FaceBatch settings?")
                        .setMessage("This restores AIFaceSwap HQ and the standard batch defaults.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                AppSettings.reset(SettingsActivity.this);
                                load(AppSettings.load(SettingsActivity.this));
                                Toast.makeText(SettingsActivity.this, "Defaults restored", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
            }
        });

        TextView footnote = Ui.text(this,
                "The separate TiemAnhAI developer API is available only under Advanced and is clearly labeled as requiring its own key. It is not the service used by the regular Face Over app.",
                12, Ui.MUTED, false);
        LinearLayout.LayoutParams footLp = Ui.matchWrap();
        footLp.topMargin = Ui.dp(this, 16);
        content.addView(footnote, footLp);
        return scroll;
    }

    private LinearLayout buildBatchCard() {
        LinearLayout card = (LinearLayout) Ui.card(this);
        card.addView(Ui.text(this, "BATCH AND OUTPUT", 12, Ui.ACCENT, true));
        TextView heading = Ui.text(this, "How FaceBatch should run", 20, Ui.INK, true);
        card.addView(heading, top(7));

        concurrency = spinner(new String[]{"1 at a time", "2 at a time", "3 at a time",
                "4 at a time", "5 at a time", "6 at a time"});
        addField(card, "Concurrent requests", concurrency,
                "AIFaceSwap HQ, Face Over Auto, FJoy, and Tao are automatically serialized for reliability. This setting applies to Custom and external developer profiles.");

        retries = spinner(new String[]{"No retries", "Retry once", "Retry twice",
                "Retry 3 times", "Retry 4 times", "Retry 5 times"});
        addField(card, "Automatic retries", retries, null);

        LinearLayout timeoutRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        connectTimeout = textField(false, true, 1);
        readTimeout = textField(false, true, 1);
        timeoutRow.addView(connectTimeout, Ui.weighted(1));
        LinearLayout.LayoutParams readLp = Ui.weighted(1);
        readLp.leftMargin = Ui.dp(this, 9);
        timeoutRow.addView(readTimeout, readLp);
        addField(card, "Connect timeout and read timeout, in seconds", timeoutRow, null);

        outputFolder = textField(false, false, 1);
        addField(card, "Downloads subfolder", outputFolder,
                "Results save automatically under Downloads. Slashes create nested folders.");
        return card;
    }

    private LinearLayout buildApiCard() {
        LinearLayout card = (LinearLayout) Ui.card(this);
        card.addView(Ui.text(this, "FACE-SWAP ENGINE", 12, Ui.ACCENT, true));
        TextView heading = Ui.text(this, "Choose engine", 20, Ui.INK, true);
        card.addView(heading, top(7));

        profileStatus = Ui.text(this, "", 13, Ui.MUTED, false);
        card.addView(profileStatus, top(6));

        LinearLayout firstRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        Button hq = Ui.secondaryButton(this, "AIFaceSwap HQ");
        firstRow.addView(hq, Ui.weighted(1));
        Button auto = Ui.secondaryButton(this, "Face Over Auto");
        LinearLayout.LayoutParams autoLp = Ui.weighted(1);
        autoLp.leftMargin = Ui.dp(this, 8);
        firstRow.addView(auto, autoLp);
        card.addView(firstRow, top(13));

        LinearLayout secondRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        Button fjoy = Ui.neutralButton(this, "FJoy / Magicut");
        secondRow.addView(fjoy, Ui.weighted(1));
        Button tao = Ui.neutralButton(this, "TaoAnhDep direct");
        LinearLayout.LayoutParams taoLp = Ui.weighted(1);
        taoLp.leftMargin = Ui.dp(this, 8);
        secondRow.addView(tao, taoLp);
        card.addView(secondRow, top(8));

        Button custom = Ui.neutralButton(this, "Custom API");
        LinearLayout.LayoutParams customLp = Ui.matchWrap();
        customLp.topMargin = Ui.dp(this, 8);
        customLp.height = Ui.dp(this, 46);
        card.addView(custom, customLp);

        hq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                profile = AppSettings.PROFILE_AIFACE;
                updateProfileStatus();
            }
        });
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                profile = AppSettings.PROFILE_AUTO;
                updateProfileStatus();
            }
        });
        fjoy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                profile = AppSettings.PROFILE_FJOY;
                updateProfileStatus();
            }
        });
        tao.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadTaoApiDefaults();
            }
        });
        custom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                profile = AppSettings.PROFILE_CUSTOM;
                updateProfileStatus();
                showAdvanced(true);
            }
        });

        endpoint = textField(false, false, 1);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addField(card, "POST endpoint", endpoint,
                "Used only by TaoAnhDep and Custom. The built-in engines use their own workflows.");

        LinearLayout fieldRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        sourceField = textField(false, false, 1);
        targetField = textField(false, false, 1);
        fieldRow.addView(sourceField, Ui.weighted(1));
        LinearLayout.LayoutParams targetLp = Ui.weighted(1);
        targetLp.leftMargin = Ui.dp(this, 9);
        fieldRow.addView(targetField, targetLp);
        addField(card, "Donor-face field and target-image field", fieldRow,
                "Used only by Tao or Custom services.");

        swapMappings = checkBox("Reverse the two file-field assignments");
        card.addView(swapMappings, top(11));

        enhancerEnabled = checkBox("Enable enhancement where supported");
        card.addView(enhancerEnabled, top(4));
        nsfwEnabled = checkBox("Enable the Tao API safety check");
        card.addView(nsfwEnabled, top(2));

        LinearLayout optionNames = Ui.row(this, Gravity.CENTER_VERTICAL);
        enhancerField = textField(false, false, 1);
        nsfwField = textField(false, false, 1);
        optionNames.addView(enhancerField, Ui.weighted(1));
        LinearLayout.LayoutParams nsfwLp = Ui.weighted(1);
        nsfwLp.leftMargin = Ui.dp(this, 9);
        optionNames.addView(nsfwField, nsfwLp);
        addField(card, "Enhancer field and safety-check field", optionNames,
                "Used only by Tao or Custom mode.");
        return card;
    }

    private LinearLayout buildAdvancedCard() {
        LinearLayout card = (LinearLayout) Ui.card(this);
        LinearLayout titleRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(Ui.text(this, "ADVANCED", 12, Ui.ACCENT, true));
        titleBlock.addView(Ui.text(this, "Response, authentication, and headers", 19, Ui.INK, true), top(5));
        titleRow.addView(titleBlock, Ui.weighted(1));
        advancedToggle = Ui.neutralButton(this, "Show");
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
        titleRow.addView(advancedToggle, toggleLp);
        card.addView(titleRow, Ui.matchWrap());

        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        card.addView(advanced, top(8));
        advancedToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAdvanced(advanced.getVisibility() != View.VISIBLE);
            }
        });

        Button publicApi = Ui.neutralButton(this, "Load external developer API (key required)");
        LinearLayout.LayoutParams publicApiLp = Ui.matchWrap();
        publicApiLp.topMargin = Ui.dp(this, 8);
        publicApiLp.height = Ui.dp(this, 48);
        advanced.addView(publicApi, publicApiLp);
        publicApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadPublicApiDefaults();
            }
        });

        officialApiKey = textField(false, false, 1);
        addField(advanced, "External developer API key", officialApiKey,
                "Only the separate external developer API uses this field. The regular Face Over backends do not require it.");

        responseMode = spinner(new String[]{
                "Auto-detect current-style response",
                "Require configured JSON path",
                "Initial response returns polling URL"
        });
        addField(advanced, "Response workflow", responseMode, null);

        resultPath = textField(false, false, 1);
        addField(advanced, "Result image JSON path", resultPath,
                "Dot notation works, for example result.image or data.output_url.");

        LinearLayout pollingOne = Ui.row(this, Gravity.CENTER_VERTICAL);
        pollUrlPath = textField(false, false, 1);
        pollStatusPath = textField(false, false, 1);
        pollingOne.addView(pollUrlPath, Ui.weighted(1));
        LinearLayout.LayoutParams statusLp = Ui.weighted(1);
        statusLp.leftMargin = Ui.dp(this, 9);
        pollingOne.addView(pollStatusPath, statusLp);
        addField(advanced, "Polling URL path and polling status path", pollingOne, null);

        LinearLayout pollingFallback = Ui.row(this, Gravity.CENTER_VERTICAL);
        pollIdPath = textField(false, false, 1);
        pollUrlTemplate = textField(false, false, 1);
        pollingFallback.addView(pollIdPath, Ui.weighted(1));
        LinearLayout.LayoutParams templateLp = Ui.weighted(1);
        templateLp.leftMargin = Ui.dp(this, 9);
        pollingFallback.addView(pollUrlTemplate, templateLp);
        addField(advanced, "Polling id path and polling URL template", pollingFallback,
                "If the first response returns a job id but no poll URL, the template can build it. Use {id} where the job id should go.");

        LinearLayout pollingTwo = Ui.row(this, Gravity.CENTER_VERTICAL);
        pollSuccessValue = textField(false, false, 1);
        pollFailureValues = textField(false, false, 1);
        pollingTwo.addView(pollSuccessValue, Ui.weighted(1));
        LinearLayout.LayoutParams failLp = Ui.weighted(1);
        failLp.leftMargin = Ui.dp(this, 9);
        pollingTwo.addView(pollFailureValues, failLp);
        addField(advanced, "Success value and comma-separated failure values", pollingTwo, null);

        LinearLayout pollingThree = Ui.row(this, Gravity.CENTER_VERTICAL);
        pollInterval = textField(false, true, 1);
        maxPolls = textField(false, true, 1);
        pollingThree.addView(pollInterval, Ui.weighted(1));
        LinearLayout.LayoutParams maxLp = Ui.weighted(1);
        maxLp.leftMargin = Ui.dp(this, 9);
        pollingThree.addView(maxPolls, maxLp);
        addField(advanced, "Seconds between checks and maximum number of checks", pollingThree, null);

        authHeaderName = textField(false, false, 1);
        authHeaderValue = textField(false, false, 1);
        LinearLayout authRow = Ui.row(this, Gravity.CENTER_VERTICAL);
        authRow.addView(authHeaderName, Ui.weighted(1));
        LinearLayout.LayoutParams authValueLp = Ui.weighted(1);
        authValueLp.leftMargin = Ui.dp(this, 9);
        authRow.addView(authHeaderValue, authValueLp);
        addField(advanced, "Optional authentication header name and value", authRow,
                "Example: Authorization and Bearer your-token. Leave both blank for no authentication.");

        origin = textField(false, false, 1);
        addField(advanced, "Origin header", origin, null);
        userAgent = textField(true, false, 2);
        addField(advanced, "User-Agent", userAgent, null);
        uploadMimeType = textField(false, false, 1);
        addField(advanced, "Multipart image MIME type", uploadMimeType,
                "The built-in profile uses image/jpeg, matching the original app. Enter auto to use each file's detected type.");
        extraHeaders = textField(true, false, 3);
        addField(advanced, "Extra headers", extraHeaders,
                "One per line as Header: value");
        extraFormFields = textField(true, false, 3);
        addField(advanced, "Extra multipart text fields", extraFormFields,
                "One per line as key=value. This can also carry an API key if a service expects it as a form field.");
        return card;
    }

    private void load(AppSettings.Snapshot s) {
        profile = s.profile == null ? AppSettings.PROFILE_AUTO : s.profile;
        endpoint.setText(s.endpoint);
        sourceField.setText(s.sourceField);
        targetField.setText(s.targetField);
        swapMappings.setChecked(s.swapMappings);
        enhancerEnabled.setChecked(s.enhancerEnabled);
        nsfwEnabled.setChecked(s.nsfwEnabled);
        enhancerField.setText(s.enhancerField);
        nsfwField.setText(s.nsfwField);
        extraFormFields.setText(s.extraFormFields);
        uploadMimeType.setText(s.uploadMimeType);
        officialApiKey.setText(apiKeyDisplay(s.authHeaderName, s.authHeaderValue));

        responseMode.setSelection(modePosition(s.responseMode));
        resultPath.setText(s.resultPath);
        pollUrlPath.setText(s.pollUrlPath);
        pollStatusPath.setText(s.pollStatusPath);
        pollIdPath.setText(s.pollIdPath);
        pollUrlTemplate.setText(s.pollUrlTemplate);
        pollSuccessValue.setText(s.pollSuccessValue);
        pollFailureValues.setText(s.pollFailureValues);
        pollInterval.setText(String.valueOf(s.pollIntervalSeconds));
        maxPolls.setText(String.valueOf(s.maxPolls));

        origin.setText(s.origin);
        userAgent.setText(s.userAgent);
        authHeaderName.setText(s.authHeaderName);
        authHeaderValue.setText(s.authHeaderValue);
        extraHeaders.setText(s.extraHeaders);

        concurrency.setSelection(Math.max(0, Math.min(5, s.concurrency - 1)));
        retries.setSelection(Math.max(0, Math.min(5, s.retries)));
        connectTimeout.setText(String.valueOf(s.connectTimeoutSeconds));
        readTimeout.setText(String.valueOf(s.readTimeoutSeconds));
        outputFolder.setText(s.outputFolder);
        updateProfileStatus();
    }

    private void loadTaoApiDefaults() {
        profile = AppSettings.PROFILE_TAO;
        endpoint.setText(AppSettings.TAO_ENDPOINT);
        sourceField.setText(AppSettings.TAO_SOURCE_FIELD);
        targetField.setText(AppSettings.TAO_TARGET_FIELD);
        swapMappings.setChecked(false);
        enhancerEnabled.setChecked(true);
        nsfwEnabled.setChecked(true);
        enhancerField.setText(AppSettings.TAO_ENHANCER_FIELD);
        nsfwField.setText(AppSettings.TAO_NSFW_FIELD);
        responseMode.setSelection(0);
        resultPath.setText(AppSettings.TAO_RESULT_PATH);
        pollUrlPath.setText("polling_url");
        pollStatusPath.setText("status");
        pollIdPath.setText("id");
        pollUrlTemplate.setText("");
        pollSuccessValue.setText("success");
        pollFailureValues.setText("failed,error,cancelled");
        pollInterval.setText("3");
        maxPolls.setText("100");
        origin.setText(AppSettings.TAO_ORIGIN);
        userAgent.setText(AppSettings.DEFAULT_USER_AGENT);
        authHeaderName.setText("");
        authHeaderValue.setText("");
        officialApiKey.setText("");
        extraHeaders.setText("");
        extraFormFields.setText("");
        uploadMimeType.setText(AppSettings.DEFAULT_UPLOAD_MIME_TYPE);
        updateProfileStatus();
        Toast.makeText(this, "TaoAnhDep profile loaded", Toast.LENGTH_SHORT).show();
    }

    private void loadPublicApiDefaults() {
        profile = AppSettings.PROFILE_PUBLIC_KEY;
        endpoint.setText(AppSettings.PUBLIC_KEY_ENDPOINT);
        sourceField.setText(AppSettings.PUBLIC_KEY_SOURCE_FIELD);
        targetField.setText(AppSettings.PUBLIC_KEY_TARGET_FIELD);
        swapMappings.setChecked(false);
        enhancerEnabled.setChecked(true);
        nsfwEnabled.setChecked(false);
        enhancerField.setText("enhancer");
        nsfwField.setText("");
        responseMode.setSelection(2);
        resultPath.setText(AppSettings.PUBLIC_KEY_RESULT_PATH);
        pollUrlPath.setText(AppSettings.PUBLIC_KEY_POLL_URL_PATH);
        pollStatusPath.setText(AppSettings.PUBLIC_KEY_POLL_STATUS_PATH);
        pollIdPath.setText(AppSettings.PUBLIC_KEY_POLL_ID_PATH);
        pollUrlTemplate.setText(AppSettings.PUBLIC_KEY_POLL_URL_TEMPLATE);
        pollSuccessValue.setText(AppSettings.PUBLIC_KEY_POLL_SUCCESS_VALUE);
        pollFailureValues.setText(AppSettings.PUBLIC_KEY_POLL_FAILURE_VALUES);
        pollInterval.setText("2");
        maxPolls.setText("180");
        origin.setText("");
        userAgent.setText(AppSettings.DEFAULT_USER_AGENT);
        authHeaderName.setText(AppSettings.PUBLIC_KEY_AUTH_HEADER_NAME);
        authHeaderValue.setText("");
        extraHeaders.setText("");
        extraFormFields.setText(AppSettings.PUBLIC_KEY_EXTRA_FORM_FIELDS);
        uploadMimeType.setText("auto");
        updateProfileStatus();
        showAdvanced(true);
        Toast.makeText(this, "External developer API profile loaded", Toast.LENGTH_SHORT).show();
    }

    private void saveAndClose() {
        String endpointValue = value(endpoint);
        boolean genericProfile = AppSettings.PROFILE_TAO.equals(profile)
                || AppSettings.PROFILE_PUBLIC_KEY.equals(profile)
                || AppSettings.PROFILE_CUSTOM.equals(profile);
        if (genericProfile && !endpointValue.startsWith("https://")) {
            showError("The endpoint must begin with https://");
            return;
        }
        if (genericProfile && (value(sourceField).isEmpty() || value(targetField).isEmpty())) {
            showError("Both image field names are required.");
            return;
        }
        if (genericProfile && value(resultPath).isEmpty()) {
            showError("The result image JSON path is required.");
            return;
        }
        if (endpointValue.isEmpty()) endpointValue = AppSettings.TAO_ENDPOINT;

        String savedAuthHeaderName = value(authHeaderName);
        String savedAuthHeaderValue = value(authHeaderValue);
        if (AppSettings.PROFILE_PUBLIC_KEY.equals(profile) && !value(officialApiKey).isEmpty()) {
            savedAuthHeaderName = AppSettings.PUBLIC_KEY_AUTH_HEADER_NAME;
            savedAuthHeaderValue = normalizedPublicApiAuthValue(value(officialApiKey));
        }

        SharedPreferences.Editor e = AppSettings.prefs(this).edit();
        e.putString("profile", profile);
        e.putString("endpoint", endpointValue);
        e.putString("source_field", value(sourceField));
        e.putString("target_field", value(targetField));
        e.putBoolean("swap_mappings", swapMappings.isChecked());
        e.putBoolean("enhancer_enabled", enhancerEnabled.isChecked());
        e.putBoolean("nsfw_enabled", nsfwEnabled.isChecked());
        e.putString("enhancer_field", value(enhancerField));
        e.putString("nsfw_field", value(nsfwField));
        e.putString("extra_form_fields", value(extraFormFields));
        e.putString("upload_mime_type", valueOr(uploadMimeType, AppSettings.DEFAULT_UPLOAD_MIME_TYPE));

        e.putString("response_mode", positionMode(responseMode.getSelectedItemPosition()));
        e.putString("result_path", value(resultPath));
        e.putString("poll_url_path", value(pollUrlPath));
        e.putString("poll_status_path", value(pollStatusPath));
        e.putString("poll_id_path", value(pollIdPath));
        e.putString("poll_url_template", value(pollUrlTemplate));
        e.putString("poll_success_value", value(pollSuccessValue));
        e.putString("poll_failure_values", value(pollFailureValues));
        e.putString("poll_interval", valueOr(pollInterval, "3"));
        e.putString("max_polls", valueOr(maxPolls, "100"));

        e.putString("origin", value(origin));
        e.putString("user_agent", value(userAgent));
        e.putString("auth_header_name", savedAuthHeaderName);
        e.putString("auth_header_value", savedAuthHeaderValue);
        e.putString("extra_headers", value(extraHeaders));

        e.putString("concurrency", String.valueOf(concurrency.getSelectedItemPosition() + 1));
        e.putString("retries", String.valueOf(retries.getSelectedItemPosition()));
        e.putString("connect_timeout", valueOr(connectTimeout, "25"));
        e.putString("read_timeout", valueOr(readTimeout, "150"));
        e.putString("output_folder", AppSettings.sanitizeFolder(value(outputFolder)));
        e.apply();
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showAdvanced(boolean show) {
        advanced.setVisibility(show ? View.VISIBLE : View.GONE);
        advancedToggle.setText(show ? "Hide" : "Show");
    }

    private void updateProfileStatus() {
        if (AppSettings.PROFILE_AIFACE.equals(profile)) {
            profileStatus.setText("AIFaceSwap HQ selected. Higher-quality no-key Face Over engine.");
            profileStatus.setTextColor(Ui.SUCCESS);
        } else if (AppSettings.PROFILE_AUTO.equals(profile)) {
            String last = AppSettings.prefs(this).getString("last_engine", "");
            profileStatus.setText(last == null || last.isEmpty()
                    ? "Face Over Auto selected. Uses Face Over's current backend route."
                    : "Face Over Auto selected. Last engine: " + last);
            profileStatus.setTextColor(Ui.SUCCESS);
        } else if (AppSettings.PROFILE_FJOY.equals(profile)) {
            profileStatus.setText("FJoy / Magicut selected. Fast no-key fallback with lower output quality.");
            profileStatus.setTextColor(Ui.WARNING);
        } else if (AppSettings.PROFILE_TAO.equals(profile)) {
            profileStatus.setText("TaoAnhDep selected directly. No key, but direct calls may be rate-limited.");
            profileStatus.setTextColor(Ui.WARNING);
        } else if (AppSettings.PROFILE_PUBLIC_KEY.equals(profile)) {
            profileStatus.setText("External developer API selected. This separate service requires its own API key.");
            profileStatus.setTextColor(Ui.ACCENT);
        } else {
            profileStatus.setText("Custom profile selected. Review the advanced controls before running a batch.");
            profileStatus.setTextColor(Ui.WARNING);
        }
    }

    private EditText textField(boolean multiline, boolean number, int minLines) {
        EditText field = new EditText(this);
        field.setTextColor(Ui.INK);
        field.setHintTextColor(Ui.MUTED);
        field.setTextSize(14);
        field.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        field.setBackground(Ui.outlined(ColorCompat.FIELD, Ui.BORDER, 12, this));
        field.setSingleLine(!multiline);
        field.setMinLines(minLines);
        field.setGravity(multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        if (number) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else if (multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return field;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(Ui.dp(this, 50));
        spinner.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        spinner.setBackground(Ui.outlined(ColorCompat.FIELD, Ui.BORDER, 12, this));
        return spinner;
    }

    private CheckBox checkBox(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextSize(14);
        box.setTextColor(Ui.INK);
        if (Build.VERSION.SDK_INT >= 21) {
            box.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Ui.ACCENT, Ui.MUTED}));
        }
        return box;
    }

    private void addField(LinearLayout parent, String label, View field, String help) {
        TextView title = Ui.text(this, label, 12, Ui.MUTED, true);
        parent.addView(title, top(13));
        parent.addView(field, top(5));
        if (help != null && !help.isEmpty()) {
            TextView note = Ui.text(this, help, 11.5f, Ui.MUTED, false);
            parent.addView(note, top(5));
        }
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private static int modePosition(String mode) {
        if (AppSettings.MODE_JSON.equals(mode)) return 1;
        if (AppSettings.MODE_POLLING.equals(mode)) return 2;
        return 0;
    }

    private static String positionMode(int position) {
        if (position == 1) return AppSettings.MODE_JSON;
        if (position == 2) return AppSettings.MODE_POLLING;
        return AppSettings.MODE_AUTO;
    }

    private static String value(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private static String valueOr(EditText field, String fallback) {
        String value = value(field);
        return value.isEmpty() ? fallback : value;
    }

    private static String apiKeyDisplay(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) return "";
        if (!AppSettings.PUBLIC_KEY_AUTH_HEADER_NAME.equalsIgnoreCase(headerName.trim())) return "";
        String trimmed = headerValue.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static String normalizedPublicApiAuthValue(String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed;
        }
        return "Bearer " + trimmed;
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Check settings")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static final class ColorCompat {
        static final int FIELD = 0xFFFBFAF8;
    }
}
