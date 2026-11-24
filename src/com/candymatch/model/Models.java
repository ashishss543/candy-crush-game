package com.candymatch.model;

import java.util.*;

/**
 * Core domain records and DTOs for the Candy Match Saga Java engine.
 */
public final class Models {

    private Models() {}

    public record Pos(int r, int c) {
        public int key(int cols) { return r * cols + c; }
        public List<Integer> toList() { return List.of(r, c); }
    }

    public record Run(String type, String dir, List<Pos> cells) {}

    public record ClearEvent(String kind, int size, Formula formula, String type, int combo) {
        public ClearEvent withCombo(int c) {
            return new ClearEvent(kind, size, formula, type, c);
        }
    }

    public record Spawn(int r, int c, Tile tile) {}

    public record ClearPlan(
            Set<Integer> clear,
            List<Spawn> spawns,
            List<ClearEvent> events,
            Set<String> types,
            int base
    ) {}

    public record ApplyResult(int[][] fall, Set<Integer> born) {}

    public record MoveSimulation(
            Pos a,
            Pos b,
            int score,
            int combo,
            int specials,
            int synth,
            Set<String> types
    ) {}

    public record WorthResult(boolean ok, List<String> reasons, double ratio, int fair) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("reasons", reasons);
            m.put("ratio", Math.round(ratio * 100.0) / 100.0);
            m.put("fair", fair);
            return m;
        }
    }

    public record LevelConfig(int level, int target, int moves) {
        public static LevelConfig forLevel(int n) {
            int lvl = Math.max(1, Math.min(20, n));
            return new LevelConfig(lvl, 1300 + 300 * (lvl - 1), 24 - (lvl - 1) / 4);
        }

        public Map<String, Object> toMap(int stars) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("target", target);
            m.put("moves", moves);
            m.put("stars", stars);
            return m;
        }
    }

    public record ResolutionStep(
            List<List<Map<String, Object>>> popGrid,
            List<Integer> pop,
            String floatText,
            int floatRow,
            int floatCol,
            String sfx,
            int combo,
            List<String> logs,
            Map<String, Object> hud,
            List<List<Map<String, Object>>> afterGrid,
            int[][] fall,
            List<Integer> born
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("popGrid", popGrid);
            m.put("pop", pop);
            m.put("floatText", floatText);
            m.put("floatRow", floatRow);
            m.put("floatCol", floatCol);
            m.put("sfx", sfx);
            m.put("combo", combo);
            m.put("logs", logs);
            m.put("hud", hud);
            m.put("afterGrid", afterGrid);
            m.put("fall", fall);
            m.put("born", born);
            return m;
        }
    }

    public record ResolveOutcome(
            List<ClearEvent> events,
            int gained,
            int combo,
            List<ResolutionStep> steps
    ) {}
}
