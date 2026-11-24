package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.model.Models.*;

import java.util.*;

/**
 * Module 3: Custom Game Rules Execution Engine.
 */
public final class RuleEngine {

    private final CandyRegistry registry;

    public RuleEngine(CandyRegistry registry) {
        this.registry = registry;
    }

    public int applyRules(GameSession session, String actor, ClearEvent ev, int comboLevel, List<String> stepLogs) {
        PlayerStats stats = session.statsFor(actor);
        int bonus = 0;
        for (GameRule rule : registry.getRules()) {
            GameRule.Trigger t = rule.trigger();
            boolean hit = false;
            if ("synth".equals(t.kind())) {
                hit = ev != null && "synth".equals(ev.kind())
                        && (t.candy() == null || t.candy().isEmpty() || t.candy().equals(ev.type()));
            } else if ("match".equals(t.kind())) {
                hit = ev != null && !"synth".equals(ev.kind()) && ev.size() >= t.n()
                        && (t.candy() == null || t.candy().isEmpty() || t.candy().equals(ev.type()));
            } else if ("combo".equals(t.kind())) {
                hit = ev == null && comboLevel == t.n();
            }
            if (!hit) continue;

            if ("points".equals(rule.reward().kind())) {
                bonus += rule.reward().value();
                String msg = "📜 Rule “" + rule.name() + "”: +" + rule.reward().value() + " pts";
                session.addLog(msg);
                stepLogs.add(msg);
            } else if ("player".equals(actor)) {
                int v = rule.reward().value();
                session.movesLeft += v;
                String msg = "📜 Rule “" + rule.name() + "”: +" + v + " move" + (v > 1 ? "s" : "");
                session.addLog(msg);
                stepLogs.add(msg);
            }
        }
        stats.score += bonus;
        return bonus;
    }
}
