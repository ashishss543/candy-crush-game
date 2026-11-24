package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.model.Models.*;

import java.util.*;

/**
 * Module 4: Adaptive AI Opponent, Learning Engine, and Best-Move Hint Engine.
 */
public final class AiEngine {

    private final CandyRegistry registry;
    private final BoardEngine board;
    private final Random random;

    public AiEngine(CandyRegistry registry, BoardEngine board) {
        this(registry, board, new Random());
    }

    public AiEngine(CandyRegistry registry, BoardEngine board, Random random) {
        this.registry = registry;
        this.board = board;
        this.random = random;
    }

    public void learnFrom(GameSession session, Tile ta, Tile tb, List<ClearEvent> evs, int combo) {
        AiLearnState l = session.learn;
        int best = 0;
        for (ClearEvent ev : evs) {
            best = Math.max(best, ev.size());
            if ("synth".equals(ev.kind())) {
                if (ev.size() == 2) l.synthN2++;
                else l.synthN++;
            } else if ("bomb".equals(ev.kind()) || ev.size() >= 5) {
                l.bomb++;
            } else if (ev.size() == 4) {
                l.striped++;
            } else if (ev.size() == 3) {
                l.std++;
            } else {
                l.pair++;
            }
            if (ev.type() != null) {
                CandyType ct = registry.getCandy(ev.type());
                if (ct != null && !ct.hidden()) {
                    l.fav.put(ev.type(), l.fav.getOrDefault(ev.type(), 0) + ev.size());
                }
            }
        }
        if (combo >= 2) l.cascade++;
        List<String> pair = new ArrayList<>(List.of(ta.type(), tb.type()));
        Collections.sort(pair);
        l.sigs.add(pair.get(0) + "/" + pair.get(1) + ":" + best);
    }

    public int aiLevel(GameSession session) {
        int n = session.learn.sigs.size();
        if (n >= 12) return 5;
        if (n >= 8) return 4;
        if (n >= 4) return 3;
        if (n >= 1) return 2;
        return 1;
    }

    public String stratLabel(GameSession session) {
        AiLearnState s = session.learn;
        int total = s.synthN2 + s.synthN + s.pair + s.std + s.striped + s.bomb + s.cascade;
        if (total == 0) return "Observing Player...";
        int synth = s.synthN2 + s.synthN;
        if (synth >= 2 && synth * 4 >= total) {
            return s.synthN2 >= s.synthN ? "Synthesis Pair Combination" : "Synthesis Chain Combination";
        }
        record Entry(String label, int count) {}
        List<Entry> others = new ArrayList<>(List.of(
                new Entry("Pair Combo Chaining", s.pair),
                new Entry("Standard Match Building", s.std),
                new Entry("Striped Candy Crafting", s.striped),
                new Entry("Color Bomb Hunting", s.bomb),
                new Entry("Cascade Chasing", s.cascade)
        ));
        others.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return others.get(0).label();
    }

    public CandyType favCandy(GameSession session) {
        String bestId = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : session.learn.fav.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestId = e.getKey();
            }
        }
        return bestId != null ? registry.getCandy(bestId) : null;
    }

    public MoveSimulation pickAIMove(GameSession session) {
        List<MoveSimulation> moves = board.allMoves(session.grid);
        if (moves.isEmpty()) return null;

        int lvl = aiLevel(session);
        CandyType fav = favCandy(session);

        moves.sort((x, y) -> Double.compare(scoreMove(y, lvl, fav), scoreMove(x, lvl, fav)));

        if (lvl == 1) return moves.get(random.nextInt(moves.size()));
        if (lvl == 2) return moves.get(random.nextInt(Math.max(1, (int) Math.ceil(moves.size() / 2.0))));
        if (lvl == 3) return moves.get(random.nextInt(Math.min(3, moves.size())));
        return moves.get(0);
    }

    private double scoreMove(MoveSimulation m, int lvl, CandyType fav) {
        double v = m.score();
        if (lvl >= 2 && fav != null && m.types().contains(fav.id())) v *= 1.25;
        if (lvl >= 4) v += m.specials() * 90.0 + m.synth() * 120.0;
        if (lvl >= 5) v += (m.combo() - 1) * 140.0;
        return v;
    }

    public MoveSimulation bestHint(GameSession session) {
        List<MoveSimulation> moves = board.allMoves(session.grid);
        if (moves.isEmpty()) return null;
        moves.sort((x, y) -> Integer.compare(y.score(), x.score()));
        return moves.get(0);
    }

    public Map<String, Object> aiPanelMap(GameSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", stratLabel(session));
        CandyType fav = favCandy(session);
        if (fav != null) {
            String[] parts = fav.name().split(" ");
            String shortName = parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
            m.put("favLabel", fav.icon() + " " + shortName);
        } else {
            m.put("favLabel", "None");
        }
        int lvl = aiLevel(session);
        int sigs = session.learn.sigs.size();
        m.put("level", lvl);
        m.put("swapsLearned", sigs);
        m.put("levelLabel", "Level " + lvl + " (" + sigs + " Swaps Learned)");
        return m;
    }
}
