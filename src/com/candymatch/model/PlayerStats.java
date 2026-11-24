package com.candymatch.model;

import java.util.*;

public final class PlayerStats {
    public int score = 0;
    public int valid = 0;
    public int invalid = 0;
    public final Map<Integer, Integer> matchCounts = new LinkedHashMap<>();
    public int matches = 0;
    public int maxCombo = 1;
    public int moves = 0;

    public PlayerStats() {
        matchCounts.put(2, 0);
        matchCounts.put(3, 0);
        matchCounts.put(4, 0);
        matchCounts.put(5, 0);
    }

    public void incrementMatch(int rawSize) {
        int sz = Math.max(2, Math.min(rawSize, 5));
        matchCounts.put(sz, matchCounts.getOrDefault(sz, 0) + 1);
        matches++;
    }

    public int accuracy() {
        int total = valid + invalid;
        return total == 0 ? 100 : (int) Math.round((valid * 100.0) / total);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("valid", valid);
        m.put("invalid", invalid);
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("2", matchCounts.getOrDefault(2, 0));
        mc.put("3", matchCounts.getOrDefault(3, 0));
        mc.put("4", matchCounts.getOrDefault(4, 0));
        mc.put("5", matchCounts.getOrDefault(5, 0));
        m.put("m", mc);
        m.put("matches", matches);
        m.put("maxCombo", maxCombo);
        m.put("moves", moves);
        m.put("accuracy", accuracy());
        return m;
    }
}
