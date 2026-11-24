package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.model.Models.*;
import com.candymatch.util.JsonUtil;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level Game Service orchestrating sessions, swaps, cascades, AI turns,
 * synthesis creation, custom rules, levels, and analytics.
 */
public final class GameService {

    private final CandyRegistry registry;
    private final BoardEngine board;
    private final RuleEngine ruleEngine;
    private final WorthEvaluator worthEvaluator;
    private final AiEngine aiEngine;
    private final AnalyticsService analytics;
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    public GameService(CandyRegistry registry) {
        this.registry = registry;
        this.board = new BoardEngine(registry);
        this.ruleEngine = new RuleEngine(registry);
        this.worthEvaluator = new WorthEvaluator(registry);
        this.aiEngine = new AiEngine(registry, board);
        this.analytics = new AnalyticsService(registry);
    }

    public CandyRegistry getRegistry() { return registry; }
    public BoardEngine getBoard() { return board; }
    public WorthEvaluator getWorthEvaluator() { return worthEvaluator; }
    public AiEngine getAiEngine() { return aiEngine; }
    public AnalyticsService getAnalytics() { return analytics; }

    public synchronized Map<String, Object> getConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sound", registry.isSoundEnabled());
        out.put("icons", CandyRegistry.ICONS);
        out.put("baseIds", CandyRegistry.BASE_IDS);
        out.put("candies", serializeCandies());
        out.put("formulas", serializeFormulas());
        out.put("rules", serializeRules());
        out.put("levels", getLevelsList());
        return out;
    }

    public synchronized List<Map<String, Object>> getLevelsList() {
        Map<Integer, Integer> cleared = registry.getClearedLevels();
        List<Map<String, Object>> levels = new ArrayList<>(20);
        for (int n = 1; n <= 20; n++) {
            levels.add(LevelConfig.forLevel(n).toMap(cleared.getOrDefault(n, 0)));
        }
        return levels;
    }

    public synchronized Map<String, Object> setSound(boolean enabled) {
        registry.setSoundEnabled(enabled);
        return JsonUtil.mapOf("sound", registry.isSoundEnabled());
    }

    public synchronized Map<String, Object> startGame(String rawName, String mode, int level, String prevSessionId) {
        if (prevSessionId != null && !prevSessionId.isBlank()) {
            abandonSession(prevSessionId);
        }
        String name = (rawName == null ? "" : rawName).trim().replace("<", "").replace(">", "");
        if (name.isEmpty()) name = "Player1";
        if (name.length() > 20) name = name.substring(0, 20);
        if (level < 1 || level > 20) level = 1;

        GameSession s = new GameSession(name, mode, level);
        s.grid = board.genBoard();
        String intro = "PLAYER_VS_AI".equals(s.mode)
                ? "🤖 Player vs AI. Level " + s.level + ", target " + fmtNum(s.target) + "."
                : "🎮 Level " + s.level + ". Reach " + fmtNum(s.target) + " points in " + s.movesLeft + " moves.";
        s.addLog(intro);
        sessions.put(s.id, s);
        analytics.persistSession(s);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("session", serializeSession(s));
        resp.put("config", getConfig());
        return resp;
    }

    public synchronized Map<String, Object> abandonSession(String sessionId) {
        GameSession s = sessions.get(sessionId);
        if (s != null && !s.over && s.player.moves > 0 && "IN_PROGRESS".equals(s.status)) {
            s.status = "ABANDONED";
            analytics.persistSession(s);
        }
        return JsonUtil.mapOf("ok", true);
    }

    public synchronized Map<String, Object> playerSwap(String sessionId, int r1, int c1, int r2, int c2) {
        GameSession s = requireSession(sessionId);
        if (s.over || !"player".equals(s.turn)) {
            return JsonUtil.mapOf("ignored", true, "session", serializeSession(s));
        }

        Pos a = new Pos(r1, c1);
        Pos b = new Pos(r2, c2);
        if (!BoardEngine.inBounds(r1, c1) || !BoardEngine.inBounds(r2, c2) || !BoardEngine.isAdjacent(a, b)) {
            throw new IllegalArgumentException("Swap coordinates must be adjacent cells inside the 8x8 board.");
        }

        Tile ta = s.grid[r1][c1];
        Tile tb = s.grid[r2][c2];
        ClearPlan plan = board.trySwap(s.grid, a, b);

        // Temporarily perform swap to capture swappedGrid
        s.grid[r1][c1] = tb;
        s.grid[r2][c2] = ta;
        List<List<Map<String, Object>>> swappedGrid = BoardEngine.serializeGrid(s.grid);

        if (plan == null) {
            s.player.invalid++;
            // Revert swap
            s.grid[r1][c1] = ta;
            s.grid[r2][c2] = tb;
            analytics.persistSession(s);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", false);
            out.put("swappedGrid", swappedGrid);
            out.put("shake", List.of(a.key(BoardEngine.COLS), b.key(BoardEngine.COLS)));
            out.put("subbar", "❌ Invalid swap. A match of 2 or more is needed. No move used.");
            out.put("session", serializeSession(s));
            return out;
        }

        s.player.valid++;
        s.player.moves++;
        s.movesLeft--;

        ResolveOutcome outcome = resolve(s, "player", a, b);
        aiEngine.learnFrom(s, ta, tb, outcome.events(), outcome.combo());

        boolean targetReachedModal = false;
        String targetToast = null;
        if (s.player.score >= s.target && !s.cleared) {
            s.cleared = true;
            if ("PLAYER_ONLY".equals(s.mode)) {
                markCleared(s);
                targetReachedModal = true;
            } else {
                s.addLog("🎯 Target reached! Keep scoring to beat the AI.");
                targetToast = "Target reached!";
            }
        }

        boolean reshuffled = ensurePlayable(s);
        List<List<Map<String, Object>>> reshuffledGrid = reshuffled ? BoardEngine.serializeGrid(s.grid) : null;

        boolean needsAiTurn = false;
        Map<String, Object> endResult = null;

        if ("PLAYER_VS_AI".equals(s.mode)) {
            s.turn = "ai";
            needsAiTurn = true;
        } else if (!targetReachedModal && s.movesLeft <= 0) {
            endResult = endLevel(s);
        }

        analytics.persistSession(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("swappedGrid", swappedGrid);
        out.put("steps", outcome.steps().stream().map(ResolutionStep::toMap).toList());
        out.put("reshuffled", reshuffled);
        if (reshuffled) out.put("reshuffledGrid", reshuffledGrid);
        out.put("targetReachedModal", targetReachedModal);
        out.put("targetToast", targetToast);
        out.put("needsAiTurn", needsAiTurn);
        out.put("endResult", endResult);
        out.put("session", serializeSession(s));
        out.put("levels", getLevelsList());
        return out;
    }

    public synchronized Map<String, Object> keepPlaying(String sessionId) {
        GameSession s = requireSession(sessionId);
        Map<String, Object> endResult = null;
        if (!s.over && s.movesLeft <= 0) {
            endResult = endLevel(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endResult", endResult);
        out.put("session", serializeSession(s));
        return out;
    }

    public synchronized Map<String, Object> aiTurn(String sessionId) {
        GameSession s = requireSession(sessionId);
        if (s.over || !"PLAYER_VS_AI".equals(s.mode)) {
            return JsonUtil.mapOf("ignored", true, "session", serializeSession(s));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        MoveSimulation move = aiEngine.pickAIMove(s);
        if (move == null) {
            boolean reshuffled = ensurePlayable(s);
            s.turn = "player";
            Map<String, Object> endResult = s.movesLeft <= 0 ? endLevel(s) : null;
            out.put("noMove", true);
            out.put("reshuffled", reshuffled);
            out.put("endResult", endResult);
            out.put("session", serializeSession(s));
            return out;
        }

        Pos a = move.a();
        Pos b = move.b();
        Tile ta = s.grid[a.r()][a.c()];
        Tile tb = s.grid[b.r()][b.c()];
        CandyType ca = registry.getCandy(ta.type());
        CandyType cb = registry.getCandy(tb.type());
        String icA = ca != null ? ca.icon() : "🍬";
        String icB = cb != null ? cb.icon() : "🍬";

        String swapLog = "🤖 AI (Lv " + aiEngine.aiLevel(s) + ") swaps " + icA + " ⇄ " + icB;
        s.addLog(swapLog);

        int beforeScore = s.ai.score;
        // Perform swap
        s.grid[a.r()][a.c()] = tb;
        s.grid[b.r()][b.c()] = ta;
        List<List<Map<String, Object>>> swappedGrid = BoardEngine.serializeGrid(s.grid);

        s.ai.valid++;
        s.ai.moves++;

        ResolveOutcome outcome = resolve(s, "ai", a, b);
        String scoreLog = "🤖 AI scored +" + fmtNum(s.ai.score - beforeScore);
        s.addLog(scoreLog);

        boolean reshuffled = ensurePlayable(s);
        List<List<Map<String, Object>>> reshuffledGrid = reshuffled ? BoardEngine.serializeGrid(s.grid) : null;

        s.turn = "player";
        Map<String, Object> endResult = null;
        if (s.movesLeft <= 0) {
            endResult = endLevel(s);
        }

        analytics.persistSession(s);

        out.put("aiMove", List.of(a.toList(), b.toList()));
        out.put("swapLog", swapLog);
        out.put("scoreLog", scoreLog);
        out.put("swappedGrid", swappedGrid);
        out.put("steps", outcome.steps().stream().map(ResolutionStep::toMap).toList());
        out.put("reshuffled", reshuffled);
        if (reshuffled) out.put("reshuffledGrid", reshuffledGrid);
        out.put("endResult", endResult);
        out.put("session", serializeSession(s));
        out.put("levels", getLevelsList());
        return out;
    }

    public synchronized Map<String, Object> requestHint(String sessionId) {
        GameSession s = requireSession(sessionId);
        if (s.over || !"player".equals(s.turn)) {
            return JsonUtil.mapOf("found", false, "message", "Cannot request hint right now.");
        }
        MoveSimulation best = aiEngine.bestHint(s);
        if (best == null) {
            return JsonUtil.mapOf("found", false, "message", "No valid moves.");
        }
        s.hints++;
        Tile ta = s.grid[best.a().r()][best.a().c()];
        Tile tb = s.grid[best.b().r()][best.b().c()];
        CandyType ca = registry.getCandy(ta.type());
        CandyType cb = registry.getCandy(tb.type());
        String logMsg = "💡 Hint: swap " + (ca != null ? ca.icon() : "🍬")
                + " (row " + (best.a().r() + 1) + ", col " + (best.a().c() + 1) + ") with "
                + (cb != null ? cb.icon() : "🍬")
                + " (row " + (best.b().r() + 1) + ", col " + (best.b().c() + 1) + "), about +"
                + best.score() + " pts";
        s.addLog(logMsg);
        analytics.persistSession(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("hint", List.of(best.a().toList(), best.b().toList()));
        out.put("score", best.score());
        out.put("log", logMsg);
        out.put("subbar", "💡 Best move glows on the board (about +" + best.score() + " pts).");
        out.put("session", serializeSession(s));
        return out;
    }

    public synchronized Map<String, Object> evaluateWorth(String type, String id) {
        return worthEvaluator.evaluateBookItem(type, id);
    }

    public synchronized Map<String, Object> checkFormula(String name, List<String> ing, Integer pts) {
        WorthResult v = worthEvaluator.worthFormula("syn_preview", name, ing, pts, null);
        return JsonUtil.mapOf("verdict", v.toMap());
    }

    public synchronized Map<String, Object> addFormula(String sessionId, String name, String icon, List<String> ing, Integer pts) {
        String id = "syn_" + Long.toString(System.currentTimeMillis(), 36);
        WorthResult v = worthEvaluator.worthFormula(id, name, ing, pts, null);
        if (!v.ok()) {
            return JsonUtil.mapOf("ok", false, "verdict", v.toMap());
        }
        Formula f = new Formula(id, name.trim(), icon == null || icon.isBlank() ? "💎" : icon, new ArrayList<>(ing), pts, false);
        registry.addCustomFormula(f);

        GameSession s = sessionId != null ? sessions.get(sessionId) : null;
        if (s != null) {
            List<String> icons = new ArrayList<>();
            for (String cid : f.ingredients()) {
                CandyType c = registry.getCandy(cid);
                icons.add(c != null ? c.icon() : "?");
            }
            s.addLog("🧪 New formula added: " + String.join(" + ", icons) + " → " + f.icon() + " " + f.name() + " (+" + f.pts() + " pts)");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("verdict", v.toMap());
        out.put("formula", f.toMap());
        out.put("config", getConfig());
        if (s != null) out.put("session", serializeSession(s));
        return out;
    }

    public synchronized Map<String, Object> checkRule(String name, String trigKind, int trigN, String rewKind, Integer rewVal) {
        WorthResult v = worthEvaluator.worthRule(name, trigKind, trigN, rewKind, rewVal, null);
        return JsonUtil.mapOf("verdict", v.toMap());
    }

    public synchronized Map<String, Object> addRule(
            String sessionId,
            String name,
            String trigKind,
            String trigCandy,
            int trigN,
            String rewKind,
            Integer rewVal
    ) {
        WorthResult v = worthEvaluator.worthRule(name, trigKind, trigN, rewKind, rewVal, null);
        if (!v.ok()) {
            return JsonUtil.mapOf("ok", false, "verdict", v.toMap());
        }
        String id = "r_" + Long.toString(System.currentTimeMillis(), 36);
        String cleanCandy = (trigCandy == null || trigCandy.isBlank()) ? null : trigCandy;
        GameRule rule = new GameRule(
                id,
                name.trim(),
                new GameRule.Trigger(trigKind, cleanCandy, trigN),
                new GameRule.Reward(rewKind, rewVal),
                false
        );
        registry.addCustomRule(rule);

        GameSession s = sessionId != null ? sessions.get(sessionId) : null;
        if (s != null) {
            s.addLog("📜 New rule active: " + rule.name());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("verdict", v.toMap());
        out.put("rule", rule.toMap());
        out.put("config", getConfig());
        if (s != null) out.put("session", serializeSession(s));
        return out;
    }

    public synchronized Map<String, Object> getDashboard(String sessionId) {
        GameSession s = requireSession(sessionId);
        return analytics.buildDashboard(s);
    }

    public synchronized Map<String, Object> exportData(String sessionId, String format) {
        GameSession s = sessionId != null ? sessions.get(sessionId) : null;
        List<Map<String, Object>> rows = analytics.allRows(s);
        if (rows.isEmpty()) {
            throw new IllegalStateException("There is no gameplay data to export yet.");
        }
        String fmt = "json".equalsIgnoreCase(format) ? "json" : "csv";
        String content = "csv".equals(fmt) ? analytics.toCsv(rows) : analytics.toExportJson(rows);
        return JsonUtil.mapOf(
                "format", fmt,
                "filename", "player_game_data." + fmt,
                "rowCount", rows.size(),
                "content", content
        );
    }

    private ResolveOutcome resolve(GameSession s, String actor, Pos a, Pos b) {
        PlayerStats stats = s.statsFor(actor);
        ClearPlan plan = board.planClear(s.grid, "swap", a, b);
        int combo = 0;
        int gained = 0;
        List<ClearEvent> allEvents = new ArrayList<>();
        List<ResolutionStep> steps = new ArrayList<>();

        while (plan != null && !plan.clear().isEmpty()) {
            combo++;
            int mult = Math.min(combo, 5);
            int pts = plan.base() * mult;
            stats.score += pts;
            gained += pts;
            stats.maxCombo = Math.max(stats.maxCombo, mult);

            List<String> stepLogs = new ArrayList<>();
            boolean hasSynth = false;

            for (ClearEvent ev : plan.events()) {
                allEvents.add(ev.withCombo(combo));
                stats.incrementMatch(ev.size());
                if ("synth".equals(ev.kind())) {
                    hasSynth = true;
                    CandyType ct = registry.getCandy(ev.type());
                    String msg = "🧪 CANDY SYNTHESIS EXECUTED! Yielded "
                            + (ct != null ? ct.icon() + " " + ct.name() : ev.type())
                            + " (+" + ev.formula().pts() + " pts)!";
                    s.addLog(msg);
                    stepLogs.add(msg);
                }
                ruleEngine.applyRules(s, actor, ev, combo, stepLogs);
            }
            if (combo >= 2) {
                ruleEngine.applyRules(s, actor, null, combo, stepLogs);
                String cMsg = "🔥 Cascade x" + combo + "!";
                s.addLog(cMsg);
                stepLogs.add(cMsg);
            }

            List<List<Map<String, Object>>> popGrid = BoardEngine.serializeGrid(s.grid);
            List<Integer> popList = new ArrayList<>(plan.clear());
            int mid = popList.get(popList.size() / 2);
            String floatText = "+" + fmtNum(pts) + (mult > 1 ? " x" + mult : "");
            Map<String, Object> hudSnap = serializeHudSnapshot(s);

            ApplyResult applied = board.applyPlan(s.grid, plan, true);
            List<List<Map<String, Object>>> afterGrid = BoardEngine.serializeGrid(s.grid);

            steps.add(new ResolutionStep(
                    popGrid,
                    popList,
                    floatText,
                    mid / BoardEngine.COLS,
                    mid % BoardEngine.COLS,
                    hasSynth ? "synth" : "match",
                    combo,
                    stepLogs,
                    hudSnap,
                    afterGrid,
                    applied.fall(),
                    new ArrayList<>(applied.born())
            ));

            plan = board.planClear(s.grid, "cascade", null, null);
        }

        return new ResolveOutcome(allEvents, gained, combo, steps);
    }

    private boolean ensurePlayable(GameSession s) {
        if (board.hasMoves(s.grid)) return false;
        s.addLog("🔀 No valid moves. Board reshuffled.");
        s.grid = board.genBoard();
        return true;
    }

    private void markCleared(GameSession s) {
        int stars = s.movesLeft >= s.totalMoves * 0.4 ? 3 : (s.movesLeft >= s.totalMoves * 0.15 ? 2 : 1);
        registry.recordClearedLevel(s.level, stars);
        s.status = "LEVEL_CLEARED";
        analytics.persistSession(s);
    }

    private Map<String, Object> endLevel(GameSession s) {
        s.over = true;
        boolean won = s.player.score >= s.target;
        String icon, title, msg;
        if ("PLAYER_VS_AI".equals(s.mode)) {
            boolean beat = s.player.score > s.ai.score;
            boolean draw = s.player.score == s.ai.score;
            if (won) markCleared(s);
            s.status = won
                    ? (beat ? "WON_VS_AI" : draw ? "DRAW_TARGET_MET" : "TARGET_MET_AI_HIGHER")
                    : (beat ? "BEAT_AI_TARGET_MISSED" : "LOST");
            icon = beat ? "🏆" : draw ? "🤝" : "🤖";
            title = beat ? "You beat the AI!" : draw ? "Draw" : "The AI wins this round";
            msg = s.name + " " + fmtNum(s.player.score) + " vs AI " + fmtNum(s.ai.score)
                    + ". Target " + fmtNum(s.target) + (won ? " reached ✅" : " missed ❌") + ".";
        } else {
            if (won) {
                markCleared(s);
                s.status = "LEVEL_CLEARED";
                icon = "🎉";
                title = "Level Cleared!";
            } else {
                s.status = "OUT_OF_MOVES";
                icon = "😵";
                title = "Out of Moves";
            }
            msg = "Score " + fmtNum(s.player.score) + " of " + fmtNum(s.target) + ".";
        }
        analytics.persistSession(s);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("won", won);
        res.put("icon", icon);
        res.put("title", title);
        res.put("msg", msg);
        res.put("accuracy", s.player.accuracy());
        res.put("maxCombo", s.player.maxCombo);
        res.put("canNext", won && s.level < 20);
        return res;
    }

    private Map<String, Object> serializeCandies() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, CandyType> e : registry.getCandies().entrySet()) {
            out.put(e.getKey(), e.getValue().toMap());
        }
        return out;
    }

    private List<Map<String, Object>> serializeFormulas() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Formula f : registry.getFormulas()) {
            list.add(f.toMap());
        }
        return list;
    }

    private List<Map<String, Object>> serializeRules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GameRule r : registry.getRules()) {
            Map<String, Object> m = r.toMap();
            m.put("description", worthEvaluator.describeRulePlain(r));
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> serializeHudSnapshot(GameSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("movesLeft", s.movesLeft);
        m.put("player", s.player.toMap());
        m.put("ai", s.ai.toMap());
        m.put("aiPanel", aiEngine.aiPanelMap(s));
        return m;
    }

    public Map<String, Object> serializeSession(GameSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id);
        m.put("name", s.name);
        m.put("mode", s.mode);
        m.put("level", s.level);
        m.put("target", s.target);
        m.put("movesLeft", s.movesLeft);
        m.put("totalMoves", s.totalMoves);
        m.put("turn", s.turn);
        m.put("over", s.over);
        m.put("cleared", s.cleared);
        m.put("status", s.status);
        m.put("hints", s.hints);
        m.put("player", s.player.toMap());
        m.put("ai", s.ai.toMap());
        m.put("aiPanel", aiEngine.aiPanelMap(s));
        m.put("grid", BoardEngine.serializeGrid(s.grid));
        m.put("log", new ArrayList<>(s.log));
        return m;
    }

    private GameSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Missing sessionId");
        }
        GameSession s = sessions.get(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return s;
    }

    private static String fmtNum(int n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }
}
