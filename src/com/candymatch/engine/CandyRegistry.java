package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Module 1 & 3 Registry: base candies, synthesis formulas, custom rules, icons,
 * and persistent JSON storage (data/store.json).
 */
public final class CandyRegistry {

    public static final List<String> BASE_IDS = List.of("red", "blue", "green", "yellow", "purple", "orange");

    public static final List<String> SYN_COLORS = List.of(
            "#d6336c", "#7a3cff", "#e0a800", "#0fb5a0", "#ff6b3d",
            "#3d8bff", "#b5179e", "#2bb673", "#c9184a", "#5f6fff"
    );

    public static final List<List<String>> ICONS = List.of(
            List.of("🔻", "Ruby Gem"), List.of("🔮", "Crystal Orb"), List.of("⭐", "Star"),
            List.of("💎", "Diamond"), List.of("🌟", "Glow Star"), List.of("✨", "Sparkles"),
            List.of("🍭", "Lollipop"), List.of("🍬", "Wrapped Sweet"), List.of("🍫", "Chocolate"),
            List.of("🧁", "Cupcake"), List.of("🍩", "Donut"), List.of("🍪", "Cookie"),
            List.of("🎂", "Cake"), List.of("🍰", "Cake Slice"), List.of("🍦", "Ice Cream"),
            List.of("🍧", "Shaved Ice"), List.of("💖", "Pink Heart"), List.of("💜", "Purple Heart"),
            List.of("💙", "Blue Heart"), List.of("💚", "Green Heart"), List.of("💛", "Yellow Heart"),
            List.of("🧡", "Orange Heart"), List.of("❤️", "Red Heart"), List.of("🔥", "Flame"),
            List.of("⚡", "Lightning"), List.of("❄️", "Snowflake"), List.of("🌈", "Rainbow"),
            List.of("🌙", "Moon"), List.of("☀️", "Sun"), List.of("🎈", "Balloon"),
            List.of("🎁", "Gift"), List.of("👑", "Crown"), List.of("🪄", "Magic Wand"),
            List.of("🌸", "Blossom"), List.of("🍀", "Clover"), List.of("🦄", "Unicorn")
    );

    private static final List<CandyType> BASE_CANDIES = List.of(
            new CandyType("red", "Red Berry", "🍓", "#e8334a", 30, true, false, null),
            new CandyType("blue", "Blueberry Blue", "🫐", "#2f80ed", 30, true, false, null),
            new CandyType("green", "Apple Green", "🍏", "#35b84b", 30, true, false, null),
            new CandyType("yellow", "Lemon Yellow", "🍋", "#f5c518", 30, true, false, null),
            new CandyType("purple", "Grape Purple", "🍇", "#9b3fe0", 30, true, false, null),
            new CandyType("orange", "Orange Citrus", "🍊", "#f58a1f", 30, true, false, null),
            new CandyType("bomb", "Color Bomb", "🌈", "#ffffff", 50, true, true, null)
    );

    private static final List<Formula> BUILTIN_FORMULAS = List.of(
            new Formula("syn_ruby", "Ruby Candy", "🔻", List.of("red", "red"), 100, true),
            new Formula("syn_amethyst", "Amethyst Orb", "🔮", List.of("purple", "purple"), 120, true),
            new Formula("syn_star", "Super Star", "⭐", List.of("yellow", "red", "syn_ruby"), 150, true)
    );

    private static final List<GameRule> BUILTIN_RULES = List.of(
            new GameRule("r_ruby", "Ruby Combination Bonus",
                    new GameRule.Trigger("synth", "syn_ruby", 0),
                    new GameRule.Reward("points", 200), true),
            new GameRule("r_casc", "Cascade Bonus",
                    new GameRule.Trigger("combo", null, 3),
                    new GameRule.Reward("moves", 1), true)
    );

    private final Path storePath;
    private final Map<String, CandyType> candies = new LinkedHashMap<>();
    private final List<Formula> customFormulas = new ArrayList<>();
    private final List<GameRule> customRules = new ArrayList<>();
    private final List<Formula> allFormulas = new ArrayList<>();
    private final List<GameRule> allRules = new ArrayList<>();
    private final Map<Integer, Integer> clearedLevels = new LinkedHashMap<>();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private boolean soundEnabled = true;

    public CandyRegistry(Path storePath) {
        this.storePath = storePath;
        loadStore();
        rebuildRegistry();
    }

    public synchronized void rebuildRegistry() {
        candies.clear();
        for (CandyType ct : BASE_CANDIES) {
            candies.put(ct.id(), ct);
        }
        allFormulas.clear();
        allFormulas.addAll(BUILTIN_FORMULAS);
        allFormulas.addAll(customFormulas);

        allRules.clear();
        allRules.addAll(BUILTIN_RULES);
        allRules.addAll(customRules);

        for (int i = 0; i < allFormulas.size(); i++) {
            Formula f = allFormulas.get(i);
            String color = SYN_COLORS.get(i % SYN_COLORS.size());
            candies.put(f.id(), new CandyType(
                    f.id(), f.name(), f.icon(), color, f.pts(), false, false, f.ingredients()
            ));
        }
    }

    public synchronized CandyType getCandy(String id) {
        return candies.get(id);
    }

    public synchronized int getCandyPts(String id) {
        CandyType ct = candies.get(id);
        return ct != null ? ct.pts() : 30;
    }

