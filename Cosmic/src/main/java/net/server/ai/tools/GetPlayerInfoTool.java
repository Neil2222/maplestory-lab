package net.server.ai.tools;

import client.Character;
import net.server.Server;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;

import java.util.Map;

public class GetPlayerInfoTool implements AiTool {
    @Override public String name() { return "get_player_info"; }
    @Override public String description() { return "Get info about a player (level, job, map, HP, stats)."; }
    @Override public String parameterSpec() { return "name: string (player name, use caller's name if they ask about themselves)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String name = args.getOrDefault("name", "");
        if (name.isEmpty() && context.callerCharacter() != null) {
            name = context.callerName();
        }
        if (name.isEmpty()) {
            return new AiToolResult(false, "Player name is required.");
        }

        Character chr = findOnline(name);
        if (chr == null) {
            return new AiToolResult(false, "Player '" + name + "' is not online.");
        }

        String info = String.format(
                "Name: %s, Level: %d, Job: %s, Map: %d, HP: %d/%d, MP: %d/%d, STR: %d, DEX: %d, INT: %d, LUK: %d, Mesos: %d",
                chr.getName(), chr.getLevel(), chr.getJob().name(), chr.getMapId(),
                chr.getHp(), chr.getMaxHp(), chr.getMp(), chr.getMaxMp(),
                chr.getStr(), chr.getDex(), chr.getInt(), chr.getLuk(),
                chr.getMeso()
        );
        return new AiToolResult(true, info);
    }

    private Character findOnline(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }
}
