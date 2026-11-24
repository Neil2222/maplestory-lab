package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import constants.id.NpcId;
import net.server.ai.AiToolContext;
import net.server.ai.ChatResult;
import net.server.ai.MapleAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(AiCommand.class);
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("\\(?ID:?(\\d{7})\\)?");
    // Valid v83 item icon/name tags: #i/#t/#z/#v + exactly 7 digits + '#'. Anything else (#i<mapid>,
    // stray '#', truncation leftovers) references a non-existent item and crashes the client.
    private static final Pattern VALID_ITEM_TAG = Pattern.compile("#[itzv]\\d{7}#");
    // Cap response length: very long text overflows the v83 getText field and can crash the client.
    private static final int MAX_RESPONSE_CHARS = 1500;

    {
        setDescription("Chat with MapleAI.");
    }

    static String sanitize(String text) {
        if (text == null) return null;
        // Replace common unicode chars with ASCII equivalents
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '‘', '’' -> sb.append('\'');
                case '“', '”' -> sb.append('"');
                case '–', '—' -> sb.append('-');
                case '…' -> sb.append("...");
                default -> {
                    if (c <= 0xFF) {
                        sb.append(c);
                    }
                }
            }
        }
        // Truncate before icon injection so a cut never splits an injected #i...# code.
        if (sb.length() > MAX_RESPONSE_CHARS) {
            sb.setLength(MAX_RESPONSE_CHARS);
            sb.append("...");
        }
        // Inject item icons: ID:1234567 or (ID:1234567) -> #i1234567# #t1234567#
        String result = ITEM_ID_PATTERN.matcher(sb).replaceAll("#i$1# #t$1#");
        // Keep only valid item tags; strip every other '#' so no malformed control code crashes the client.
        result = stripInvalidHashCodes(result);
        // Ensure proper line breaks for MapleStory client
        result = result.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        return result;
    }

    /**
     * Keeps valid 7-digit item tags (#i/#t/#z/#v) verbatim and removes every other '#'. Stray or
     * malformed control codes (e.g. #i followed by a 9-digit map id) crash the v83 client.
     */
    private static String stripInvalidHashCodes(String text) {
        Matcher m = VALID_ITEM_TAG.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            // gap before the tag: drop every stray '#'; the matched tag itself is kept verbatim
            out.append(text.substring(last, m.start()).replace("#", ""));
            out.append(m.group());
            last = m.end();
        }
        out.append(text.substring(last).replace("#", ""));
        return out.toString();
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        String message = player.getLastCommandMessage();
        if (message == null || message.isBlank()) {
            player.dropMessage(5, "[MapleAI] Usage: @ai <your message>");
            return;
        }

        player.dropMessage(5, "[MapleAI] Even denken...");

        CompletableFuture.runAsync(() -> {
            try {
                String sessionId = "chr:" + player.getId();
                AiToolContext context = new AiToolContext(sessionId, player, player.getName());
                ChatResult result = MapleAIService.getInstance().chat(sessionId, message, context);

                String response = sanitize(result.response());
                if (response == null || response.isBlank()) {
                    response = "Ik kon geen antwoord genereren. Probeer het opnieuw!";
                }
                log.info("MapleAI response for {}: {} (tools: {})", player.getName(), response, result.toolsUsed());

                player.setAiPendingResponse(response);
                player.getAbstractPlayerInteraction().openNpc(NpcId.MAPLE_ADMINISTRATOR, "mapleai");
            } catch (Exception e) {
                log.error("MapleAI error for {}", player.getName(), e);
                player.dropMessage(5, "[MapleAI] Er ging iets mis: " + e.getMessage());
            }
        });
    }
}
