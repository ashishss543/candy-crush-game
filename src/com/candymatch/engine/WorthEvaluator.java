package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.model.Models.*;

import java.util.*;

/**
 * Module 3: AI Worth Check (Rule-Based Accept / Reject Balance Evaluator).
 */
public final class WorthEvaluator {

    private final CandyRegistry registry;

    public WorthEvaluator(CandyRegistry registry) {
        this.registry = registry;
    }

    public WorthResult worthFormula(String id, String rawName, List<String> ing, Integer pts, String existingIgnoreId) {
        List<String> reasons = new ArrayList<>();
        int n = ing != null ? ing.size() : 0;
        boolean ok = true;
        String name = rawName == null ? "" : rawName.trim();

        if (name.isEmpty()) {
            ok = false;
            reasons.add("The candy needs a name.");
        } else if (name.length() > 24) {
            ok = false;
            reasons.add("The name is longer than 24 characters.");
        }

        if (pts == null || pts <= 0) {
            ok = false;
            reasons.add("The score value must be a positive number.");
        } else if (pts > 1000) {
            ok = false;
            reasons.add("Score values above 1000 would break the balance.");
        }

        if (ing == null || ing.size() < 2) {
            ok = false;
            reasons.add("A synthesis formula needs at least 2 ingredients.");
        } else {
            for (String cid : ing) {
                if (registry.getCandy(cid) == null) {
                    ok = false;
                    reasons.add("One of the ingredients does not exist.");
                    break;
                }
            }
        }

        List<Formula> others = new ArrayList<>();
        for (Formula x : registry.getFormulas()) {
            if (!Objects.equals(x.id(), existingIgnoreId)) {
                others.add(x);
            }
        }

        if (ing != null && !ing.isEmpty()) {
            String key = Formula.formulaKey(ing);
            for (Formula x : others) {
                if (Formula.formulaKey(x.ingredients()).equals(key)) {
                    ok = false;
                    reasons.add("Another candy already uses this exact formula.");
                    break;
                }
            }
        }

        for (Formula x : others) {
            if (x.name().equalsIgnoreCase(name)) {
                ok = false;
                reasons.add("A candy with this name already exists.");
                break;
            }
        }

        if (ing != null && id != null && ing.contains(id)) {
            ok = false;
            reasons.add("A candy cannot be its own ingredient.");
        }

        int sum = 0;
        if (ing != null) {
            for (String cid : ing) {
                CandyType c = registry.getCandy(cid);
                if (c != null) sum += c.pts();
            }
        }
        int fair = (int) Math.round(sum * 0.6 + (double) n * n * 10.0);
        double ratio = (pts != null && fair > 0) ? ((double) pts / fair) : 0.0;

        if (ok) {
            if (ratio > 1.8) {
                ok = false;
                reasons.add(String.format(Locale.US,
                        "Overpowered: %d pts is %.2fx the fair value of about %d. Try %d or less.",
                        pts, ratio, fair, Math.round(fair * 1.5)));
            } else if (ratio < 0.5) {
                ok = false;
                reasons.add(String.format(Locale.US,
                        "Not worth the effort: %d pts is only %.2fx the fair value of about %d.",
                        pts, ratio, fair));
            } else {
                reasons.add(String.format(Locale.US,
                        "Worth index %.2fx against a fair value of about %d pts for %d ingredients (%d pts of candy).",
                        ratio, fair, n, sum));
            }
        }

        if (ok && n >= 4 && new HashSet<>(ing).size() == 1) {
            reasons.add("Hard to line up: all ingredients are the same colour.");
        }

        return new WorthResult(ok, reasons, ratio, fair);
    }

