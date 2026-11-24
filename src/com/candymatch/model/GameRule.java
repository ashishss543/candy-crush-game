package com.candymatch.model;

import java.util.*;

public record GameRule(
        String id,
        String name,
        Trigger trigger,
        Reward reward,
        boolean builtin
) {
    public record Trigger(String kind, String candy, int n) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("candy", candy);
            m.put("n", n);
            return m;
        }
    }

    public record Reward(String kind, int value) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("value", value);
            return m;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("trig", trigger.toMap());
        m.put("rew", reward.toMap());
        m.put("builtin", builtin);
        return m;
    }
}
