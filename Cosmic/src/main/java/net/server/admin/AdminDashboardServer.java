package net.server.admin;

import client.Character;
import client.Client;
import client.command.CommandsExecutor;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import constants.game.GameConstants;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import server.CashShop;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminDashboardServer {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8787;
    private static AdminDashboardServer instance;

    private final HttpServer server;
    private final long startedAt = System.currentTimeMillis();

    private AdminDashboardServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/players", this::handlePlayers);
        server.createContext("/api/action", this::handleAction);
        server.createContext("/api/logs", this::handleLogs);
        server.createContext("/api/commands", this::handleCommands);
        server.createContext("/api/accounts", this::handleAccounts);
        server.createContext("/api/items", this::handleItems);
        server.createContext("/api/icon/", this::handleIcon);
        server.createContext("/api/inventory", this::handleInventory);
        server.createContext("/api/maps", this::handleMaps);
        server.createContext("/trainer", this::handleTrainerPage);
        server.createContext("/api/trainer/state", this::handleTrainerState);
        server.createContext("/api/trainer/toggle", this::handleTrainerToggle);
        server.createContext("/api/trainer/offset", this::handleTrainerOffset);
        // Small fixed pool so concurrent slow requests (icon proxy fetches) don't
        // block fast endpoints like /api/status and /api/action.
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "AdminDashboard-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }));
    }

    public static synchronized void start() {
        if (instance != null) {
            return;
        }

        try {
            instance = new AdminDashboardServer();
            instance.server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start admin dashboard on " + HOST + ":" + PORT, e);
        }
    }

    private static final String DASHBOARD_RESOURCE = "/admin/dashboard.html";
    private static final Path DASHBOARD_LIVE_PATH = Path.of("src", "main", "resources", "admin", "dashboard.html");

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        try {
            send(exchange, 200, "text/html", html());
        } catch (IOException e) {
            send(exchange, 500, "text/plain", "Failed to load dashboard: " + e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        int threads = Thread.activeCount();

        List<String> worldEntries = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            List<Channel> channels = world.getChannels();
            int id = world.getId();
            String name = (id >= 0 && id < GameConstants.WORLD_NAMES.length) ? GameConstants.WORLD_NAMES[id] : ("World " + id);
            worldEntries.add("{"
                    + "\"id\":" + id + ","
                    + "\"name\":\"" + json(name) + "\","
                    + "\"flag\":" + world.getFlag() + ","
                    + "\"eventMessage\":\"" + json(world.getEventMessage()) + "\","
                    + "\"channels\":" + channels.size() + ","
                    + "\"players\":" + world.getPlayerStorage().getAllCharacters().size() + ","
                    + "\"expRate\":" + world.getExpRate() + ","
                    + "\"dropRate\":" + world.getDropRate() + ","
                    + "\"mesoRate\":" + world.getMesoRate()
                    + "}");
        }

        sendJson(exchange, 200, "{"
                + "\"online\":" + Server.getInstance().isOnline() + ","
                + "\"uptimeMs\":" + (System.currentTimeMillis() - startedAt) + ","
                + "\"worlds\":[" + String.join(",", worldEntries) + "],"
                + "\"players\":" + allOnlinePlayers().size() + ","
                + "\"memoryUsedMb\":" + usedMb + ","
                + "\"memoryMaxMb\":" + maxMb + ","
                + "\"threads\":" + threads + ","
                + "\"autosaveMinutes\":5"
                + "}");
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        List<String> rows = new ArrayList<>();
        for (AdminEventLog.Entry e : AdminEventLog.snapshot()) {
            rows.add("{"
                    + "\"t\":" + e.timestamp() + ","
                    + "\"type\":\"" + json(e.type()) + "\","
                    + "\"message\":\"" + json(e.message()) + "\""
                    + "}");
        }
        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static final String TRAINER_RESOURCE = "/admin/trainer.html";
    private static final Path TRAINER_LIVE_PATH = Path.of("src", "main", "resources", "admin", "trainer.html");

    private void handleTrainerPage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        try {
            String body;
            if (Files.exists(TRAINER_LIVE_PATH)) {
                body = Files.readString(TRAINER_LIVE_PATH, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = AdminDashboardServer.class.getResourceAsStream(TRAINER_RESOURCE)) {
                    if (in == null) throw new IOException("trainer.html missing on classpath");
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            send(exchange, 200, "text/html", body);
        } catch (IOException e) {
            send(exchange, 500, "text/plain", "Failed to load trainer: " + e.getMessage());
        }
    }

    private void handleTrainerState(HttpExchange exchange) throws IOException {
        Map<String, Set<TrainerEngine.Cheat>> snap = TrainerEngine.snapshot();
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, Set<TrainerEngine.Cheat>> e : snap.entrySet()) {
            List<String> cs = new ArrayList<>();
            for (TrainerEngine.Cheat c : e.getValue()) cs.add("\"" + c.name() + "\"");
            String name = e.getKey();
            rows.add("\"" + json(name) + "\":{\"cheats\":[" + String.join(",", cs) + "],\"vacOffset\":" + TrainerEngine.getVacOffset(name) + "}");
        }
        sendJson(exchange, 200, "{" + String.join(",", rows) + "}");
    }

    private void handleTrainerOffset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> body = parseJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"name is required\"}");
            return;
        }
        int offset;
        try {
            offset = Integer.parseInt(body.getOrDefault("offset", "0"));
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"offset must be an integer\"}");
            return;
        }
        // Cap to a sensible range — beyond a few hundred px the mobs land off-screen.
        int clamped = Math.max(-500, Math.min(500, offset));
        TrainerEngine.setVacOffset(name, clamped);
        sendJson(exchange, 200, "{\"ok\":true,\"message\":\"Mob Vac offset for " + json(name) + " set to " + clamped + "px.\"}");
    }

    private void handleTrainerToggle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> body = parseJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String name = body.getOrDefault("name", "").trim();
        String cheatName = body.getOrDefault("cheat", "").trim();
        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "false"));
        if (name.isEmpty() || cheatName.isEmpty()) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"name and cheat are required\"}");
            return;
        }
        try {
            TrainerEngine.Cheat cheat = TrainerEngine.Cheat.valueOf(cheatName);
            TrainerEngine.setCheat(name, cheat, enabled);
            AdminEventLog.add("trainer", (enabled ? "enabled " : "disabled ") + cheatName + " on " + name);
            sendJson(exchange, 200, "{\"ok\":true,\"message\":\"" + json(cheatName + (enabled ? " ON" : " OFF") + " for " + name) + "\"}");
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Unknown cheat: " + json(cheatName) + "\"}");
        }
    }

    private void handleMaps(HttpExchange exchange) throws IOException {
        List<MapHandbook.MapInfo> maps = MapHandbook.load();
        StringBuilder sb = new StringBuilder(maps.size() * 80);
        sb.append('[');
        boolean first = true;
        for (MapHandbook.MapInfo m : maps) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(m.id())
              .append(",\"region\":\"").append(json(m.region())).append("\"")
              .append(",\"name\":\"").append(json(m.name())).append("\"}");
        }
        sb.append(']');
        sendJson(exchange, 200, sb.toString());
    }

    private static final Path ADMIN_ICONS_DIR = Path.of("admin-icons");
    private static final String UPSTREAM_ICON_URL = "https://maplestory.io/api/GMS/83/item/%d/icon";
    private static final HttpClient ICON_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Item IDs that MapleStory.io doesn't have — don't re-fetch them this session. */
    private static final java.util.Set<Integer> iconNegative = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Serves icon PNGs from the local <code>admin-icons/</code> folder. On a cache
     * miss, fetches once from maplestory.io's free v83 API and writes the PNG to
     * disk so subsequent requests are served locally. Negative results (upstream
     * 404 or fetch error) are remembered in-memory so we don't repeatedly hammer
     * the upstream for items it doesn't have.
     */
    private void handleIcon(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.endsWith(".png")) last = last.substring(0, last.length() - 4);
        int id;
        try { id = Integer.parseInt(last); }
        catch (NumberFormatException e) { send(exchange, 400, "text/plain", "bad id"); return; }

        Path file = ADMIN_ICONS_DIR.resolve(id + ".png");
        if (!Files.exists(file) && !iconNegative.contains(id)) {
            tryFetchIcon(id, file);
        }
        if (!Files.exists(file)) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private void tryFetchIcon(int id, Path target) {
        try {
            Files.createDirectories(ADMIN_ICONS_DIR);
            HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(UPSTREAM_ICON_URL, id)))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "maplestory-lab-dashboard/1.0")
                    .GET().build();
            HttpResponse<byte[]> resp = ICON_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                Files.write(target, resp.body());
            } else {
                iconNegative.add(id);
            }
        } catch (Exception e) {
            iconNegative.add(id);
        }
    }

    private void handleItems(HttpExchange exchange) throws IOException {
        List<ItemHandbook.Item> items = ItemHandbook.load();
        StringBuilder sb = new StringBuilder(items.size() * 80);
        sb.append('[');
        boolean first = true;
        for (ItemHandbook.Item it : items) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(it.id())
              .append(",\"name\":\"").append(json(it.name())).append("\"")
              .append(",\"cat\":\"").append(json(it.category())).append("\"");
            if (!it.stats().isEmpty()) {
                sb.append(",\"stats\":{");
                boolean firstStat = true;
                for (Map.Entry<String, Integer> e : it.stats().entrySet()) {
                    if (!firstStat) sb.append(',');
                    firstStat = false;
                    sb.append("\"").append(json(e.getKey())).append("\":").append(e.getValue());
                }
                sb.append('}');
            }
            sb.append('}');
        }
        sb.append(']');
        sendJson(exchange, 200, sb.toString());
    }

    private void handleAccounts(HttpExchange exchange) throws IOException {
        // Two queries: all accounts, then all characters joined. Bind together in-memory.
        Map<Integer, List<String>> charactersByAccount = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> accounts = new LinkedHashMap<>();

        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, name, loggedin, banned, characterslots, lastlogin FROM accounts ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    int id = rs.getInt("id");
                    a.put("id", id);
                    a.put("name", rs.getString("name"));
                    a.put("loggedin", rs.getInt("loggedin"));
                    a.put("banned", rs.getInt("banned"));
                    a.put("slots", rs.getInt("characterslots"));
                    java.sql.Timestamp ll = rs.getTimestamp("lastlogin");
                    a.put("lastlogin", ll == null ? 0L : ll.getTime());
                    accounts.put(id, a);
                    charactersByAccount.put(id, new ArrayList<>());
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, accountid, world, name, level, exp, str, dex, `int`, luk, maxhp, maxmp, meso, job, fame, ap, sp, gm, map FROM characters ORDER BY accountid, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int accId = rs.getInt("accountid");
                    List<String> bucket = charactersByAccount.get(accId);
                    if (bucket == null) continue;
                    int charId = rs.getInt("id");
                    String name = rs.getString("name");
                    int jobId = rs.getInt("job");
                    int spForCurrentBook = 0;
                    String spCsv = rs.getString("sp");
                    if (spCsv != null && !spCsv.isBlank()) {
                        int book = constants.game.GameConstants.getSkillBook(jobId);
                        String[] parts = spCsv.split(",");
                        if (book >= 0 && book < parts.length) {
                            try { spForCurrentBook = Integer.parseInt(parts[book].trim()); } catch (NumberFormatException ignored) {}
                        }
                    }
                    bucket.add("{"
                            + "\"id\":" + charId + ","
                            + "\"name\":\"" + json(name) + "\","
                            + "\"world\":" + rs.getInt("world") + ","
                            + "\"level\":" + rs.getInt("level") + ","
                            + "\"job\":" + jobId + ","
                            + "\"exp\":" + rs.getInt("exp") + ","
                            + "\"str\":" + rs.getInt("str") + ","
                            + "\"dex\":" + rs.getInt("dex") + ","
                            + "\"int\":" + rs.getInt("int") + ","
                            + "\"luk\":" + rs.getInt("luk") + ","
                            + "\"maxhp\":" + rs.getInt("maxhp") + ","
                            + "\"maxmp\":" + rs.getInt("maxmp") + ","
                            + "\"meso\":" + rs.getInt("meso") + ","
                            + "\"fame\":" + rs.getInt("fame") + ","
                            + "\"ap\":" + rs.getInt("ap") + ","
                            + "\"sp\":" + spForCurrentBook + ","
                            + "\"gm\":" + rs.getInt("gm") + ","
                            + "\"map\":" + rs.getInt("map") + ","
                            + "\"online\":" + (findOnlineByName(name) != null)
                            + "}");
                }
            }
        } catch (SQLException e) {
            sendJson(exchange, 500, "{\"error\":\"" + json(e.getMessage()) + "\"}");
            return;
        }

        List<String> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Object>> e : accounts.entrySet()) {
            Map<String, Object> a = e.getValue();
            List<String> chars = charactersByAccount.getOrDefault(e.getKey(), List.of());
            // Account-level GM in this fork is derived from the highest GM level among
            // the account's characters — accounts table has no gm column.
            int maxGm = 0;
            for (String charJson : chars) {
                int idx = charJson.indexOf("\"gm\":");
                if (idx >= 0) {
                    int comma = charJson.indexOf(',', idx);
                    int end = comma == -1 ? charJson.length() - 1 : comma;
                    try { maxGm = Math.max(maxGm, Integer.parseInt(charJson.substring(idx + 5, end).trim())); } catch (NumberFormatException ignored) {}
                }
            }
            rows.add("{"
                    + "\"id\":" + a.get("id") + ","
                    + "\"name\":\"" + json((String) a.get("name")) + "\","
                    + "\"loggedin\":" + a.get("loggedin") + ","
                    + "\"banned\":" + a.get("banned") + ","
                    + "\"slots\":" + a.get("slots") + ","
                    + "\"gm\":" + maxGm + ","
                    + "\"lastlogin\":" + a.get("lastlogin") + ","
                    + "\"characters\":[" + String.join(",", chars) + "]"
                    + "}");
        }
        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    /**
     * Computes the in-game CHARACTER STAT window numbers that Cosmic doesn't track
     * server-side, using public-domain v83 formulas plus actual gear sums from
     * Equip.getXxx. Output keys mirror the dashboard sidebar labels.
     *
     * - WDEF / MDEF / ACC / EVA / HANDS / SPEED / JUMP: gear sum (+ stat-based base
     *   contribution where the game window adds one — MDEF gets INT, ACC gets
     *   floor(DEX*0.8)+floor(LUK/2), EVA gets floor((LUK+DEX)/2), SPEED/JUMP base 100).
     * - maxAttack: server-side calculateMaxBaseDamage(totalWatk).
     * - minAttack: max × 0.15 — no-mastery floor used by the GMS client.
     */
    private String buildDisplayStatsJson(Character player) {
        int gWdef = 0, gMdef = 0, gAcc = 0, gEva = 0, gHands = 0, gSpeed = 0, gJump = 0;
        Inventory equipped = player.getInventory(InventoryType.EQUIPPED);
        if (equipped != null) {
            for (Item it : equipped.list()) {
                if (it instanceof Equip eq) {
                    gWdef  += eq.getWdef();
                    gMdef  += eq.getMdef();
                    gAcc   += eq.getAcc();
                    gEva   += eq.getAvoid();
                    gHands += eq.getHands();
                    gSpeed += eq.getSpeed();
                    gJump  += eq.getJump();
                }
            }
        }
        int totalDex = player.getTotalDex();
        int totalLuk = player.getTotalLuk();
        int totalInt = player.getTotalInt();
        int wdef = gWdef;
        int mdef = gMdef + totalInt;
        int acc  = gAcc + (int) Math.floor(totalDex * 0.8) + totalLuk / 2;
        int eva  = gEva + (totalLuk + totalDex) / 2;
        int hands = gHands;
        int speed = 100 + gSpeed;
        int jump  = 100 + gJump;
        int maxAtk = player.calculateMaxBaseDamage(player.getTotalWatk());
        int minAtk = (int) Math.floor(maxAtk * 0.15);

        return "\"dispWdef\":" + wdef
            + ",\"dispMdef\":" + mdef
            + ",\"dispAcc\":" + acc
            + ",\"dispEva\":" + eva
            + ",\"dispHands\":" + hands
            + ",\"dispSpeed\":" + speed
            + ",\"dispJump\":" + jump
            + ",\"maxAttack\":" + maxAtk
            + ",\"minAttack\":" + minAtk;
    }

    /**
     * Returns the four bag inventories + cash inventory of an online character
     * keyed by inventory name: {equip:[...], use:[...], setup:[...], etc:[...], cash:[...]}.
     * Each entry is {slot, itemId, quantity, name}. Equip items also include their
     * scrolled stats so the dashboard can show "Sword #1302000 STR+3 WATK+88".
     */
    private void handleInventory(HttpExchange exchange) throws IOException {
        String name = "";
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "name".equals(pair.substring(0, eq))) {
                    name = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        if (name.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"name query param required\"}");
            return;
        }
        Character player = findOnlineByName(name);
        if (player == null) {
            sendJson(exchange, 404, "{\"error\":\"Character not online\"}");
            return;
        }
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        StringBuilder sb = new StringBuilder("{");
        boolean firstType = true;
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH}) {
            if (!firstType) sb.append(',');
            firstType = false;
            sb.append('"').append(type.name().toLowerCase()).append("\":[");
            Inventory inv = player.getInventory(type);
            if (inv != null) {
                List<String> rows = new ArrayList<>();
                for (Item it : inv.list()) {
                    String iname = iip.getName(it.getItemId());
                    StringBuilder row = new StringBuilder("{");
                    row.append("\"slot\":").append(it.getPosition());
                    row.append(",\"itemId\":").append(it.getItemId());
                    row.append(",\"quantity\":").append(it.getQuantity());
                    row.append(",\"name\":\"").append(json(iname == null ? "" : iname)).append('"');
                    if (it instanceof Equip eq) {
                        List<String> stats = new ArrayList<>();
                        addStat(stats, "STR",   eq.getStr());
                        addStat(stats, "DEX",   eq.getDex());
                        addStat(stats, "INT",   eq.getInt());
                        addStat(stats, "LUK",   eq.getLuk());
                        addStat(stats, "MHP",   eq.getHp());
                        addStat(stats, "MMP",   eq.getMp());
                        addStat(stats, "PAD",   eq.getWatk());
                        addStat(stats, "MAD",   eq.getMatk());
                        addStat(stats, "PDD",   eq.getWdef());
                        addStat(stats, "MDD",   eq.getMdef());
                        addStat(stats, "ACC",   eq.getAcc());
                        addStat(stats, "EVA",   eq.getAvoid());
                        addStat(stats, "Hands", eq.getHands());
                        addStat(stats, "Speed", eq.getSpeed());
                        addStat(stats, "Jump",  eq.getJump());
                        addStat(stats, "tuc",   (int) eq.getUpgradeSlots());
                        row.append(",\"stats\":{").append(String.join(",", stats)).append('}');
                    }
                    row.append('}');
                    rows.add(row.toString());
                }
                sb.append(String.join(",", rows));
            }
            sb.append(']');
        }
        sb.append('}');
        sendJson(exchange, 200, sb.toString());
    }

    /** Appends "key":value to the list when value != 0. */
    private void addStat(List<String> sink, String key, int value) {
        if (value != 0) sink.add("\"" + key + "\":" + value);
    }

    /**
     * Maps an equip item ID to its default equipped slot, using v83 prefix rules.
     * Returns 0 if the item isn't a known equip. For rings (-12..-15), picks the
     * first free ring slot on the player so existing rings aren't clobbered.
     */
    private short defaultEquipSlot(int itemId, Character player) {
        int p4 = itemId / 1000;
        if (p4 == 1002) return -1;   // cap
        if (p4 == 1003) return -2;   // face acc
        if (p4 == 1004) return -3;   // eye acc
        if (p4 == 1005) return -4;   // earring
        int p3 = itemId / 10000;
        if (p3 == 104) return -5;    // top
        if (p3 == 105) return -5;    // overall (uses top slot, fills both slots in-game)
        if (p3 == 106) return -6;    // pants
        if (p3 == 107) return -7;    // shoes
        if (p3 == 108) return -8;    // glove
        if (p3 == 109) return -10;   // shield (slot -10)
        if (p3 == 110) return -9;    // cape (slot -9)
        if (p3 == 112) return -16;   // pendant
        if (p3 == 113) return -17;   // belt
        if (p3 == 114) return -49;   // medal
        if (p3 == 180) return -19;   // pet equip
        if (p3 >= 130 && p3 <= 179) return -11;  // weapon
        if (p3 == 111) {  // ring — pick first free among -12..-15, else -12
            Inventory eq = player.getInventory(InventoryType.EQUIPPED);
            if (eq != null) {
                for (short s = -12; s >= -15; s--) {
                    if (eq.getItem(s) == null) return s;
                }
            }
            return -12;
        }
        return 0;
    }

    /**
     * Sorts an inventory in-place by item name (alphabetical). Mirrors what the
     * in-game sort button does but adapted to be called from the dashboard.
     * Returns the number of items sorted.
     */
    private int sortInventoryForCharacter(Character target, InventoryType invType) {
        Inventory inv = target.getInventory(invType);
        if (inv == null) return 0;
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        List<Item> items = new ArrayList<>();
        List<client.inventory.ModifyInventory> mods = new ArrayList<>();
        inv.lockInventory();
        try {
            for (short i = 1; i <= inv.getSlotLimit(); i++) {
                Item it = inv.getItem(i);
                if (it != null) items.add(it.copy());
            }
            for (Item it : items) {
                inv.removeSlot(it.getPosition());
                mods.add(new client.inventory.ModifyInventory(3, it));
            }
            items.sort((a, b) -> {
                String na = iip.getName(a.getItemId());
                String nb = iip.getName(b.getItemId());
                if (na == null) na = String.valueOf(a.getItemId());
                if (nb == null) nb = String.valueOf(b.getItemId());
                int c = na.compareToIgnoreCase(nb);
                if (c != 0) return c;
                return Integer.compare(a.getItemId(), b.getItemId());
            });
            for (Item it : items) {
                inv.addItem(it);
                mods.add(new client.inventory.ModifyInventory(0, it.copy()));
            }
        } finally {
            inv.unlockInventory();
        }
        target.getClient().sendPacket(tools.PacketCreator.modifyInventory(true, mods));
        target.getClient().sendPacket(tools.PacketCreator.finishedSort2((byte) invType.getType()));
        target.getClient().sendPacket(tools.PacketCreator.enableActions());
        return items.size();
    }

    /** Builds a JSON array of the character's equipped items: [{slot, itemId, name}]. */
    private String buildEquippedJson(Character player) {
        Inventory equipped = player.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) return "[]";
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        List<String> rows = new ArrayList<>();
        for (Item it : equipped.list()) {
            int itemId = it.getItemId();
            String name = iip.getName(itemId);
            rows.add("{\"slot\":" + it.getPosition()
                    + ",\"itemId\":" + itemId
                    + ",\"name\":\"" + json(name == null ? "" : name) + "\"}");
        }
        return "[" + String.join(",", rows) + "]";
    }

    private Character findOnlineByName(String name) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }

    private static final java.util.Set<String> OFFLINE_STAT_COLS = java.util.Set.of(
            "level", "exp", "str", "dex", "int", "luk", "maxhp", "maxmp", "hp", "mp", "meso", "job", "fame", "ap", "gm", "map");

    private record DbCharRef(int id, int accountId, int world, String name) {}

    private DbCharRef loadDbChar(int charId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, accountid, world, name FROM characters WHERE id = ?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new DbCharRef(rs.getInt("id"), rs.getInt("accountid"), rs.getInt("world"), rs.getString("name"));
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private void handleCommands(HttpExchange exchange) throws IOException {
        List<String> names = new ArrayList<>(CommandsExecutor.getInstance().getRegisteredCommandNames());
        java.util.Collections.sort(names);
        List<String> wrapped = new ArrayList<>();
        for (String n : names) {
            wrapped.add("\"" + json(n) + "\"");
        }
        sendJson(exchange, 200, "[" + String.join(",", wrapped) + "]");
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        List<String> players = new ArrayList<>();
        for (Character player : allOnlinePlayers()) {
            CashShop cashShop = player.getCashShop();
            players.add("{"
                    + "\"id\":" + player.getId() + ","
                    + "\"name\":\"" + json(player.getName()) + "\","
                    + "\"account\":\"" + json(player.getClient().getAccountName()) + "\","
                    + "\"level\":" + player.getLevel() + ","
                    + "\"job\":\"" + json(player.getJob().name()) + "\","
                    + "\"jobId\":" + player.getJob().getId() + ","
                    + "\"map\":" + player.getMapId() + ","
                    + "\"world\":" + player.getWorld() + ","
                    + "\"channel\":" + player.getClient().getChannel() + ","
                    + "\"gm\":" + player.gmLevel() + ","
                    + "\"meso\":" + player.getMeso() + ","
                    + "\"nx\":" + cashShop.getCash(CashShop.NX_PREPAID) + ","
                    + "\"str\":" + player.getStr() + ","
                    + "\"dex\":" + player.getDex() + ","
                    + "\"int\":" + player.getInt() + ","
                    + "\"luk\":" + player.getLuk() + ","
                    + "\"maxHp\":" + player.getClientMaxHp() + ","
                    + "\"maxMp\":" + player.getClientMaxMp() + ","
                    + "\"ap\":" + player.getRemainingAp() + ","
                    + "\"sp\":" + player.getRemainingSp() + ","
                    + "\"fame\":" + player.getFame() + ","
                    + "\"exp\":" + player.getExp() + ","
                    + "\"godmode\":" + player.isLocalGodMode() + ","
                    + "\"totalStr\":" + player.getTotalStr() + ","
                    + "\"totalDex\":" + player.getTotalDex() + ","
                    + "\"totalInt\":" + player.getTotalInt() + ","
                    + "\"totalLuk\":" + player.getTotalLuk() + ","
                    + "\"totalWatk\":" + player.getTotalWatk() + ","
                    + "\"totalMagic\":" + player.getTotalMagic() + ","
                    + buildDisplayStatsJson(player) + ","
                    + "\"equipped\":" + buildEquippedJson(player)
                    + "}");
        }
        sendJson(exchange, 200, "[" + String.join(",", players) + "]");
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Method not allowed\"}");
            return;
        }

        Map<String, String> body = parseJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String action = body.getOrDefault("action", "");
        try {
            switch (action) {
                case "godmode" -> {
                    Character target = requirePlayer(body);
                    boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "false"));
                    target.setLocalGodMode(enabled);
                    target.dropMessage(5, "Admin dashboard: godmode " + (enabled ? "enabled" : "disabled") + ".");
                    sendOk(exchange, "Godmode " + (enabled ? "enabled" : "disabled") + " for " + target.getName() + ".");
                }
                case "mobvac" -> {
                    Character target = requirePlayer(body);
                    new client.command.commands.gm4.MobVacCommand().execute(target.getClient(), new String[]{});
                    sendOk(exchange, "Mob vac executed for " + target.getName() + ".");
                }
                case "tubi" -> {
                    Character target = requirePlayer(body);
                    new client.command.commands.gm4.ForceVacCommand().execute(target.getClient(), new String[]{});
                    sendOk(exchange, "Tubi executed for " + target.getName() + ".");
                }
                case "heal" -> {
                    Character target = requirePlayer(body);
                    target.healHpMp();
                    sendOk(exchange, "Healed " + target.getName() + ".");
                }
                case "mesos" -> {
                    Character target = requirePlayer(body);
                    int amount = Integer.parseInt(body.getOrDefault("amount", "0"));
                    target.gainMeso(amount, true);
                    sendOk(exchange, "Gave " + amount + " mesos to " + target.getName() + ".");
                }
                case "nx" -> {
                    Character target = requirePlayer(body);
                    int amount = Integer.parseInt(body.getOrDefault("amount", "0"));
                    target.getCashShop().gainCash(CashShop.NX_PREPAID, amount);
                    target.dropMessage(5, "Admin dashboard: received " + amount + " NX prepaid.");
                    sendOk(exchange, "Gave " + amount + " NX to " + target.getName() + ".");
                }
                case "item" -> {
                    Character target = requirePlayer(body);
                    int itemId = Integer.parseInt(body.getOrDefault("itemId", "0"));
                    short quantity = (short) Math.max(1, Math.min(Short.MAX_VALUE, Integer.parseInt(body.getOrDefault("quantity", "1"))));
                    boolean added = InventoryManipulator.addById(target.getClient(), itemId, quantity);
                    sendOk(exchange, added ? "Gave item " + itemId + " x" + quantity + " to " + target.getName() + "." : "Could not add item.");
                }
                case "rates" -> {
                    int exp = Integer.parseInt(body.getOrDefault("exp", "0"));
                    int drop = Integer.parseInt(body.getOrDefault("drop", "0"));
                    int meso = Integer.parseInt(body.getOrDefault("meso", "0"));
                    // Optional worldId — if omitted/empty, apply to all worlds (legacy behaviour).
                    String worldIdStr = body.getOrDefault("worldId", "").trim();
                    List<World> targets;
                    if (worldIdStr.isEmpty()) {
                        targets = Server.getInstance().getWorlds();
                    } else {
                        int wid = Integer.parseInt(worldIdStr);
                        World w = Server.getInstance().getWorld(wid);
                        if (w == null) {
                            throw new IllegalArgumentException("World " + wid + " does not exist.");
                        }
                        targets = List.of(w);
                    }
                    for (World world : targets) {
                        if (exp > 0)  { world.setExpRate(exp);  LabOverrides.setWorldRate(world.getId(), "exp_rate",  exp); }
                        if (drop > 0) { world.setDropRate(drop); LabOverrides.setWorldRate(world.getId(), "drop_rate", drop); }
                        if (meso > 0) { world.setMesoRate(meso); LabOverrides.setWorldRate(world.getId(), "meso_rate", meso); }
                    }
                    String scope = worldIdStr.isEmpty() ? "all worlds" : ("world " + worldIdStr);
                    sendOk(exchange, "Rates updated for " + scope + " (persisted to lab-overrides).");
                }
                case "setFlag" -> {
                    int wid = Integer.parseInt(body.getOrDefault("worldId", "-1"));
                    int flag = Integer.parseInt(body.getOrDefault("flag", "0"));
                    World w = Server.getInstance().getWorld(wid);
                    if (w == null) {
                        throw new IllegalArgumentException("World " + wid + " does not exist.");
                    }
                    w.setFlag((byte) flag);
                    LabOverrides.setWorldFlag(wid, flag);
                    sendOk(exchange, "World " + wid + " flag set to " + flag + " (persisted).");
                }
                case "deleteCharacter" -> {
                    int charId = Integer.parseInt(body.getOrDefault("charId", "0"));
                    DbCharRef ref = loadDbChar(charId);
                    if (ref == null) {
                        throw new IllegalArgumentException("Character " + charId + " not found.");
                    }
                    if (findOnlineByName(ref.name) != null) {
                        throw new IllegalStateException("Character '" + ref.name + "' is online. Log it out first.");
                    }
                    // World must be active so loadCharFromDB can resolve Server.getWorld(...) for guilds, etc.
                    if (Server.getInstance().getWorld(ref.world) == null) {
                        throw new IllegalStateException("World " + ref.world + " is not active. Bring it up via setWorldCount first.");
                    }
                    Client tempClient = Client.createMock();
                    try {
                        Character target = Character.loadCharFromDB(charId, tempClient, false);
                        boolean ok = Character.deleteCharFromDBLocalLab(target, ref.accountId);
                        if (!ok) throw new IllegalStateException("Delete returned false.");
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error loading char: " + ex.getMessage());
                    }
                    sendOk(exchange, "Deleted character '" + ref.name + "' (id " + charId + ").");
                }
                case "renameCharacter" -> {
                    int charId = Integer.parseInt(body.getOrDefault("charId", "0"));
                    String newName = body.getOrDefault("newName", "").trim();
                    if (newName.length() < 4 || newName.length() > 12) {
                        throw new IllegalArgumentException("Name must be 4-12 characters.");
                    }
                    if (!newName.matches("[A-Za-z0-9]+")) {
                        throw new IllegalArgumentException("Name must be alphanumeric only.");
                    }
                    DbCharRef ref = loadDbChar(charId);
                    if (ref == null) {
                        throw new IllegalArgumentException("Character " + charId + " not found.");
                    }
                    if (findOnlineByName(ref.name) != null) {
                        throw new IllegalStateException("Character '" + ref.name + "' is online. Log it out first.");
                    }
                    try (Connection con = DatabaseConnection.getConnection()) {
                        try (PreparedStatement chk = con.prepareStatement("SELECT id FROM characters WHERE LOWER(name) = LOWER(?) AND id != ?")) {
                            chk.setString(1, newName);
                            chk.setInt(2, charId);
                            try (ResultSet rs = chk.executeQuery()) {
                                if (rs.next()) throw new IllegalStateException("Name '" + newName + "' is already in use.");
                            }
                        }
                        try (PreparedStatement up = con.prepareStatement("UPDATE characters SET name = ? WHERE id = ?")) {
                            up.setString(1, newName);
                            up.setInt(2, charId);
                            up.executeUpdate();
                        }
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error: " + ex.getMessage());
                    }
                    sendOk(exchange, "Renamed character " + charId + " from '" + ref.name + "' to '" + newName + "'.");
                }
                case "setOfflineStat" -> {
                    int charId = Integer.parseInt(body.getOrDefault("charId", "0"));
                    String col = body.getOrDefault("stat", "").toLowerCase();
                    int value = Integer.parseInt(body.getOrDefault("value", "0"));
                    boolean isSp = col.equals("sp");
                    if (!isSp && !OFFLINE_STAT_COLS.contains(col)) {
                        throw new IllegalArgumentException("Stat must be one of " + OFFLINE_STAT_COLS + " or 'sp'.");
                    }
                    DbCharRef ref = loadDbChar(charId);
                    if (ref == null) {
                        throw new IllegalArgumentException("Character " + charId + " not found.");
                    }
                    if (findOnlineByName(ref.name) != null) {
                        throw new IllegalStateException("Character '" + ref.name + "' is online. Use the Players tab for live edits.");
                    }
                    if (isSp) {
                        // SP is stored as a CSV per skillbook. Update the slot corresponding to the
                        // character's current job, preserve the rest, write the CSV back.
                        try (Connection con = DatabaseConnection.getConnection()) {
                            int jobId;
                            String csv;
                            try (PreparedStatement read = con.prepareStatement("SELECT job, sp FROM characters WHERE id = ?")) {
                                read.setInt(1, charId);
                                try (ResultSet rs = read.executeQuery()) {
                                    if (!rs.next()) throw new IllegalArgumentException("Character not found.");
                                    jobId = rs.getInt("job");
                                    csv = rs.getString("sp");
                                }
                            }
                            int book = constants.game.GameConstants.getSkillBook(jobId);
                            String[] parts = csv == null || csv.isBlank() ? new String[]{} : csv.split(",");
                            int len = Math.max(parts.length, book + 1);
                            int[] arr = new int[len];
                            for (int i = 0; i < parts.length; i++) {
                                try { arr[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) {}
                            }
                            arr[book] = Math.max(0, value);
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < arr.length; i++) {
                                if (i > 0) sb.append(',');
                                sb.append(arr[i]);
                            }
                            try (PreparedStatement up = con.prepareStatement("UPDATE characters SET sp = ? WHERE id = ?")) {
                                up.setString(1, sb.toString());
                                up.setInt(2, charId);
                                up.executeUpdate();
                            }
                            sendOk(exchange, "Set SP[book " + book + "] = " + value + " on character " + charId + " (" + ref.name + "). Full SP CSV is now " + sb + ".");
                        } catch (SQLException ex) {
                            throw new IllegalStateException("DB error: " + ex.getMessage());
                        }
                    } else {
                        try (Connection con = DatabaseConnection.getConnection();
                             PreparedStatement up = con.prepareStatement("UPDATE characters SET `" + col + "` = ? WHERE id = ?")) {
                            up.setInt(1, value);
                            up.setInt(2, charId);
                            int affected = up.executeUpdate();
                            if (affected == 0) throw new IllegalStateException("No row updated.");
                        } catch (SQLException ex) {
                            throw new IllegalStateException("DB error: " + ex.getMessage());
                        }
                        sendOk(exchange, "Set " + col + " = " + value + " on character " + charId + " (" + ref.name + ").");
                    }
                }
                case "setWorldCount" -> {
                    int n = Integer.parseInt(body.getOrDefault("count", "0"));
                    int maxAllowed = GameConstants.WORLD_NAMES.length;
                    if (n < 1 || n > maxAllowed) {
                        throw new IllegalArgumentException("World count must be between 1 and " + maxAllowed + ".");
                    }
                    int current = Server.getInstance().getWorlds().size();
                    LabOverrides.setWorldCount(n);
                    if (n > current) {
                        for (int i = current; i < n; i++) {
                            Server.getInstance().addWorld();
                        }
                        sendOk(exchange, "World count set to " + n + ". Added " + (n - current) + " world(s) live; persisted.");
                    } else if (n < current) {
                        int removed = 0;
                        while (Server.getInstance().getWorlds().size() > n) {
                            if (!Server.getInstance().removeWorld()) break;
                            removed++;
                        }
                        if (removed == (current - n)) {
                            sendOk(exchange, "World count set to " + n + ". Removed " + removed + " world(s) live; persisted.");
                        } else {
                            sendOk(exchange, "Partially removed " + removed + " world(s); some had connected players. Reduce activity first.");
                        }
                    } else {
                        sendOk(exchange, "World count already at " + n + ". Persisted.");
                    }
                }
                case "setServerMessage" -> {
                    String msg = body.getOrDefault("message", "");
                    String widStr = body.getOrDefault("worldId", "").trim();
                    List<World> targets = widStr.isEmpty()
                            ? Server.getInstance().getWorlds()
                            : List.of(java.util.Objects.requireNonNull(Server.getInstance().getWorld(Integer.parseInt(widStr)), "Unknown world"));
                    for (World w : targets) {
                        w.setServerMessage(msg);
                    }
                    sendOk(exchange, "Server message updated for " + (widStr.isEmpty() ? "all worlds" : "world " + widStr) + ".");
                }
                case "banAccount" -> {
                    int accountId = Integer.parseInt(body.getOrDefault("accountId", "0"));
                    String reason = body.getOrDefault("reason", "Banned by admin dashboard.").trim();
                    int banFlag = 1;
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement up = con.prepareStatement("UPDATE accounts SET banned = ?, banreason = ? WHERE id = ?")) {
                        up.setInt(1, banFlag);
                        up.setString(2, reason);
                        up.setInt(3, accountId);
                        if (up.executeUpdate() == 0) throw new IllegalArgumentException("Account " + accountId + " not found.");
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error: " + ex.getMessage());
                    }
                    // Kick any online character on that account.
                    int kicked = 0;
                    for (World w : Server.getInstance().getWorlds()) {
                        for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                            if (chr.getClient() != null && chr.getClient().getAccID() == accountId) {
                                chr.getClient().disconnect(false, false);
                                kicked++;
                            }
                        }
                    }
                    sendOk(exchange, "Banned account " + accountId + (kicked > 0 ? " (kicked " + kicked + " online char(s))." : "."));
                }
                case "unbanAccount" -> {
                    int accountId = Integer.parseInt(body.getOrDefault("accountId", "0"));
                    try (Connection con = DatabaseConnection.getConnection()) {
                        try (PreparedStatement up = con.prepareStatement("UPDATE accounts SET banned = -1, banreason = NULL WHERE id = ?")) {
                            up.setInt(1, accountId);
                            if (up.executeUpdate() == 0) throw new IllegalArgumentException("Account " + accountId + " not found.");
                        }
                        try (PreparedStatement del = con.prepareStatement("DELETE FROM ipbans WHERE aid = ?")) {
                            del.setInt(1, accountId);
                            del.executeUpdate();
                        }
                        try (PreparedStatement del = con.prepareStatement("DELETE FROM macbans WHERE aid = ?")) {
                            del.setInt(1, accountId);
                            del.executeUpdate();
                        }
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error: " + ex.getMessage());
                    }
                    sendOk(exchange, "Unbanned account " + accountId + " (cleared banned flag + ip/mac bans).");
                }
                case "setGmLevel" -> {
                    int charId = Integer.parseInt(body.getOrDefault("charId", "0"));
                    int level = Integer.parseInt(body.getOrDefault("level", "0"));
                    if (level < 0 || level > 6) {
                        throw new IllegalArgumentException("GM level must be 0-6.");
                    }
                    DbCharRef ref = loadDbChar(charId);
                    if (ref == null) {
                        throw new IllegalArgumentException("Character " + charId + " not found.");
                    }
                    Character online = findOnlineByName(ref.name);
                    if (online != null) {
                        online.setGMLevel(level);
                        online.getClient().setGMLevel(level);
                    }
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement up = con.prepareStatement("UPDATE characters SET gm = ? WHERE id = ?")) {
                        up.setInt(1, level);
                        up.setInt(2, charId);
                        up.executeUpdate();
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error: " + ex.getMessage());
                    }
                    sendOk(exchange, "GM level for '" + ref.name + "' set to " + level + (online != null ? " (live)" : " (offline DB)") + ".");
                }
                case "createCharacter" -> {
                    int accountId = Integer.parseInt(body.getOrDefault("accountId", "0"));
                    String charName = body.getOrDefault("charName", "").trim();
                    int gender = Integer.parseInt(body.getOrDefault("gender", "0"));
                    int world = Integer.parseInt(body.getOrDefault("world", "0"));
                    if (charName.length() < 4 || charName.length() > 12) {
                        throw new IllegalArgumentException("Name must be 4-12 characters.");
                    }
                    if (!charName.matches("[A-Za-z0-9]+")) {
                        throw new IllegalArgumentException("Name must be alphanumeric.");
                    }
                    if (gender != 0 && gender != 1) {
                        throw new IllegalArgumentException("Gender must be 0 (male) or 1 (female).");
                    }
                    if (Server.getInstance().getWorld(world) == null) {
                        throw new IllegalArgumentException("World " + world + " is not active. Add it via setWorldCount first.");
                    }
                    // Name uniqueness check.
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ck = con.prepareStatement("SELECT id FROM characters WHERE LOWER(name) = LOWER(?)")) {
                        ck.setString(1, charName);
                        try (ResultSet rs = ck.executeQuery()) {
                            if (rs.next()) throw new IllegalStateException("Name '" + charName + "' is already taken.");
                        }
                    } catch (SQLException ex) {
                        throw new IllegalStateException("DB error: " + ex.getMessage());
                    }
                    // Build via Cosmic's own pipeline (Character.getDefault populates keymap,
                    // BuddyList, trockmaps, slot limits, etc.) so the result is byte-for-byte
                    // identical to a normally-created character. Skips CharacterFactory's
                    // sendPacket() because we have no live client to push the new-char entry to.
                    Client tempClient = new Client(Client.Type.LOGIN, -1, null, null, world, 1);
                    tempClient.setAccID(accountId);
                    Character newChar = Character.getDefault(tempClient);
                    newChar.setWorld(world);
                    newChar.setSkinColor(client.SkinColor.getById(0));
                    newChar.setGender(gender);
                    newChar.setName(charName);
                    int hair = gender == 0 ? 30000 : 31000;
                    int face = gender == 0 ? 20000 : 21000;
                    newChar.setHair(hair);
                    newChar.setFace(face);
                    newChar.setLevel(1);
                    newChar.setJob(client.Job.BEGINNER);
                    newChar.setMapId(constants.id.MapId.MUSHROOM_TOWN);
                    client.creator.CharacterFactoryRecipe recipe = new client.creator.CharacterFactoryRecipe(
                        client.Job.BEGINNER, 1, constants.id.MapId.MUSHROOM_TOWN, 0, 0, 0, 0);
                    recipe.addStartingItem(constants.id.ItemId.BEGINNERS_GUIDE, 1, InventoryType.ETC);
                    if (!newChar.insertNewChar(recipe)) {
                        throw new IllegalStateException("insertNewChar returned false — schema mismatch?");
                    }
                    // createCharacterEntry registers the char in the world's online roster.
                    // It NPEs when the account isn't already in the server's accountCharacterCount
                    // map (accounts not logged in this session). The DB INSERT already succeeded,
                    // so the character is real — Cosmic will pick it up on the account's next
                    // login. Swallow the NPE and continue.
                    try {
                        Server.getInstance().createCharacterEntry(newChar);
                    } catch (NullPointerException npe) {
                        // expected for cold accounts; nothing else to do
                    }
                    sendOk(exchange, "Created '" + charName + "' (id " + newChar.getId() + ") on account " + accountId + ", world " + world + ". Log in and apply a preset.");
                }
                case "applyPreset" -> {
                    Character target = requirePlayer(body);
                    int presetJob = Integer.parseInt(body.getOrDefault("job", "-1"));
                    int presetLevel = Integer.parseInt(body.getOrDefault("level", "-1"));
                    int presetStr = Integer.parseInt(body.getOrDefault("str", "4"));
                    int presetDex = Integer.parseInt(body.getOrDefault("dex", "4"));
                    int presetInt = Integer.parseInt(body.getOrDefault("int", "4"));
                    int presetLuk = Integer.parseInt(body.getOrDefault("luk", "4"));
                    int presetMap = Integer.parseInt(body.getOrDefault("mapId", "-1"));
                    int presetMesos = Integer.parseInt(body.getOrDefault("mesos", "0"));
                    boolean maxSkills = Boolean.parseBoolean(body.getOrDefault("maxSkills", "false"));
                    if (presetJob < 0 || presetLevel < 1) {
                        throw new IllegalArgumentException("Preset needs job and level >= 1.");
                    }
                    StringBuilder report = new StringBuilder();
                    Client targetClient = target.getClient();
                    CommandsExecutor exec = CommandsExecutor.getInstance();
                    // 1. Level — Cosmic's LevelCommand pattern handles exp/level-up properly.
                    exec.executeDirect(targetClient, "level", new String[]{ String.valueOf(presetLevel) });
                    report.append("Lv→").append(presetLevel);
                    // 2. Job change.
                    exec.executeDirect(targetClient, "job", new String[]{ String.valueOf(presetJob) });
                    report.append(" · job→").append(presetJob);
                    // 3. Naturalize for clean baseline.
                    exec.executeDirect(targetClient, "naturalize", new String[]{ String.valueOf(presetLevel) });
                    report.append(" · naturalized");
                    // 4. Override stat distribution with preset values via setattr.
                    exec.executeDirect(targetClient, "setattr", new String[]{ "str", String.valueOf(presetStr) });
                    exec.executeDirect(targetClient, "setattr", new String[]{ "dex", String.valueOf(presetDex) });
                    exec.executeDirect(targetClient, "setattr", new String[]{ "int", String.valueOf(presetInt) });
                    exec.executeDirect(targetClient, "setattr", new String[]{ "luk", String.valueOf(presetLuk) });
                    // Burn AP that's now allocated — set remaining to 0 since preset accounted for it.
                    exec.executeDirect(targetClient, "setattr", new String[]{ "ap", "0" });
                    report.append(" · STR/DEX/INT/LUK ").append(presetStr).append("/").append(presetDex).append("/").append(presetInt).append("/").append(presetLuk);
                    // 5. Optional: max all skills in current job tree.
                    if (maxSkills) {
                        exec.executeDirect(targetClient, "maxskill", new String[]{});
                        report.append(" · skills maxed");
                    }
                    // 5b. Optional gear set — equip IDs as a comma-separated list.
                    String equipsCsv = body.getOrDefault("equips", "").trim();
                    int equipped = 0;
                    if (!equipsCsv.isEmpty()) {
                        for (String idStr : equipsCsv.split(",")) {
                            int eqId;
                            try { eqId = Integer.parseInt(idStr.trim()); }
                            catch (NumberFormatException e) { continue; }
                            if (eqId == 0) continue;
                            boolean added = InventoryManipulator.addById(targetClient, eqId, (short) 1);
                            if (!added) continue;
                            // Find the slot the item just landed in by scanning EQUIP for a fresh
                            // copy of that itemId. addById picks the next free slot, so any matching
                            // item with the highest position is likely the new one — but safer: take
                            // the first match we don't already have an equipped twin of.
                            Inventory eqInv = target.getInventory(InventoryType.EQUIP);
                            short srcSlot = -1;
                            for (Item it : eqInv.list()) {
                                if (it.getItemId() == eqId) { srcSlot = it.getPosition(); }
                            }
                            if (srcSlot <= 0) continue;
                            short dst = defaultEquipSlot(eqId, target);
                            if (dst == 0) continue;
                            try { InventoryManipulator.equip(targetClient, srcSlot, dst); equipped++; }
                            catch (Exception ex) { /* slot mismatch / invalid — leave in inventory */ }
                        }
                        report.append(" · equipped ").append(equipped).append("/").append(equipsCsv.split(",").length);
                    }
                    // 6. Warp.
                    if (presetMap >= 0) {
                        server.maps.MapleMap dest = targetClient.getChannelServer().getMapFactory().getMap(presetMap);
                        if (dest != null && target.isAlive()) {
                            target.saveLocationOnWarp();
                            target.changeMap(dest, dest.getRandomPlayerSpawnpoint());
                            report.append(" · warped to ").append(presetMap);
                        }
                    }
                    // 7. Mesos.
                    if (presetMesos > 0) {
                        target.gainMeso(presetMesos, true);
                        report.append(" · +").append(presetMesos).append(" mesos");
                    }
                    sendOk(exchange, "Applied preset to " + target.getName() + ": " + report);
                }
                case "forceWarp" -> {
                    Character target = requirePlayer(body);
                    int mapId = Integer.parseInt(body.getOrDefault("mapId", "-1"));
                    server.maps.MapleMap dest = target.getClient().getChannelServer().getMapFactory().getMap(mapId);
                    if (dest == null) throw new IllegalArgumentException("Map " + mapId + " does not exist.");
                    if (!target.isAlive()) throw new IllegalStateException(target.getName() + " is dead — revive first.");
                    // Direct changeMap, skipping the FieldLimit/event/mini-dungeon checks the
                    // in-game !warp enforces for non-GM players. Dashboard is the admin tool.
                    target.saveLocationOnWarp();
                    target.changeMap(dest, dest.getRandomPlayerSpawnpoint());
                    sendOk(exchange, "Warped " + target.getName() + " to map " + mapId + ".");
                }
                case "kick" -> {
                    Character target = requirePlayer(body);
                    String name = target.getName();
                    target.getClient().disconnect(false, false);
                    sendOk(exchange, "Kicked '" + name + "'.");
                }
                case "unequip" -> {
                    Character target = requirePlayer(body);
                    short slot = Short.parseShort(body.getOrDefault("slot", "0"));
                    if (slot >= 0) throw new IllegalArgumentException("Slot must be negative (equipped position).");
                    Inventory eqp = target.getInventory(InventoryType.EQUIP);
                    short free = eqp.getNextFreeSlot();
                    if (free < 0) throw new IllegalStateException("EQUIP inventory is full — make room first.");
                    InventoryManipulator.unequip(target.getClient(), slot, free);
                    sendOk(exchange, "Unequipped slot " + slot + " on " + target.getName() + " into EQUIP slot " + free + ".");
                }
                case "equip" -> {
                    Character target = requirePlayer(body);
                    short src = Short.parseShort(body.getOrDefault("invSlot", "0"));
                    if (src <= 0) throw new IllegalArgumentException("invSlot must be a positive EQUIP inventory slot.");
                    Inventory eqp = target.getInventory(InventoryType.EQUIP);
                    Item item = eqp.getItem(src);
                    if (item == null) throw new IllegalArgumentException("No item in EQUIP inventory slot " + src + ".");
                    short dst = defaultEquipSlot(item.getItemId(), target);
                    if (dst == 0) throw new IllegalStateException("Could not figure out which slot to equip item " + item.getItemId() + " into.");
                    InventoryManipulator.equip(target.getClient(), src, dst);
                    sendOk(exchange, "Equipped " + item.getItemId() + " from inventory slot " + src + " to equip slot " + dst + ".");
                }
                case "deleteInventoryItem" -> {
                    Character target = requirePlayer(body);
                    String typeStr = body.getOrDefault("invType", "").toLowerCase();
                    short slot = Short.parseShort(body.getOrDefault("slot", "0"));
                    InventoryType invType = switch (typeStr) {
                        case "equip" -> InventoryType.EQUIP;
                        case "use"   -> InventoryType.USE;
                        case "setup" -> InventoryType.SETUP;
                        case "etc"   -> InventoryType.ETC;
                        case "cash"  -> InventoryType.CASH;
                        default -> throw new IllegalArgumentException("invType must be one of equip/use/setup/etc/cash.");
                    };
                    Inventory inv = target.getInventory(invType);
                    if (inv == null) throw new IllegalStateException("No " + typeStr + " inventory.");
                    Item it = inv.getItem(slot);
                    if (it == null) throw new IllegalArgumentException("No item in " + typeStr.toUpperCase() + " slot " + slot + ".");
                    int itemId = it.getItemId();
                    String name = it.getQuantity() > 1 ? (it.getQuantity() + "× ") : "";
                    inv.lockInventory();
                    try {
                        inv.removeSlot(slot);
                    } finally {
                        inv.unlockInventory();
                    }
                    target.getClient().sendPacket(tools.PacketCreator.modifyInventory(true,
                        java.util.List.of(new client.inventory.ModifyInventory(3, it))));
                    sendOk(exchange, "Deleted " + name + "item " + itemId + " from " + typeStr.toUpperCase() + " slot " + slot + " on " + target.getName() + ".");
                }
                case "sortInventory" -> {
                    Character target = requirePlayer(body);
                    String typeStr = body.getOrDefault("invType", "").toLowerCase();
                    InventoryType invType = switch (typeStr) {
                        case "equip" -> InventoryType.EQUIP;
                        case "use"   -> InventoryType.USE;
                        case "setup" -> InventoryType.SETUP;
                        case "etc"   -> InventoryType.ETC;
                        case "cash"  -> InventoryType.CASH;
                        default -> throw new IllegalArgumentException("invType must be one of equip/use/setup/etc/cash.");
                    };
                    int sorted = sortInventoryForCharacter(target, invType);
                    sendOk(exchange, "Sorted " + sorted + " items in " + typeStr.toUpperCase() + " for " + target.getName() + ".");
                }
                case "notice" -> {
                    String message = body.getOrDefault("message", "").trim();
                    if (message.isEmpty()) {
                        throw new IllegalArgumentException("Message is empty.");
                    }
                    for (World world : Server.getInstance().getWorlds()) {
                        world.dropMessage(6, "[Dashboard] " + message);
                    }
                    sendOk(exchange, "Notice sent.");
                }
                case "runCommand" -> {
                    Character target = requirePlayer(body);
                    String cmdName = body.getOrDefault("command", "").trim();
                    String argsStr = body.getOrDefault("args", "").trim();
                    String[] args = argsStr.isBlank() ? new String[]{} : argsStr.split("\\s+");
                    boolean ran = CommandsExecutor.getInstance().executeDirect(target.getClient(), cmdName, args);
                    if (ran) {
                        AdminEventLog.add("command", "!" + cmdName + (argsStr.isBlank() ? "" : " " + argsStr) + " on " + target.getName());
                        sendOk(exchange, "Ran !" + cmdName + " on " + target.getName() + ".");
                    } else {
                        sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Unknown command: " + json(cmdName) + "\"}");
                    }
                }
                case "shutdown" -> {
                    int saved = 0;
                    for (World world : Server.getInstance().getWorlds()) {
                        for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                            if (chr != null && chr.isLoggedin()) {
                                try {
                                    chr.saveCharToDB(false);
                                    saved++;
                                } catch (Exception ex) {
                                    // skip failing chars, keep going
                                }
                            }
                        }
                    }
                    sendOk(exchange, "Saved " + saved + " characters. Shutting down in 1s.");
                    Thread shutdownThread = new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        Server.getInstance().shutdown(false).run();
                    }, "Dashboard-Shutdown");
                    shutdownThread.setDaemon(false);
                    shutdownThread.start();
                }
                default -> sendJson(exchange, 400, "{\"ok\":false,\"message\":\"Unknown action\"}");
            }
        } catch (Exception e) {
            sendJson(exchange, 400, "{\"ok\":false,\"message\":\"" + json(e.getMessage()) + "\"}");
        }
    }

    private Character requirePlayer(Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Missing player name.");
        }

        for (World world : Server.getInstance().getWorlds()) {
            Character player = world.getPlayerStorage().getCharacterByName(name);
            if (player != null) {
                return player;
            }
        }
        throw new IllegalArgumentException("Player not online: " + name);
    }

    private List<Character> allOnlinePlayers() {
        List<Character> players = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            players.addAll(world.getPlayerStorage().getAllCharacters());
        }
        return players;
    }

    private void sendOk(HttpExchange exchange, String message) throws IOException {
        AdminEventLog.add("action", message);
        sendJson(exchange, 200, "{\"ok\":true,\"message\":\"" + json(message) + "\"}");
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json", body);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private Map<String, String> parseJson(String body) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|-?\\d+|true|false|null)", Pattern.CASE_INSENSITIVE).matcher(body);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            result.put(matcher.group(1), value);
        }
        return result;
    }

    private String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String html() throws IOException {
        if (Files.exists(DASHBOARD_LIVE_PATH)) {
            return Files.readString(DASHBOARD_LIVE_PATH, StandardCharsets.UTF_8);
        }
        try (InputStream in = AdminDashboardServer.class.getResourceAsStream(DASHBOARD_RESOURCE)) {
            if (in == null) {
                throw new IOException("Dashboard resource not found on classpath: " + DASHBOARD_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
