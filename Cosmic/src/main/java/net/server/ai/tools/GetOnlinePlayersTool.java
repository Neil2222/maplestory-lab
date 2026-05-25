package net.server.ai.tools;

import client.Character;
import net.server.Server;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;

import java.util.Map;

public class GetOnlinePlayersTool implements AiTool {
    @Override public String name() { return "get_online_players"; }
    @Override public String description() { return "List all online players with their level, job, and map."; }
    @Override public String parameterSpec() { return ""; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (count > 0) sb.append("; ");
                sb.append(chr.getName())
                  .append(" (Lv").append(chr.getLevel())
                  .append(" ").append(chr.getJob().name())
                  .append(", map ").append(chr.getMapId()).append(")");
                count++;
            }
        }
        if (count == 0) return new AiToolResult(true, "No players online.");
        return new AiToolResult(true, count + " online: " + sb);
    }
}
