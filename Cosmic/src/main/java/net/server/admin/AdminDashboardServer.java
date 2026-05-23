package net.server.admin;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.server.Server;
import net.server.world.World;
import server.CashShop;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        send(exchange, 200, "text/html", html());
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

    private String html() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Maple Lab Admin</title>
                  <style>
                    :root { --bg:#f5f2eb; --panel:#fffaf0; --ink:#241b16; --muted:#776c63; --line:#d7c7aa; --accent:#2e7d74; --danger:#a43131; }
                    * { box-sizing: border-box; }
                    body { margin:0; font:14px/1.4 system-ui, Segoe UI, sans-serif; background:var(--bg); color:var(--ink); }
                    header { padding:18px 22px; background:#2f241d; color:#fff7e8; display:flex; align-items:center; justify-content:space-between; }
                    h1 { margin:0; font-size:20px; letter-spacing:0; }
                    main { padding:18px 22px; display:grid; gap:16px; grid-template-columns: 1.4fr .8fr; }
                    section { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
                    h2 { margin:0 0 12px; font-size:15px; }
                    table { width:100%; border-collapse:collapse; }
                    th, td { padding:8px 7px; border-bottom:1px solid var(--line); text-align:left; white-space:nowrap; }
                    th { color:var(--muted); font-size:12px; font-weight:650; }
                    tr.selected { background:#e7f2ee; }
                    button, input { font:inherit; border-radius:6px; border:1px solid var(--line); padding:8px 9px; background:white; color:var(--ink); }
                    button { cursor:pointer; background:#fff; }
                    button.primary { background:var(--accent); color:white; border-color:var(--accent); }
                    .toolbar, .grid { display:grid; gap:9px; }
                    .grid.two { grid-template-columns: 1fr 1fr; }
                    .grid.three { grid-template-columns: 1fr 1fr 1fr; }
                    .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
                    .muted, .status { color:var(--muted); }
                    .selectedName { font-weight:700; }
                    @media (max-width: 900px) { main { grid-template-columns:1fr; } th:nth-child(3), td:nth-child(3), th:nth-child(5), td:nth-child(5) { display:none; } }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>Maple Lab Admin</h1>
                    <div id="status" class="status"></div>
                  </header>
                  <main>
                    <section>
                      <h2>Online Players</h2>
                      <table>
                        <thead><tr><th>Name</th><th>Account</th><th>Job</th><th>Level</th><th>Map</th><th>Mesos</th><th>NX</th><th>God</th></tr></thead>
                        <tbody id="players"></tbody>
                      </table>
                    </section>
                    <section class="toolbar">
                      <h2>Controls <span id="selected" class="selectedName muted">select a player</span></h2>
                      <div class="row">
                        <button class="primary" onclick="act('godmode',{enabled:true})">Godmode On</button>
                        <button onclick="act('godmode',{enabled:false})">Godmode Off</button>
                        <button onclick="act('heal')">Heal</button>
                      </div>
                      <div class="row">
                        <button onclick="act('mobvac')">Mob Vac</button>
                        <button onclick="act('tubi')">Tubi</button>
                      </div>
                      <div class="grid two">
                        <input id="mesos" type="number" value="1000000">
                        <button onclick="act('mesos',{amount:num('mesos')})">Give Mesos</button>
                        <input id="nx" type="number" value="10000">
                        <button onclick="act('nx',{amount:num('nx')})">Give NX</button>
                        <input id="itemId" type="number" placeholder="Item ID" value="2000005">
                        <input id="quantity" type="number" placeholder="Qty" value="10">
                        <button class="primary" onclick="act('item',{itemId:num('itemId'),quantity:num('quantity')})">Give Item</button>
                      </div>
                      <h2>World Rates</h2>
                      <div class="grid three">
                        <input id="exprate" type="number" value="1" min="1">
                        <input id="droprate" type="number" value="1" min="1">
                        <input id="mesorate" type="number" value="1" min="1">
                      </div>
                      <button onclick="post({action:'rates',exp:num('exprate'),drop:num('droprate'),meso:num('mesorate')})">Set Rates</button>
                      <input id="notice" placeholder="Server notice">
                      <button onclick="post({action:'notice',message:document.getElementById('notice').value})">Send Notice</button>
                      <div id="result" class="status"></div>
                    </section>
                  </main>
                  <script>
                    let selected = null;
                    const fmt = new Intl.NumberFormat();
                    const num = id => Number(document.getElementById(id).value || 0);
                    async function getJson(url){ const r = await fetch(url); return r.json(); }
                    async function post(payload){
                      const r = await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
                      const j = await r.json();
                      document.getElementById('result').textContent = j.message || (j.ok ? 'OK' : 'Failed');
                      await refresh();
                    }
                    function act(action, extra={}){
                      if(!selected){ document.getElementById('result').textContent='Select a player first.'; return; }
                      post({action,name:selected,...extra});
                    }
                    async function refresh(){
                      const [status, players] = await Promise.all([getJson('/api/status'), getJson('/api/players')]);
                      document.getElementById('status').textContent = `${players.length} online · uptime ${Math.floor(status.uptimeMs/1000)}s`;
                      const body = document.getElementById('players');
                      body.innerHTML = '';
                      for(const p of players){
                        const tr = document.createElement('tr');
                        if(p.name === selected) tr.className = 'selected';
                        tr.innerHTML = `<td>${p.name}</td><td>${p.account}</td><td>${p.job}</td><td>${p.level}</td><td>${p.map}</td><td>${fmt.format(p.meso)}</td><td>${fmt.format(p.nx)}</td><td>${p.godmode ? 'on':'off'}</td>`;
                        tr.onclick = () => { selected = p.name; document.getElementById('selected').textContent = p.name; refresh(); };
                        body.appendChild(tr);
                      }
                    }
                    refresh(); setInterval(refresh, 3000);
                  </script>
                </body>
                </html>
                """;
    }
}
