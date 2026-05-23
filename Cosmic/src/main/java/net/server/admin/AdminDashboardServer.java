package net.server.admin;

import client.Character;
import client.Client;
import client.command.CommandsExecutor;
import client.inventory.manipulator.InventoryManipulator;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        server.createContext("/trainer", this::handleTrainerPage);
        server.createContext("/api/trainer/state", this::handleTrainerState);
        server.createContext("/api/trainer/toggle", this::handleTrainerToggle);
        server.createContext("/api/trainer/offset", this::handleTrainerOffset);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "AdminDashboard");
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
                    + "\"godmode\":" + player.isLocalGodMode()
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
                case "kick" -> {
                    Character target = requirePlayer(body);
                    String name = target.getName();
                    target.getClient().disconnect(false, false);
                    sendOk(exchange, "Kicked '" + name + "'.");
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
