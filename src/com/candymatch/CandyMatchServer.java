package com.candymatch;

import com.candymatch.engine.CandyRegistry;
import com.candymatch.engine.GameService;
import com.candymatch.util.JsonUtil;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Candy Match Saga - Pure Java Localhost HTTP Server & REST API.
 * Runs on http://localhost:8080 by default (override via CLI arg or PORT env var).
 */
public final class CandyMatchServer {

    private final GameService gameService;
    private final Path webDir;
    private final int port;
    private HttpServer server;

    public CandyMatchServer(int port, Path webDir, Path storePath) {
        this.port = port;
        this.webDir = webDir;
        CandyRegistry registry = new CandyRegistry(storePath);
        this.gameService = new GameService(registry);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("============================================================");
        System.out.println("  CANDY MATCH SAGA (Java Engine) is running on localhost!");
        System.out.println("  Open in browser: http://localhost:" + getPort());
        System.out.println("============================================================");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    public GameService getGameService() {
        return gameService;
    }

    private void handleRequest(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = ex.getRequestURI().getPath();
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

        try {
            if (path.startsWith("/api/")) {
                handleApi(ex, method, path, query);
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                handleStatic(ex, path, "HEAD".equals(method));
            } else {
                sendJson(ex, 405, JsonUtil.mapOf("error", "Method not allowed"));
            }
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, JsonUtil.mapOf("error", e.getMessage()));
        } catch (IllegalStateException e) {
            sendJson(ex, 409, JsonUtil.mapOf("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, JsonUtil.mapOf("error", "Internal server error: " + e.getMessage()));
        }
    }

    private void handleApi(HttpExchange ex, String method, String path, Map<String, String> query) throws IOException {
        if ("OPTIONS".equals(method)) {
            addNoCacheHeaders(ex.getResponseHeaders());
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        if ("GET".equals(method) && "/api/health".equals(path)) {
            sendJson(ex, 200, JsonUtil.mapOf("status", "ok", "engine", "Java " + System.getProperty("java.version"), "port", getPort()));
            return;
        }
        if ("GET".equals(method) && "/api/config".equals(path)) {
            sendJson(ex, 200, gameService.getConfig());
            return;
        }
        if ("GET".equals(method) && "/api/levels".equals(path)) {
            sendJson(ex, 200, JsonUtil.mapOf("levels", gameService.getLevelsList()));
            return;
        }
        if ("POST".equals(method) && "/api/sound".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            sendJson(ex, 200, gameService.setSound(JsonUtil.asBool(body.get("enabled"), true)));
            return;
        }
        if ("POST".equals(method) && "/api/game/start".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String name = JsonUtil.asString(body.get("name"), "Player1");
            String mode = JsonUtil.asString(body.get("mode"), "PLAYER_ONLY");
            int level = JsonUtil.asInt(body.get("level"), 1);
            String prevId = JsonUtil.asString(body.get("prevSessionId"), null);
            sendJson(ex, 200, gameService.startGame(name, mode, level, prevId));
            return;
        }
        if ("POST".equals(method) && "/api/game/abandon".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            sendJson(ex, 200, gameService.abandonSession(JsonUtil.asString(body.get("sessionId"), "")));
            return;
        }
        if ("POST".equals(method) && "/api/game/swap".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String sid = JsonUtil.asString(body.get("sessionId"), "");
            int r1 = JsonUtil.asInt(body.get("r1"), -1);
            int c1 = JsonUtil.asInt(body.get("c1"), -1);
            int r2 = JsonUtil.asInt(body.get("r2"), -1);
            int c2 = JsonUtil.asInt(body.get("c2"), -1);
            sendJson(ex, 200, gameService.playerSwap(sid, r1, c1, r2, c2));
            return;
        }
        if ("POST".equals(method) && "/api/game/ai-turn".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            sendJson(ex, 200, gameService.aiTurn(JsonUtil.asString(body.get("sessionId"), "")));
            return;
        }
        if ("POST".equals(method) && "/api/game/keep-playing".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            sendJson(ex, 200, gameService.keepPlaying(JsonUtil.asString(body.get("sessionId"), "")));
            return;
        }
        if ("POST".equals(method) && "/api/game/hint".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            sendJson(ex, 200, gameService.requestHint(JsonUtil.asString(body.get("sessionId"), "")));
            return;
        }
        if ("POST".equals(method) && "/api/worth/evaluate".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String type = JsonUtil.asString(body.get("type"), "candy");
            String id = JsonUtil.asString(body.get("id"), "");
            sendJson(ex, 200, gameService.evaluateWorth(type, id));
            return;
        }
        if ("POST".equals(method) && "/api/formulas/check".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String name = JsonUtil.asString(body.get("name"), "");
            List<String> ing = readStringList(body.get("ing"));
            Integer pts = JsonUtil.asIntOrNull(body.get("pts"));
            sendJson(ex, 200, gameService.checkFormula(name, ing, pts));
            return;
        }
        if ("POST".equals(method) && "/api/formulas".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String sid = JsonUtil.asString(body.get("sessionId"), null);
            String name = JsonUtil.asString(body.get("name"), "");
            String icon = JsonUtil.asString(body.get("icon"), "💎");
            List<String> ing = readStringList(body.get("ing"));
            Integer pts = JsonUtil.asIntOrNull(body.get("pts"));
            sendJson(ex, 200, gameService.addFormula(sid, name, icon, ing, pts));
            return;
        }
        if ("POST".equals(method) && "/api/rules/check".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String name = JsonUtil.asString(body.get("name"), "");
            String trigKind = JsonUtil.asString(body.get("trigKind"), "match");
            int trigN = JsonUtil.asInt(body.get("trigN"), 2);
            String rewKind = JsonUtil.asString(body.get("rewKind"), "points");
            Integer rewVal = JsonUtil.asIntOrNull(body.get("rewVal"));
            sendJson(ex, 200, gameService.checkRule(name, trigKind, trigN, rewKind, rewVal));
            return;
        }
        if ("POST".equals(method) && "/api/rules".equals(path)) {
            Map<String, Object> body = readJsonBody(ex);
            String sid = JsonUtil.asString(body.get("sessionId"), null);
            String name = JsonUtil.asString(body.get("name"), "");
            String trigKind = JsonUtil.asString(body.get("trigKind"), "match");
            String trigCandy = body.get("trigCandy") == null ? null : String.valueOf(body.get("trigCandy"));
            int trigN = JsonUtil.asInt(body.get("trigN"), 2);
            String rewKind = JsonUtil.asString(body.get("rewKind"), "points");
            Integer rewVal = JsonUtil.asIntOrNull(body.get("rewVal"));
            sendJson(ex, 200, gameService.addRule(sid, name, trigKind, trigCandy, trigN, rewKind, rewVal));
            return;
        }
        if ("GET".equals(method) && "/api/dashboard".equals(path)) {
            sendJson(ex, 200, gameService.getDashboard(query.get("sessionId")));
            return;
        }
        if ("GET".equals(method) && ("/api/export/csv".equals(path) || "/api/export/json".equals(path))) {
            String fmt = "/api/export/csv".equals(path) ? "csv" : "json";
            Map<String, Object> exported = gameService.exportData(query.get("sessionId"), fmt);
            if ("1".equals(query.get("download"))) {
                String content = JsonUtil.asString(exported.get("content"), "");
                String filename = JsonUtil.asString(exported.get("filename"), "player_game_data." + fmt);
                String mime = "csv".equals(fmt) ? "text/csv; charset=UTF-8" : "application/json; charset=UTF-8";
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                Headers h = ex.getResponseHeaders();
                addNoCacheHeaders(h);
                h.set("Content-Type", mime);
                h.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            } else {
                sendJson(ex, 200, exported);
            }
            return;
        }

        sendJson(ex, 404, JsonUtil.mapOf("error", "Unknown API route: " + method + " " + path));
    }

    private void handleStatic(HttpExchange ex, String rawPath, boolean headOnly) throws IOException {
        String rel = "/".equals(rawPath) ? "index.html" : rawPath.replaceFirst("^/+", "");
        Path resolved = webDir.resolve(rel).normalize();
        if (!resolved.startsWith(webDir.normalize()) || !Files.exists(resolved) || Files.isDirectory(resolved)) {
            sendText(ex, 404, "text/plain; charset=UTF-8", "Not found", headOnly);
            return;
        }
        byte[] bytes = Files.readAllBytes(resolved);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType(resolved.getFileName().toString()));
        addNoCacheHeaders(h);
        if (headOnly) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) return new LinkedHashMap<>();
            return JsonUtil.parseObject(raw);
        }
    }

    private static List<String> readStringList(Object obj) {
        List<String> out = new ArrayList<>();
        for (Object item : JsonUtil.asList(obj)) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(k, v);
            } else if (!pair.isEmpty()) {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = JsonUtil.stringify(payload).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        addNoCacheHeaders(h);
        h.set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String contentType, String body, boolean headOnly) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        addNoCacheHeaders(h);
        h.set("Content-Type", contentType);
        if (headOnly) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void addNoCacheHeaders(Headers h) {
        h.set("Cache-Control", "no-store, no-cache, must-revalidate");
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }
        if (args.length > 0) {
            port = Integer.parseInt(args[0].trim());
        }
        Path root = Paths.get("").toAbsolutePath();
        Path webDir = root.resolve("web");
        Path storePath = root.resolve("data").resolve("store.json");
        CandyMatchServer srv = new CandyMatchServer(port, webDir, storePath);
        srv.start();
    }
}
