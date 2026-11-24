package com.candymatch.model;

import java.util.*;

public record Formula(
        String id,
        String name,
        String icon,
        List<String> ingredients,
        int pts,
        boolean builtin
) {
    public static String formulaKey(List<String> ing) {
        List<String> sorted = new ArrayList<>(ing);
        Collections.sort(sorted);
        return String.join("+", sorted);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("icon", icon);
        m.put("ing", ingredients);
        m.put("pts", pts);
        m.put("builtin", builtin);
        return m;
    }
}
