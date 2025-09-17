package me.sedattr.deluxebazaar.others;

import java.util.HashMap;

public class PlaceholderUtil {
    public HashMap<String, String> placeholders = new HashMap<>();

    public PlaceholderUtil addPlaceholder(String key, String value) {
        if (key == null)
            return this;
        if (value == null)
            return this;

        this.placeholders.put(key, value);
        return this;
    }

    public HashMap<String, String> getPlaceholders() {
        return this.placeholders;
    }
}
