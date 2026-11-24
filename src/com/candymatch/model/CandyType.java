package com.candymatch.model;

import java.util.*;

public record CandyType(
        String id,
        String name,
        String icon,
        String color,
        int pts,
        boolean base,
        boolean hidden,
        List<String> formula
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("color", color);
        m.put("pts", pts);
        m.put("base", base);
        m.put("hidden", hidden);
        if (formula != null) {
            m.put("formula", formula);
        }
        return m;
    }
}
