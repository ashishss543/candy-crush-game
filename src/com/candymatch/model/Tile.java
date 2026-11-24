package com.candymatch.model;

import java.util.*;

public final class Tile {
    private final String type;
    private final String stripe; // null, "h", or "v"
    private final int id;

    public Tile(String type, String stripe, int id) {
        this.type = type;
        this.stripe = stripe;
        this.id = id;
    }

    public String type() { return type; }
    public String stripe() { return stripe; }
    public int id() { return id; }

    public Tile copy() {
        return new Tile(type, stripe, id);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);
        m.put("s", stripe);
        m.put("id", id);
        return m;
    }
}