    public synchronized Map<String, CandyType> getCandies() {
        return new LinkedHashMap<>(candies);
    }

    public synchronized List<Formula> getFormulas() {
        return new ArrayList<>(allFormulas);
    }

    public synchronized List<GameRule> getRules() {
        return new ArrayList<>(allRules);
    }

    public synchronized void addCustomFormula(Formula f) {
        customFormulas.add(f);
        rebuildRegistry();
        saveStore();
    }

    public synchronized void addCustomRule(GameRule r) {
        customRules.add(r);
        rebuildRegistry();
        saveStore();
    }

    public synchronized Map<Integer, Integer> getClearedLevels() {
        return new LinkedHashMap<>(clearedLevels);
    }

    public synchronized void recordClearedLevel(int level, int stars) {
        int prev = clearedLevels.getOrDefault(level, 0);
        clearedLevels.put(level, Math.max(prev, stars));
        saveStore();
    }

    public synchronized List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : history) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }

    public synchronized void upsertHistoryRow(Map<String, Object> row) {
        String sid = JsonUtil.asString(row.get("SessionID"), "");
        int idx = -1;
        for (int i = 0; i < history.size(); i++) {
            if (sid.equals(history.get(i).get("SessionID"))) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            history.set(idx, new LinkedHashMap<>(row));
        } else {
            history.add(0, new LinkedHashMap<>(row));
        }
        while (history.size() > 60) {
            history.remove(history.size() - 1);
        }
        saveStore();
    }

    public synchronized boolean isSoundEnabled() {
        return soundEnabled;
    }

    public synchronized void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        saveStore();
    }

    private void loadStore() {
        if (storePath == null || !Files.exists(storePath)) return;
        try {
            String raw = Files.readString(storePath, StandardCharsets.UTF_8);
            if (raw.isBlank()) return;
            Map<String, Object> data = JsonUtil.parseObject(raw);

            soundEnabled = JsonUtil.asBool(data.get("sound"), true);

            for (Object item : JsonUtil.asList(data.get("formulas"))) {
                Map<String, Object> m = JsonUtil.asMap(item);
                String id = JsonUtil.asString(m.get("id"), "");
                String name = JsonUtil.asString(m.get("name"), "");
                String icon = JsonUtil.asString(m.get("icon"), "💎");
                int pts = JsonUtil.asInt(m.get("pts"), 100);
                List<String> ing = new ArrayList<>();
                for (Object x : JsonUtil.asList(m.get("ing"))) {
                    ing.add(String.valueOf(x));
                }
                if (!id.isBlank() && !name.isBlank() && !ing.isEmpty()) {
                    customFormulas.add(new Formula(id, name, icon, ing, pts, false));
                }
            }

            for (Object item : JsonUtil.asList(data.get("rules"))) {
                Map<String, Object> m = JsonUtil.asMap(item);
                String id = JsonUtil.asString(m.get("id"), "");
                String name = JsonUtil.asString(m.get("name"), "");
                Map<String, Object> tm = JsonUtil.asMap(m.get("trig"));
                Map<String, Object> rm = JsonUtil.asMap(m.get("rew"));
                String tKind = JsonUtil.asString(tm.get("kind"), "match");
                String tCandy = tm.get("candy") == null ? null : String.valueOf(tm.get("candy"));
                int tN = JsonUtil.asInt(tm.get("n"), 2);
                String rKind = JsonUtil.asString(rm.get("kind"), "points");
                int rVal = JsonUtil.asInt(rm.get("value"), 100);
                if (!id.isBlank() && !name.isBlank()) {
                    customRules.add(new GameRule(id, name,
                            new GameRule.Trigger(tKind, tCandy, tN),
                            new GameRule.Reward(rKind, rVal), false));
                }
            }

            Map<String, Object> clearedMap = JsonUtil.asMap(data.get("cleared"));
            for (Map.Entry<String, Object> e : clearedMap.entrySet()) {
                try {
                    int lvl = Integer.parseInt(e.getKey());
                    int stars = JsonUtil.asInt(e.getValue(), 0);
                    if (lvl >= 1 && lvl <= 20 && stars > 0) {
                        clearedLevels.put(lvl, stars);
                    }
                } catch (NumberFormatException ignored) {}
            }

            for (Object item : JsonUtil.asList(data.get("history"))) {
                Map<String, Object> row = JsonUtil.asMap(item);
                if (!row.isEmpty()) {
                    history.add(new LinkedHashMap<>(row));
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not load store from " + storePath + ": " + e.getMessage());
        }
    }

    private void saveStore() {
        if (storePath == null) return;
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sound", soundEnabled);

            List<Map<String, Object>> fList = new ArrayList<>();
            for (Formula f : customFormulas) fList.add(f.toMap());
            data.put("formulas", fList);

            List<Map<String, Object>> rList = new ArrayList<>();
            for (GameRule r : customRules) rList.add(r.toMap());
            data.put("rules", rList);

            Map<String, Object> cl = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> e : clearedLevels.entrySet()) {
                cl.put(String.valueOf(e.getKey()), e.getValue());
            }
            data.put("cleared", cl);
            data.put("history", history);

            String json = JsonUtil.stringifyPretty(data);
            Path tmp = storePath.resolveSibling(storePath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: could not save store to " + storePath + ": " + e.getMessage());
        }
    }
}
