package com.jon.facebatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public final class BatchStateStore {
    private static final String PREFS = "facebatch_batch_state";

    private BatchStateStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static State load(Context context) {
        SharedPreferences p = prefs(context);
        State s = new State();
        s.running = p.getBoolean("running", false);
        s.cancelled = p.getBoolean("cancelled", false);
        s.total = p.getInt("total", 0);
        s.completed = p.getInt("completed", 0);
        s.failed = p.getInt("failed", 0);
        s.current = p.getString("current", "");
        s.message = p.getString("message", "Choose images to begin.");
        s.outputFolder = p.getString("output_folder", AppSettings.DEFAULT_OUTPUT_FOLDER);
        s.failedJobsJson = p.getString("failed_jobs", "[]");
        s.lastError = p.getString("last_error", "");
        return s;
    }

    public static void save(Context context, State s) {
        prefs(context).edit()
                .putBoolean("running", s.running)
                .putBoolean("cancelled", s.cancelled)
                .putInt("total", s.total)
                .putInt("completed", s.completed)
                .putInt("failed", s.failed)
                .putString("current", s.current == null ? "" : s.current)
                .putString("message", s.message == null ? "" : s.message)
                .putString("output_folder", s.outputFolder == null ? AppSettings.DEFAULT_OUTPUT_FOLDER : s.outputFolder)
                .putString("failed_jobs", s.failedJobsJson == null ? "[]" : s.failedJobsJson)
                .putString("last_error", s.lastError == null ? "" : s.lastError)
                .apply();
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static String jobsToJson(List<SwapJob> jobs) {
        JSONArray array = new JSONArray();
        for (SwapJob job : jobs) {
            try {
                array.put(job.toJson());
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    public static List<SwapJob> jobsFromJson(String json) {
        ArrayList<SwapJob> jobs = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                SwapJob job = SwapJob.fromJson(array.getJSONObject(i));
                if (!job.sourceUri.isEmpty() && !job.targetUri.isEmpty()) {
                    jobs.add(job);
                }
            }
        } catch (Exception ignored) {
        }
        return jobs;
    }

    public static final class State {
        public boolean running;
        public boolean cancelled;
        public int total;
        public int completed;
        public int failed;
        public String current;
        public String message;
        public String outputFolder;
        public String failedJobsJson;
        public String lastError;
    }
}
