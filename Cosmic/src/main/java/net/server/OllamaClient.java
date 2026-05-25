package net.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OllamaClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "gpt-oss:20b";
    private static final int MAX_HISTORY = 20;
    private static final String SYSTEM_PROMPT =
            "You are MapleAI, a wise and friendly spirit living inside MapleStory. " +
            "You help adventurers with tips, lore, and friendly conversation. " +
            "Keep responses concise — max 2 short sentences. Never use markdown. " +
            "Respond in the same language the player uses.";

    private static final OllamaClient INSTANCE = new OllamaClient();

    private final HttpClient httpClient;
    private final Map<Integer, List<String[]>> conversationHistory = new ConcurrentHashMap<>();

    private OllamaClient() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static OllamaClient getInstance() {
        return INSTANCE;
    }

    public String chat(int characterId, String userMessage) {
        List<String[]> history = conversationHistory.computeIfAbsent(characterId, k -> new ArrayList<>());
        history.add(new String[]{"user", userMessage});

        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }

        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(SYSTEM_PROMPT)).append("\"},");
        for (int i = 0; i < history.size(); i++) {
            String[] msg = history.get(i);
            messages.append("{\"role\":\"").append(msg[0])
                    .append("\",\"content\":\"").append(escapeJson(msg[1])).append("\"}");
            if (i < history.size() - 1) messages.append(",");
        }
        messages.append("]");

        String body = "{\"model\":\"" + MODEL + "\",\"messages\":" + messages + ",\"stream\":false}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String content = extractContent(response.body());

            history.add(new String[]{"assistant", content});
            return content;
        } catch (Exception e) {
            history.removeLast();
            log.warn("Ollama request failed", e);
            return "My mind is foggy right now... try again later!";
        }
    }

    private String extractContent(String json) {
        int messageIdx = json.lastIndexOf("\"message\":");
        if (messageIdx == -1) return "...";

        String marker = "\"content\":\"";
        int start = json.indexOf(marker, messageIdx);
        if (start == -1) return "...";
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

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void clearHistory(int characterId) {
        conversationHistory.remove(characterId);
    }
}
