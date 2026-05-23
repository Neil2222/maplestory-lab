package net.server.admin;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.world.World;
import server.CashShop;

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
        sendJson(exchange, 200, "{"
                + "\"online\":" + Server.getInstance().isOnline() + ","
                + "\"uptimeMs\":" + (System.currentTimeMillis() - startedAt) + ","
                + "\"worlds\":" + Server.getInstance().getWorlds().size() + ","
                + "\"players\":" + allOnlinePlayers().size()
                + "}");
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
                    + "\"map\":" + player.getMapId() + ","
                    + "\"world\":" + player.getWorld() + ","
                    + "\"channel\":" + player.getClient().getChannel() + ","
                    + "\"gm\":" + player.gmLevel() + ","
                    + "\"meso\":" + player.getMeso() + ","
                    + "\"nx\":" + cashShop.getCash(CashShop.NX_PREPAID) + ","
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
                    for (World world : Server.getInstance().getWorlds()) {
                        if (exp > 0) world.setExpRate(exp);
                        if (drop > 0) world.setDropRate(drop);
                        if (meso > 0) world.setMesoRate(meso);
                    }
                    sendOk(exchange, "Rates updated.");
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
