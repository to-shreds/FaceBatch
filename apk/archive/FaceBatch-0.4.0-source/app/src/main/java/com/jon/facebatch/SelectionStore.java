package com.jon.facebatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class SelectionStore {
    private static final String PREFS = "facebatch_selections";

    private SelectionStore() {
    }

    public static void save(Context context, String key,
                            LinkedHashMap<String, UriTools.ImageRef> values) {
        JSONArray array = new JSONArray();
        for (UriTools.ImageRef ref : values.values()) {
            try {
                JSONObject object = new JSONObject();
                object.put("uri", ref.uri);
                object.put("name", ref.name);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, array.toString()).apply();
    }

    public static List<UriTools.ImageRef> read(Context context, String key) {
        LinkedHashMap<String, UriTools.ImageRef> values = new LinkedHashMap<>();
        load(context, key, values);
        return new ArrayList<>(values.values());
    }

    public static void load(Context context, String key,
                            LinkedHashMap<String, UriTools.ImageRef> destination) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = preferences.getString(key, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String uri = object.optString("uri", "");
                String name = object.optString("name", "image");
                if (!uri.isEmpty()) {
                    destination.put(uri, new UriTools.ImageRef(uri, name));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
