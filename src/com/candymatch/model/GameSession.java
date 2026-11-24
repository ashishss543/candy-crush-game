package com.candymatch.model;

import java.time.Instant;
import java.util.*;

public final class GameSession {
    public final String id;
    public final String name;
    public final String mode; // PLAYER_ONLY or PLAYER_VS_AI
    public final int level;
    public final int target;
    public int movesLeft;
    public final int totalMoves;
    public Tile[][] grid;
    public String turn = "player"; // player, ai, wait
    public boolean over = false;
    public boolean cleared = false;
    public final PlayerStats player = new PlayerStats();
    public final PlayerStats ai = new PlayerStats();
    public int hints = 0;
    public String status = "IN_PROGRESS";
    public final String startedAt;
    public final AiLearnState learn = new AiLearnState();
    public final Deque<String> log = new ArrayDeque<>();

    public GameSession(String name, String mode, int level) {
        Models.LevelConfig cfg = Models.LevelConfig.forLevel(level);
        this.id = "SESS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        this.name = name;
        this.mode = "PLAYER_VS_AI".equals(mode) ? "PLAYER_VS_AI" : "PLAYER_ONLY";
        this.level = cfg.level();
        this.target = cfg.target();
        this.movesLeft = cfg.moves();
        this.totalMoves = cfg.moves();
        this.startedAt = Instant.now().toString();
    }

    public void addLog(String message) {
        log.addFirst(message);
        while (log.size() > 40) {
            log.removeLast();
        }
    }

    public PlayerStats statsFor(String actor) {
        return "player".equals(actor) ? player : ai;
    }
}
