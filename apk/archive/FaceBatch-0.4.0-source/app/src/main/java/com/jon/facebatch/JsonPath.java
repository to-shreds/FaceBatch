package com.jon.facebatch;

import org.json.JSONArray;
import org.json.JSONObject;

public final class JsonPath {
    private JsonPath() {
    }

    public static Object read(Object root, String path) throws Exception {
        if (root == null) {
            return null;
        }
        if (path == null || path.trim().isEmpty()) {
            return root;
        }
        Object current = root;
        String expression = path.trim();
        int index = 0;
        StringBuilder key = new StringBuilder();
        while (index <= expression.length()) {
            char ch = index < expression.length() ? expression.charAt(index) : '.';
            if (ch == '.' || ch == '[' || index == expression.length()) {
                if (key.length() > 0) {
                    if (!(current instanceof JSONObject)) {
                        return null;
                    }
                    current = ((JSONObject) current).opt(key.toString());
                    if (current == JSONObject.NULL) {
                        return null;
                    }
                    key.setLength(0);
                }
                if (ch == '[') {
                    int close = expression.indexOf(']', index + 1);
                    if (close < 0 || !(current instanceof JSONArray)) {
                        return null;
                    }
                    int arrayIndex = Integer.parseInt(expression.substring(index + 1, close).trim());
                    JSONArray array = (JSONArray) current;
                    if (arrayIndex < 0 || arrayIndex >= array.length()) {
                        return null;
                    }
                    current = array.opt(arrayIndex);
                    if (current == JSONObject.NULL) {
                        return null;
                    }
                    index = close;
                }
            } else {
                key.append(ch);
            }
            index++;
        }
        return current;
    }

    public static String readString(Object root, String path) throws Exception {
        Object value = read(root, path);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return String.valueOf(value);
    }
}
