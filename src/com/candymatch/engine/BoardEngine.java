package com.candymatch.engine;

import com.candymatch.model.*;
import com.candymatch.model.Models.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Module 2: 8x8 Board & Match Processing Engine (Match 2/3/4/5, Striped Candies,
 * Color Bombs, Synthesis detection, Gravity, Refill, and Move Simulation).
 */
public final class BoardEngine {

    public static final int ROWS = 8;
    public static final int COLS = 8;

    private final CandyRegistry registry;
    private final Random random;
    private final AtomicInteger tileSeq = new AtomicInteger(1);

    public BoardEngine(CandyRegistry registry) {
        this(registry, new Random());
    }

    public BoardEngine(CandyRegistry registry, Random random) {
        this.registry = registry;
        this.random = random;
    }

    public String randBase() {
        return CandyRegistry.BASE_IDS.get(random.nextInt(CandyRegistry.BASE_IDS.size()));
    }

    public Tile mkTile(String type) {
        return mkTile(type, null);
    }

    public Tile mkTile(String type, String stripe) {
        return new Tile(type, stripe, tileSeq.getAndIncrement());
    }

    public static Tile[][] cloneGrid(Tile[][] g) {
        Tile[][] copy = new Tile[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                copy[r][c] = g[r][c] != null ? g[r][c].copy() : null;
            }
        }
        return copy;
    }

    public static List<List<Map<String, Object>>> serializeGrid(Tile[][] g) {
        List<List<Map<String, Object>>> out = new ArrayList<>(ROWS);
        for (int r = 0; r < ROWS; r++) {
            List<Map<String, Object>> row = new ArrayList<>(COLS);
            for (int c = 0; c < COLS; c++) {
                row.add(g[r][c] != null ? g[r][c].toMap() : null);
            }
            out.add(row);
        }
        return out;
    }

    public static boolean isAdjacent(Pos a, Pos b) {
        return Math.abs(a.r() - b.r()) + Math.abs(a.c() - b.c()) == 1;
    }

    public static boolean inBounds(int r, int c) {
        return r >= 0 && r < ROWS && c >= 0 && c < COLS;
    }

    public Tile[][] genBoard() {
        for (int tries = 0; tries < 50; tries++) {
            Tile[][] g = new Tile[ROWS][COLS];
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    String t;
                    int guard = 0;
                    do {
                        t = randBase();
                        guard++;
                    } while (guard < 20 && (
                            (c >= 2 && g[r][c - 1].type().equals(t) && g[r][c - 2].type().equals(t)) ||
                            (r >= 2 && g[r - 1][c].type().equals(t) && g[r - 2][c].type().equals(t))
                    ));
                    g[r][c] = mkTile(t);
                }
            }
            if (hasMoves(g)) return g;
        }
        throw new IllegalStateException("Could not generate a playable board");
    }

    public List<Run> findRuns(Tile[][] g, int minLen) {
        List<Run> runs = new ArrayList<>();
        // Horizontal runs
        for (int r = 0; r < ROWS; r++) {
            int c = 0;
            while (c < COLS) {
                Tile x = g[r][c];
                int e = c;
                if (x != null && !"bomb".equals(x.type())) {
                    while (e + 1 < COLS && g[r][e + 1] != null && g[r][e + 1].type().equals(x.type())) {
                        e++;
                    }
                    if (e - c + 1 >= minLen) {
                        List<Pos> cells = new ArrayList<>(e - c + 1);
                        for (int i = c; i <= e; i++) cells.add(new Pos(r, i));
                        runs.add(new Run(x.type(), "h", cells));
                    }
                }
                c = e + 1;
            }
        }
        // Vertical runs
        for (int c = 0; c < COLS; c++) {
            int r = 0;
            while (r < ROWS) {
                Tile x = g[r][c];
                int e = r;
                if (x != null && !"bomb".equals(x.type())) {
                    while (e + 1 < ROWS && g[e + 1][c] != null && g[e + 1][c].type().equals(x.type())) {
                        e++;
                    }
                    if (e - r + 1 >= minLen) {
                        List<Pos> cells = new ArrayList<>(e - r + 1);
                        for (int i = r; i <= e; i++) cells.add(new Pos(i, c));
                        runs.add(new Run(x.type(), "v", cells));
                    }
                }
                r = e + 1;
            }
        }
        return runs;
    }

    /**
     * Build a clearing plan. mode = "swap" (with positions a and b) or "cascade".
     */
    public ClearPlan planClear(Tile[][] g, String mode, Pos a, Pos b) {
        Set<Integer> clear = new LinkedHashSet<>();
        List<Spawn> spawns = new ArrayList<>();
        List<ClearEvent> events = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        int base = 0;

        if ("swap".equals(mode)) {
            Tile ta = g[a.r()][a.c()];
            Tile tb = g[b.r()][b.c()];
            if ("bomb".equals(ta.type()) || "bomb".equals(tb.type())) {
                String target = null;
                if (!"bomb".equals(ta.type())) target = ta.type();
                else if (!"bomb".equals(tb.type())) target = tb.type();

                for (int r = 0; r < ROWS; r++) {
                    for (int c = 0; c < COLS; c++) {
                        Tile x = g[r][c];
                        if (x != null && (target == null || x.type().equals(target))) {
                            clear.add(r * COLS + c);
                        }
                    }
                }
                clear.add(a.key(COLS));
                clear.add(b.key(COLS));
                String evType = target != null ? target : "bomb";
                events.add(new ClearEvent("bomb", 5, null, evType, 0));
                types.add(evType);
                base += 150;
            } else {
                // Synthesis formulas first (largest formula first)
                List<Formula> fl = registry.getFormulas();
                fl.sort((x, y) -> Integer.compare(y.ingredients().size(), x.ingredients().size()));
                boolean synthFired = false;
                Pos[] swArr = {a, b};
                String[] dirs = {"h", "v"};

                outer:
                for (Formula f : fl) {
                    int n = f.ingredients().size();
                    String want = Formula.formulaKey(f.ingredients());
                    for (Pos p : swArr) {
                        for (String dir : dirs) {
                            for (int s = -(n - 1); s <= 0; s++) {
                                List<Pos> cells = new ArrayList<>(n);
                                boolean valid = true;
                                List<String> foundTypes = new ArrayList<>(n);
                                for (int i = 0; i < n; i++) {
                                    int rr = p.r() + ("v".equals(dir) ? s + i : 0);
                                    int cc = p.c() + ("h".equals(dir) ? s + i : 0);
                                    if (!inBounds(rr, cc) || g[rr][cc] == null || "bomb".equals(g[rr][cc].type())) {
                                        valid = false;
                                        break;
                                    }
                                    cells.add(new Pos(rr, cc));
                                    foundTypes.add(g[rr][cc].type());
                                }
                                if (!valid) continue;
                                if (!Formula.formulaKey(foundTypes).equals(want)) continue;

                                for (Pos cell : cells) clear.add(cell.key(COLS));
                                spawns.add(new Spawn(p.r(), p.c(), mkTile(f.id())));
                                events.add(new ClearEvent("synth", n, f, f.id(), 0));
                                base += f.pts();
                                types.add(f.id());
                                synthFired = true;
                                break outer;
                            }
                        }
                    }
                }

                if (!synthFired) {
                    Set<Integer> swKeys = Set.of(a.key(COLS), b.key(COLS));
                    List<Run> runs = new ArrayList<>();
                    for (Run run : findRuns(g, 2)) {
                        for (Pos cell : run.cells()) {
                            if (swKeys.contains(cell.key(COLS))) {
                                runs.add(run);
                                break;
                            }
                        }
                    }
                    base += addRuns(runs, List.of(a, b), clear, spawns, events, types);
                }
            }
        } else {
            base += addRuns(findRuns(g, 3), null, clear, spawns, events, types);
        }

        // Striped candies explode their line (chain reaction)
        Set<Integer> protect = new HashSet<>();
        for (Spawn sp : spawns) protect.add(sp.r() * COLS + sp.c());

        Deque<Integer> queue = new ArrayDeque<>(clear);
        Set<Integer> seen = new HashSet<>(clear);
        while (!queue.isEmpty()) {
            int k = queue.removeLast();
            int r = k / COLS;
            int c = k % COLS;
            Tile x = g[r][c];
            if (x == null || x.stripe() == null || protect.contains(k)) continue;
            List<Integer> add = new ArrayList<>();
            if ("h".equals(x.stripe())) {
                for (int i = 0; i < COLS; i++) add.add(r * COLS + i);
            } else {
                for (int i = 0; i < ROWS; i++) add.add(i * COLS + c);
            }
            for (int kk : add) {
                if (!seen.contains(kk) && g[kk / COLS][kk % COLS] != null) {
                    seen.add(kk);
                    clear.add(kk);
                    queue.addLast(kk);
                }
            }
            base += 20;
        }

        for (int k : clear) {
            Tile x = g[k / COLS][k % COLS];
            if (x != null) {
                base += registry.getCandyPts(x.type());
            }
        }

        return new ClearPlan(clear, spawns, events, types, base);
    }

    private int addRuns(
            List<Run> runs,
            List<Pos> sw,
            Set<Integer> clear,
            List<Spawn> spawns,
            List<ClearEvent> events,
            Set<String> types
    ) {
        int bonus = 0;
        for (Run run : runs) {
            int n = run.cells().size();
            for (Pos cell : run.cells()) {
                clear.add(cell.key(COLS));
            }
            events.add(new ClearEvent("run", n, null, run.type(), 0));
            types.add(run.type());
            if (n >= 4) {
                Pos pos = run.cells().get(n / 2);
                if (sw != null) {
                    for (Pos cell : run.cells()) {
                        if (sw.contains(cell)) {
                            pos = cell;
                            break;
                        }
                    }
                }
                if (n >= 5) {
                    spawns.add(new Spawn(pos.r(), pos.c(), mkTile("bomb")));
                    bonus += 150;
                } else {
                    String stripeDir = "h".equals(run.dir()) ? "v" : "h";
                    spawns.add(new Spawn(pos.r(), pos.c(), mkTile(run.type(), stripeDir)));
                    bonus += 60;
                }
            }
        }
        return bonus;
    }

    /**
     * Apply the plan: clear matched tiles, place spawned specials, drop tiles by gravity,
     * and optionally refill empty top cells.
     */
    public ApplyResult applyPlan(Tile[][] g, ClearPlan plan, boolean refill) {
        for (int k : plan.clear()) {
            g[k / COLS][k % COLS] = null;
        }
        Set<Integer> born = new LinkedHashSet<>();
        for (Spawn sp : plan.spawns()) {
            g[sp.r()][sp.c()] = sp.tile();
            born.add(sp.r() * COLS + sp.c());
        }
        int[][] fall = new int[ROWS][COLS];
        for (int c = 0; c < COLS; c++) {
            int w = ROWS - 1;
            for (int r = ROWS - 1; r >= 0; r--) {
                if (g[r][c] != null) {
                    if (r != w) {
                        g[w][c] = g[r][c];
                        g[r][c] = null;
                        fall[w][c] = w - r;
                        born.remove(r * COLS + c);
                    }
                    w--;
                }
            }
            for (int r = w; r >= 0; r--) {
                if (refill) {
                    g[r][c] = mkTile(randBase());
                    fall[r][c] = w + 1;
                }
            }
        }
        return new ApplyResult(fall, born);
    }

    public ClearPlan trySwap(Tile[][] g, Pos a, Pos b) {
        if (!inBounds(a.r(), a.c()) || !inBounds(b.r(), b.c())) return null;
        Tile ga = g[a.r()][a.c()];
        Tile gb = g[b.r()][b.c()];
        if (ga == null || gb == null) return null;
        Tile[][] h = cloneGrid(g);
        h[a.r()][a.c()] = gb.copy();
        h[b.r()][b.c()] = ga.copy();
        ClearPlan plan = planClear(h, "swap", a, b);
        return plan.clear().isEmpty() ? null : plan;
    }

    public List<Pos> neighbours(int r, int c) {
        List<Pos> list = new ArrayList<>(2);
        if (c + 1 < COLS) list.add(new Pos(r, c + 1));
        if (r + 1 < ROWS) list.add(new Pos(r + 1, c));
        return list;
    }

    public boolean hasMoves(Tile[][] g) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Pos p = new Pos(r, c);
                for (Pos n : neighbours(r, c)) {
                    if (trySwap(g, p, n) != null) return true;
                }
            }
        }
        return false;
    }

    /**
     * Estimate total value of a move including deterministic cascades (without random refill).
     */
    public MoveSimulation simulate(Tile[][] g, Pos a, Pos b) {
        Tile ga = g[a.r()][a.c()];
        Tile gb = g[b.r()][b.c()];
        if (ga == null || gb == null) return null;
        Tile[][] h = cloneGrid(g);
        h[a.r()][a.c()] = gb.copy();
        h[b.r()][b.c()] = ga.copy();

        ClearPlan plan = planClear(h, "swap", a, b);
        if (plan.clear().isEmpty()) return null;

        int total = 0;
        int combo = 0;
        int specials = 0;
        int synth = 0;
        Set<String> types = new LinkedHashSet<>(plan.types());

        while (!plan.clear().isEmpty() && combo < 6) {
            combo++;
            total += plan.base() * Math.min(combo, 5);
            specials += plan.spawns().size();
            for (ClearEvent ev : plan.events()) {
                if ("synth".equals(ev.kind())) synth++;
            }
            applyPlan(h, plan, false);
            plan = planClear(h, "cascade", null, null);
        }
        return new MoveSimulation(a, b, total, combo, specials, synth, types);
    }

    public List<MoveSimulation> allMoves(Tile[][] g) {
        List<MoveSimulation> out = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Pos p = new Pos(r, c);
                for (Pos n : neighbours(r, c)) {
                    MoveSimulation sim = simulate(g, p, n);
                    if (sim != null) out.add(sim);
                }
            }
        }
        return out;
    }
}
