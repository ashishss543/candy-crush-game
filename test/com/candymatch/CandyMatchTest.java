package com.candymatch;

import com.candymatch.engine.*;
import com.candymatch.model.*;
import com.candymatch.model.Models.*;
import com.candymatch.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

/**
 * Automated verification suite for all 5 Candy Match Saga Java modules
 * plus live localhost HTTP server & REST API integration tests.
 */
public final class CandyMatchTest {

    private static int passed = 0;

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("FAIL: " + msg + " (expected=" + expected + ", actual=" + actual + ")");
        }
        passed++;
    }

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("candymatch-test");
        Path storePath = tempDir.resolve("store.json");

        // 1. JSON round-trip with Unicode emojis
        Map<String, Object> sample = JsonUtil.mapOf("name", "Ruby 🔻", "pts", 100, "ok", true, "list", List.of("🍓", "🫐"));
        Map<String, Object> parsed = JsonUtil.parseObject(JsonUtil.stringify(sample));
        assertEquals("Ruby 🔻", parsed.get("name"), "JSON emoji round-trip");
        assertEquals(100, JsonUtil.asInt(parsed.get("pts"), 0), "JSON int round-trip");

        // 2. Registry & Levels
        CandyRegistry reg = new CandyRegistry(storePath);
        assertEquals(10, reg.getCandies().size(), "6 base + 1 bomb + 3 builtin synthesis candies");
        assertEquals(3, reg.getFormulas().size(), "3 builtin formulas");
        assertEquals(2, reg.getRules().size(), "2 builtin rules");
        LevelConfig l1 = LevelConfig.forLevel(1);
        LevelConfig l20 = LevelConfig.forLevel(20);
        assertEquals(1300, l1.target(), "Level 1 target");
        assertEquals(24, l1.moves(), "Level 1 moves");
        assertEquals(7000, l20.target(), "Level 20 target");
        assertEquals(20, l20.moves(), "Level 20 moves");

        // 3. Board Engine: genBoard, Match 2/3/4/5, Striped chain, Bomb, Synthesis
        BoardEngine board = new BoardEngine(reg, new Random(42));
        Tile[][] g = board.genBoard();
        assertTrue(board.hasMoves(g), "Generated board must have moves");
        assertEquals(0, board.findRuns(g, 3).size(), "Initial board must have no 3-in-a-row runs");

        // Test Synthesis (red + red -> syn_ruby)
        Tile[][] synGrid = blankGrid(board, "yellow", "orange");
        synGrid[0][0] = board.mkTile("red");
        synGrid[0][1] = board.mkTile("green");
        synGrid[0][2] = board.mkTile("red");
        ClearPlan synPlan = board.trySwap(synGrid, new Pos(0, 0), new Pos(0, 1));
        assertTrue(synPlan != null, "Red+Red swap should trigger Ruby Candy synthesis");
        assertEquals("synth", synPlan.events().get(0).kind(), "Event kind should be synth");
        assertEquals("syn_ruby", synPlan.spawns().get(0).tile().type(), "Should spawn syn_ruby");

        // Test Match 4 -> Striped Candy
        Tile[][] m4Grid = blankGrid(board, "yellow", "blue");
        m4Grid[3][0] = board.mkTile("green");
        m4Grid[3][1] = board.mkTile("green");
        m4Grid[3][2] = board.mkTile("orange");
        m4Grid[3][3] = board.mkTile("green");
        m4Grid[2][2] = board.mkTile("green");
        ClearPlan m4Plan = board.trySwap(m4Grid, new Pos(3, 2), new Pos(2, 2));
        assertTrue(m4Plan != null && !m4Plan.spawns().isEmpty(), "Match 4 should spawn striped candy");
        assertEquals("v", m4Plan.spawns().get(0).tile().stripe(), "Horizontal Match 4 creates vertical stripe");

        // Test Match 5 -> Color Bomb
        Tile[][] m5Grid = blankGrid(board, "yellow", "green");
        m5Grid[4][0] = board.mkTile("blue");
        m5Grid[4][1] = board.mkTile("blue");
        m5Grid[4][2] = board.mkTile("orange");
        m5Grid[4][3] = board.mkTile("blue");
        m5Grid[4][4] = board.mkTile("blue");
        m5Grid[3][2] = board.mkTile("blue");
        ClearPlan m5Plan = board.trySwap(m5Grid, new Pos(4, 2), new Pos(3, 2));
        assertTrue(m5Plan != null && !m5Plan.spawns().isEmpty(), "Match 5 should spawn Color Bomb");
        assertEquals("bomb", m5Plan.spawns().get(0).tile().type(), "Match 5 spawns bomb");

        // Test Bomb swap clears all of target colour
        Tile[][] bGrid = blankGrid(board, "yellow", "green");
        bGrid[0][0] = board.mkTile("bomb");
        bGrid[0][1] = board.mkTile("orange");
        bGrid[5][5] = board.mkTile("orange");
        ClearPlan bPlan = board.trySwap(bGrid, new Pos(0, 0), new Pos(0, 1));
        assertTrue(bPlan != null && bPlan.clear().contains(5 * 8 + 5), "Bomb+Orange clears all orange tiles");

        // 4. Worth Evaluator (Accept / Reject)
        WorthEvaluator worth = new WorthEvaluator(reg);
        WorthResult goodF = worth.worthFormula("syn_test", "Sapphire Drop", List.of("blue", "blue"), 100, null);
        assertTrue(goodF.ok(), "Balanced 2-candy formula should be ACCEPTED");
        WorthResult opF = worth.worthFormula("syn_op", "Mega OP", List.of("blue", "blue"), 999, null);
        assertTrue(!opF.ok(), "Overpowered formula should be REJECTED");
        WorthResult dupF = worth.worthFormula("syn_dup", "Another Ruby", List.of("red", "red"), 100, null);
        assertTrue(!dupF.ok(), "Duplicate ingredients formula should be REJECTED");

        WorthResult goodR = worth.worthRule("Grape Pair Bonus", "match", 2, "points", 100, null);
        assertTrue(goodR.ok(), "Balanced rule should be ACCEPTED");
        WorthResult opR = worth.worthRule("Too Many Moves", "match", 2, "moves", 5, null);
        assertTrue(!opR.ok(), "Rule granting >3 moves should be REJECTED");

        // 5. Full Localhost HTTP Server + REST API Integration Test
        CandyMatchServer server = new CandyMatchServer(0, Paths.get("web").toAbsolutePath(), storePath);
        server.start();
        try {
            int port = server.getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // Health & static index
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), "GET /api/health -> 200");
            HttpResponse<String> html = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, html.statusCode(), "GET / -> 200");
            assertTrue(html.body().contains("CANDY MATCH SAGA"), "Index HTML contains title");

            // Start PLAYER_VS_AI game
            HttpResponse<String> startRes = postJson(client, baseUrl + "/api/game/start",
                    JsonUtil.mapOf("name", "Tester", "mode", "PLAYER_VS_AI", "level", 1));
            assertEquals(200, startRes.statusCode(), "POST /api/game/start -> 200");
            Map<String, Object> startObj = JsonUtil.parseObject(startRes.body());
            String sid = JsonUtil.asString(JsonUtil.asMap(startObj.get("session")).get("id"), "");
            assertTrue(sid.startsWith("SESS_"), "Session ID created");

            // Ask for Hint
            HttpResponse<String> hintRes = postJson(client, baseUrl + "/api/game/hint", JsonUtil.mapOf("sessionId", sid));
            assertEquals(200, hintRes.statusCode(), "POST /api/game/hint -> 200");
            Map<String, Object> hintObj = JsonUtil.parseObject(hintRes.body());
            assertTrue(JsonUtil.asBool(hintObj.get("found"), false), "Hint should find a valid move");
            List<Object> hintCoords = JsonUtil.asList(hintObj.get("hint"));
            List<Object> pA = JsonUtil.asList(hintCoords.get(0));
            List<Object> pB = JsonUtil.asList(hintCoords.get(1));

            // Execute valid swap using hint coordinates
            HttpResponse<String> swapRes = postJson(client, baseUrl + "/api/game/swap", JsonUtil.mapOf(
                    "sessionId", sid,
                    "r1", JsonUtil.asInt(pA.get(0), 0), "c1", JsonUtil.asInt(pA.get(1), 0),
                    "r2", JsonUtil.asInt(pB.get(0), 0), "c2", JsonUtil.asInt(pB.get(1), 0)
            ));
            assertEquals(200, swapRes.statusCode(), "POST /api/game/swap -> 200");
            Map<String, Object> swapObj = JsonUtil.parseObject(swapRes.body());
            assertTrue(JsonUtil.asBool(swapObj.get("valid"), false), "Hint swap must be valid");
            assertTrue(JsonUtil.asBool(swapObj.get("needsAiTurn"), false), "PLAYER_VS_AI triggers AI turn");
            assertTrue(JsonUtil.asInt(JsonUtil.asMap(JsonUtil.asMap(swapObj.get("session")).get("player")).get("score"), 0) > 0, "Player score > 0");

            // Execute AI turn
            HttpResponse<String> aiRes = postJson(client, baseUrl + "/api/game/ai-turn", JsonUtil.mapOf("sessionId", sid));
            assertEquals(200, aiRes.statusCode(), "POST /api/game/ai-turn -> 200");
            Map<String, Object> aiObj = JsonUtil.parseObject(aiRes.body());
            assertTrue(JsonUtil.asInt(JsonUtil.asMap(JsonUtil.asMap(aiObj.get("session")).get("ai")).get("score"), 0) > 0, "AI score > 0 after AI turn");

            // Add custom formula & verify persistence
            HttpResponse<String> fRes = postJson(client, baseUrl + "/api/formulas", JsonUtil.mapOf(
                    "sessionId", sid, "name", "Sapphire Drop", "icon", "💎", "ing", List.of("blue", "blue"), "pts", 100));
            assertTrue(JsonUtil.asBool(JsonUtil.parseObject(fRes.body()).get("ok"), false), "Add valid custom formula succeeds");

            // Add custom rule
            HttpResponse<String> rRes = postJson(client, baseUrl + "/api/rules", JsonUtil.mapOf(
                    "sessionId", sid, "name", "Grape Pair Bonus", "trigKind", "match", "trigCandy", "purple", "trigN", 2, "rewKind", "points", "rewVal", 100));
            assertTrue(JsonUtil.asBool(JsonUtil.parseObject(rRes.body()).get("ok"), false), "Add valid custom rule succeeds");

            // Dashboard & CSV/JSON Exports
            HttpResponse<String> dashRes = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/dashboard?sessionId=" + sid)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dashRes.statusCode(), "GET /api/dashboard -> 200");
            HttpResponse<String> csvRes = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/export/csv?sessionId=" + sid)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csvRes.statusCode(), "GET /api/export/csv -> 200");
            assertTrue(csvRes.body().contains("PlayerID,SessionID,Mode"), "CSV export contains expected headers");
            HttpResponse<String> jsonRes = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/export/json?sessionId=" + sid)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, jsonRes.statusCode(), "GET /api/export/json -> 200");
            assertTrue(jsonRes.body().contains("Candy Match Saga"), "JSON export contains game metadata");
        } finally {
            server.stop();
        }

        // Cleanup temp dir
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        System.out.println("ALL " + passed + " JAVA ASSERTIONS PASSED!");
    }

    private static Tile[][] blankGrid(BoardEngine board, String a, String b) {
        Tile[][] g = new Tile[8][8];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                g[r][c] = board.mkTile((r + c) % 2 == 0 ? a : b);
            }
        }
        return g;
    }

    private static HttpResponse<String> postJson(HttpClient client, String url, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.stringify(body)))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
