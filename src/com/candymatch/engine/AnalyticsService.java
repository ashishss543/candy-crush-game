package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.util.JsonUtil;

import java.time.Instant;
import java.util.*;

/**
 * Module 5: Player Analytics (Power BI compatible Telemetry) & CSV/JSON Exporters.
 */
public final class AnalyticsService {

    private final CandyRegistry registry;

    public AnalyticsService(CandyRegistry registry) {
        this.registry = registry;
    }

    public Map<String, Object> sessionRow(GameSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("PlayerID", s.name);
        m.put("SessionID", s.id);
        m.put("Mode", s.mode);
        m.put("Level", s.level);
        m.put("PlayerScore", s.player.score);
        m.put("AIScore", s.ai.score);
        m.put("ValidSwaps", s.player.valid);
        m.put("InvalidSwaps", s.player.invalid);
        m.put("SwapAccuracy", s.player.accuracy() + "%");
        m.put("Matches", s.player.matches);
        m.put("Match2", s.player.matchCounts.getOrDefault(2, 0));
        m.put("Match3", s.player.matchCounts.getOrDefault(3, 0));
        m.put("Match4", s.player.matchCounts.getOrDefault(4, 0));
        m.put("Match5", s.player.matchCounts.getOrDefault(5, 0));
        m.put("Combo", s.player.maxCombo + "x");
        m.put("AIMoves", s.ai.moves);
        m.put("HintsUsed", s.hints);
        m.put("Target", s.target);
        m.put("Status", s.status);
        m.put("StartedAt", s.startedAt);
        return m;
    }

    public void persistSession(GameSession s) {
        if (s == null) return;
        registry.upsertHistoryRow(sessionRow(s));
    }

    public List<Map<String, Object>> allRows(GameSession current) {
        List<Map<String, Object>> hist = registry.getHistory();
        if (!hist.isEmpty()) return hist;
        return current != null ? List.of(sessionRow(current)) : Collections.emptyList();
    }

    public String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        List<String> headers = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers));
        for (Map<String, Object> row : rows) {
            sb.append('\n');
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(row.get(headers.get(i))));
            }
        }
        return sb.toString();
    }

    private static String csvCell(Object val) {
        String s = val == null ? "" : String.valueOf(val);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public String toExportJson(List<Map<String, Object>> rows) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportedAt", Instant.now().toString());
        payload.put("game", "Candy Match Saga");
        payload.put("sessions", rows);
        return JsonUtil.stringifyPretty(payload);
    }

    public Map<String, Object> buildDashboard(GameSession s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("player", s.player.toMap());
        out.put("ai", s.ai.toMap());
        out.put("target", s.target);
        out.put("hints", s.hints);
        out.put("targetProgress", Math.min(100, (int) Math.round((s.player.score * 100.0) / Math.max(1, s.target))));

        Map<String, Object> cur = sessionRow(s);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(cur);
        int added = 0;
        for (Map<String, Object> h : registry.getHistory()) {
            if (!s.id.equals(h.get("SessionID"))) {
                rows.add(h);
                added++;
                if (added >= 5) break;
            }
        }
        out.put("rows", rows);
        return out;
    }
}