    public WorthResult worthRule(
            String rawName,
            String trigKind,
            int trigN,
            String rewKind,
            Integer rewValue,
            String ignoreId
    ) {
        List<String> reasons = new ArrayList<>();
        boolean ok = true;
        String name = rawName == null ? "" : rawName.trim();

        if (name.isEmpty()) {
            ok = false;
            reasons.add("The rule needs a name.");
        }
        if (rewValue == null || rewValue <= 0) {
            ok = false;
            reasons.add("The reward must be a positive number.");
        }
        for (GameRule x : registry.getRules()) {
            if (!Objects.equals(x.id(), ignoreId) && x.name().equalsIgnoreCase(name)) {
                ok = false;
                reasons.add("A rule with this name already exists.");
                break;
            }
        }

        double weight;
        if ("synth".equals(trigKind)) weight = 2.5;
        else if ("combo".equals(trigKind)) weight = trigN;
        else weight = Math.max(1, trigN - 1);

        int fair = "points".equals(rewKind)
                ? (int) Math.round(weight * 100.0)
                : Math.max(1, (int) Math.round((weight * 100.0) / 250.0));

        if ("moves".equals(rewKind) && rewValue != null && rewValue > 3) {
            ok = false;
            reasons.add("More than 3 extra moves is too generous.");
        }

        double ratio = (rewValue != null && fair > 0) ? ((double) rewValue / fair) : 0.0;
        String unit = "points".equals(rewKind) ? " pts" : " moves";

        if (ok) {
            if (ratio > 2.0) {
                ok = false;
                reasons.add(String.format(Locale.US,
                        "Overpowered: reward is %.2fx the fair reward (%d%s) for this trigger.",
                        ratio, fair, unit));
            } else if (ratio < 0.3) {
                ok = false;
                reasons.add(String.format(Locale.US,
                        "Too small to matter: reward is only %.2fx the fair reward (%d%s).",
                        ratio, fair, unit));
            } else {
                String unitFlex = "points".equals(rewKind) ? " pts" : " move(s)";
                reasons.add(String.format(Locale.US,
                        "Balance index %.2fx against a fair reward of %d%s.",
                        ratio, fair, unitFlex));
            }
        }

        return new WorthResult(ok, reasons, ratio, fair);
    }

    public Map<String, Object> evaluateBookItem(String type, String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("candy".equals(type)) {
            CandyType c = registry.getCandy(id);
            if (c == null) throw new IllegalArgumentException("Candy not found: " + id);
            out.put("heading", c.icon() + " " + c.name());
            if (c.base()) {
                out.put("detail", "Base candy worth " + c.pts() + " pts per tile.");
                out.put("verdict", new WorthResult(true, List.of("Built-in candies are always valid."), 1.0, c.pts()).toMap());
                return out;
            }
            Formula f = registry.getFormulas().stream()
                    .filter(x -> x.id().equals(c.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Formula not found for candy: " + id));
            WorthResult v = worthFormula(f.id(), f.name(), f.ingredients(), f.pts(), f.id());
            out.put("detail", ingredientIcons(f) + " → " + c.icon() + " (" + f.pts() + " pts)");
            out.put("verdict", v.toMap());
            return out;
        } else if ("formula".equals(type)) {
            Formula f = registry.getFormulas().stream()
                    .filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Formula not found: " + id));
            WorthResult v = worthFormula(f.id(), f.name(), f.ingredients(), f.pts(), f.id());
            out.put("heading", f.icon() + " " + f.name());
            out.put("detail", ingredientIcons(f) + " → " + f.icon());
            out.put("verdict", v.toMap());
            return out;
        } else {
            GameRule r = registry.getRules().stream()
                    .filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + id));
            WorthResult v = worthRule(r.name(), r.trigger().kind(), r.trigger().n(), r.reward().kind(), r.reward().value(), r.id());
            out.put("heading", "📜 " + r.name());
            out.put("detail", describeRulePlain(r));
            out.put("verdict", v.toMap());
            return out;
        }
    }

    public String describeRulePlain(GameRule r) {
        GameRule.Trigger t = r.trigger();
        String w = "any candy";
        if (t.candy() != null && !t.candy().isEmpty()) {
            CandyType ct = registry.getCandy(t.candy());
            if (ct != null) w = ct.icon() + " " + ct.name();
        }
        String when;
        if ("synth".equals(t.kind())) when = "When " + w + " is synthesized";
        else if ("match".equals(t.kind())) when = "On a match of " + t.n() + "+ with " + w;
        else when = "When a cascade reaches x" + t.n();

        String rew = "points".equals(r.reward().kind())
                ? ("+" + r.reward().value() + " pts")
                : ("+" + r.reward().value() + " move" + (r.reward().value() > 1 ? "s" : ""));
        return when + ": " + rew;
    }

    private String ingredientIcons(Formula f) {
        List<String> icons = new ArrayList<>();
        for (String cid : f.ingredients()) {
            CandyType ct = registry.getCandy(cid);
            icons.add(ct != null ? ct.icon() : "?");
        }
        return String.join(" + ", icons);
    }
}
