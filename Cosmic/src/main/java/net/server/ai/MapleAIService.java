package net.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapleAIService {
    private static final Logger log = LoggerFactory.getLogger(MapleAIService.class);

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "gpt-oss:20b";
    private static final int MAX_HISTORY = 20;
    private static final int MAX_TOOL_ITERATIONS = 10;

    private static final MapleAIService INSTANCE = new MapleAIService();

    private final HttpClient httpClient;
    private final Map<String, List<String[]>> sessions = new ConcurrentHashMap<>();
    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    private MapleAIService() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        registerTool(new net.server.ai.tools.GetPlayerInfoTool());
        registerTool(new net.server.ai.tools.GetOnlinePlayersTool());
        registerTool(new net.server.ai.tools.WarpPlayerTool());
        registerTool(new net.server.ai.tools.GetBestTrainingMapTool());
        registerTool(new net.server.ai.tools.GiveItemTool());
        registerTool(new net.server.ai.tools.SearchItemTool());
        registerTool(new net.server.ai.tools.SearchMapTool());
        registerTool(new net.server.ai.tools.RecommendEquipTool());
        registerTool(new net.server.ai.tools.RunCommandTool());
    }

    public static MapleAIService getInstance() {
        return INSTANCE;
    }

    public void registerTool(AiTool tool) {
        tools.put(tool.name(), tool);
    }

    public ChatResult chat(String sessionId, String userMessage, AiToolContext context) {
        List<String[]> history = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
        history.add(new String[]{"user", userMessage});
        trimHistory(history);

        List<String> toolsUsed = new ArrayList<>();

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            String rawJson = callOllamaRaw(sessionId, context);
            if (rawJson == null) {
                history.add(new String[]{"assistant", "My mind is foggy... try again later!"});
                return new ChatResult("My mind is foggy... try again later!", toolsUsed);
            }

            ToolCallRequest nativeToolCall = parseNativeToolCall(rawJson);
            if (nativeToolCall != null) {
                history.add(new String[]{"assistant", "<tool_call>\ntool: " + nativeToolCall.name() + "\n</tool_call>"});
                AiToolResult result = executeTool(nativeToolCall, context);
                toolsUsed.add(nativeToolCall.name());
                history.add(new String[]{"user", "[Tool result for " + nativeToolCall.name() + "]: " + result.data()});
                trimHistory(history);
                continue;
            }

            String response = extractContent(rawJson);
            if (response == null || response.isEmpty()) {
                response = "...";
            }

            ToolCallRequest promptToolCall = parseToolCall(response);
            if (promptToolCall != null) {
                history.add(new String[]{"assistant", response});
                AiToolResult result = executeTool(promptToolCall, context);
                toolsUsed.add(promptToolCall.name());
                history.add(new String[]{"user", "[Tool result for " + promptToolCall.name() + "]: " + result.data()});
                trimHistory(history);
                continue;
            }

            history.add(new String[]{"assistant", response});
            return new ChatResult(response, toolsUsed);
        }

        String lastResponse = "I used too many tools trying to answer that. Here's what I found so far.";
        history.add(new String[]{"assistant", lastResponse});
        return new ChatResult(lastResponse, toolsUsed);
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    private String callOllamaRaw(String sessionId, AiToolContext context) {
        List<String[]> history = sessions.get(sessionId);
        if (history == null) return null;

        String systemPrompt = buildSystemPrompt(context);

        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":\"").append(esc(systemPrompt)).append("\"},");
        for (int i = 0; i < history.size(); i++) {
            String[] msg = history.get(i);
            messages.append("{\"role\":\"").append(msg[0])
                    .append("\",\"content\":\"").append(esc(msg[1])).append("\"}");
            if (i < history.size() - 1) messages.append(",");
        }
        messages.append("]");

        String body = "{\"model\":\"" + MODEL + "\",\"messages\":" + messages
                + ",\"tools\":" + buildToolsJson()
                + ",\"stream\":false}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Ollama raw response length: {}", response.body().length());
            return response.body();
        } catch (Exception e) {
            log.warn("Ollama request failed", e);
            return null;
        }
    }

    private String buildToolsJson() {
        if (tools.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (AiTool tool : tools.values()) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"function\",\"function\":{");
            sb.append("\"name\":\"").append(tool.name()).append("\",");
            sb.append("\"description\":\"").append(esc(tool.description())).append("\",");
            sb.append("\"parameters\":{\"type\":\"object\",\"properties\":{");
            String spec = tool.parameterSpec();
            if (spec != null && !spec.isEmpty()) {
                String[] params = spec.split(",\\s*");
                for (int j = 0; j < params.length; j++) {
                    String[] parts = params[j].split(":\\s*", 2);
                    if (parts.length < 2) continue;
                    String pName = parts[0].trim();
                    String pDesc = parts[1].trim();
                    String pType = pDesc.contains("int") ? "integer" : "string";
                    if (j > 0) sb.append(",");
                    sb.append("\"").append(pName).append("\":{\"type\":\"").append(pType)
                      .append("\",\"description\":\"").append(esc(pDesc)).append("\"}");
                }
            }
            sb.append("}}}}");
            i++;
        }
        sb.append("]");
        return sb.toString();
    }

    ToolCallRequest parseNativeToolCall(String rawJson) {
        int toolCallsIdx = rawJson.indexOf("\"tool_calls\"");
        if (toolCallsIdx == -1) return null;

        int nameIdx = rawJson.indexOf("\"name\"", toolCallsIdx);
        if (nameIdx == -1) return null;

        String toolName = extractStringValue(rawJson, nameIdx);
        if (toolName == null || toolName.isEmpty()) return null;

        Map<String, String> args = new LinkedHashMap<>();
        int argsIdx = rawJson.indexOf("\"arguments\"", toolCallsIdx);
        if (argsIdx != -1) {
            int braceStart = rawJson.indexOf("{", argsIdx);
            if (braceStart != -1) {
                int depth = 0;
                int braceEnd = -1;
                for (int j = braceStart; j < rawJson.length(); j++) {
                    if (rawJson.charAt(j) == '{') depth++;
                    else if (rawJson.charAt(j) == '}') { depth--; if (depth == 0) { braceEnd = j + 1; break; } }
                }
                if (braceEnd > braceStart) {
                    args = parseJsonArgs(rawJson.substring(braceStart, braceEnd));
                }
            }
        }

        return new ToolCallRequest(toolName, args);
    }

    private String extractStringValue(String json, int keyIdx) {
        int colonIdx = json.indexOf(":", keyIdx);
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf("\"", colonIdx + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private String buildSystemPrompt(AiToolContext context) {
        String callerName = context.callerName();
        StringBuilder sb = new StringBuilder();
        sb.append("You are MapleAI, a wise and friendly spirit living inside MapleStory (v83 Cosmic server). ");
        sb.append("You help adventurers with tips, training advice, item info, and can perform game actions. ");
        sb.append("Keep responses concise -- max 2-3 short sentences. Never use markdown formatting. ");
        sb.append("Respond in the same language the player uses.\n\n");
        sb.append("When the player says 'me', 'mij', 'ik', 'my', or refers to themselves, ALWAYS use \"")
          .append(callerName).append("\" as the name parameter in tool calls. NEVER ask for their name.\n");
        sb.append("CRITICAL RULES ABOUT ITEMS:\n");
        sb.append("- NEVER make up or guess item IDs or item names. ALWAYS use recommend_equip or search_item tools first.\n");
        sb.append("- When mentioning items in your response, ALWAYS copy the #i and #t tags from the tool result. Example: #i1302000# #t1302000# shows the item icon and name in-game.\n");
        sb.append("- When a tool returns items with #i...# #t...# tags, include those tags in your response so the player sees item icons.\n");
        sb.append("- You do NOT have real knowledge about MapleStory items, scrolls, or equipment. Your training data is WRONG. ALWAYS use search_item to look up real items. Never describe items from memory.\n\n");

        sb.append(buildPlayerContext(context));

        return sb.toString();
    }

    private String buildPlayerContext(AiToolContext context) {
        client.Character chr = context.callerCharacter();
        if (chr == null) {
            return "Caller: " + context.callerName() + " (dashboard, no character)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Current Player ===\n");
        sb.append("Name: ").append(chr.getName()).append("\n");
        sb.append("Level: ").append(chr.getLevel()).append("\n");
        sb.append("Job: ").append(chr.getJob().name()).append("\n");
        sb.append("Map: ").append(chr.getMapId()).append("\n");
        sb.append("HP: ").append(chr.getHp()).append("/").append(chr.getMaxHp()).append("\n");
        sb.append("MP: ").append(chr.getMp()).append("/").append(chr.getMaxMp()).append("\n");
        sb.append("STR: ").append(chr.getStr())
          .append("  DEX: ").append(chr.getDex())
          .append("  INT: ").append(chr.getInt())
          .append("  LUK: ").append(chr.getLuk()).append("\n");
        sb.append("Mesos: ").append(chr.getMeso()).append("\n");

        var ii = server.ItemInformationProvider.getInstance();
        var equipped = chr.getInventory(client.inventory.InventoryType.EQUIPPED);
        short[] slots = {-1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11};
        String[] slotNames = {"Hat", "Face", "Eye", "Earring", "Top", "Bottom", "Shoes", "Gloves", "Cape", "Shield", "Weapon"};
        sb.append("Equipped:\n");
        for (int i = 0; i < slots.length; i++) {
            var item = equipped.getItem(slots[i]);
            if (item != null) {
                String name = ii.getName(item.getItemId());
                sb.append("  ").append(slotNames[i]).append(": ").append(name != null ? name : "?")
                  .append(" (ID:").append(item.getItemId()).append(")\n");
            }
        }

        return sb.toString();
    }

    record ToolCallRequest(String name, Map<String, String> args) {}

    ToolCallRequest parseToolCall(String response) {
        int start = response.indexOf("<tool_call>");
        int end = response.indexOf("</tool_call>");
        if (start == -1 || end == -1 || end <= start) return null;

        String block = response.substring(start + "<tool_call>".length(), end).trim();

        String toolName = null;
        String argsJson = null;
        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.startsWith("tool:")) {
                toolName = line.substring(5).trim();
            } else if (line.startsWith("args:")) {
                argsJson = line.substring(5).trim();
            }
        }

        if (toolName == null || toolName.isEmpty()) return null;

        Map<String, String> args = new LinkedHashMap<>();
        if (argsJson != null && !argsJson.isEmpty()) {
            args = parseJsonArgs(argsJson);
        }

        return new ToolCallRequest(toolName, args);
    }

    private AiToolResult executeTool(ToolCallRequest request, AiToolContext context) {
        AiTool tool = tools.get(request.name());
        if (tool == null) {
            return new AiToolResult(false, "Unknown tool: " + request.name());
        }
        try {
            return tool.execute(context, request.args());
        } catch (Exception e) {
            log.warn("Tool {} failed", request.name(), e);
            return new AiToolResult(false, "Tool error: " + e.getMessage());
        }
    }

    private void trimHistory(List<String[]> history) {
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    private static final Pattern JSON_KV = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|-?\\d+|true|false|null)");

    Map<String, String> parseJsonArgs(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher m = JSON_KV.matcher(json);
        while (m.find()) {
            String value = m.group(2);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            result.put(m.group(1), value);
        }
        return result;
    }

    private String extractContent(String json) {
        int messageIdx = json.lastIndexOf("\"message\":");
        if (messageIdx == -1) return null;

        String marker = "\"content\":\"";
        int start = json.indexOf(marker, messageIdx);
        if (start == -1) return null;
        start += marker.length();

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> { sb.append('\\'); sb.append(c); }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
