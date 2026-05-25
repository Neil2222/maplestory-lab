package net.server.ai.tools;

import client.Character;
import net.server.Server;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;
import server.maps.MapleMap;

import java.util.Map;

public class WarpPlayerTool implements AiTool {
    @Override public String name() { return "warp_player"; }
    @Override public String description() { return "Teleport a player to a map by map ID."; }
    @Override public String parameterSpec() { return "name: string (player name), map_id: int (target map ID)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String name = args.getOrDefault("name", "");
        if (name.isEmpty() && context.callerCharacter() != null) {
            name = context.callerName();
        }
        if (name.isEmpty()) {
            return new AiToolResult(false, "Player name is required.");
        }

        int mapId;
        try {
            mapId = Integer.parseInt(args.getOrDefault("map_id", "-1"));
        } catch (NumberFormatException e) {
            return new AiToolResult(false, "Invalid map_id.");
        }

        Character chr = findOnline(name);
        if (chr == null) {
            return new AiToolResult(false, "Player '" + name + "' is not online.");
        }
        if (!chr.isAlive()) {
            return new AiToolResult(false, name + " is dead — revive first.");
        }

        MapleMap dest = chr.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (dest == null) {
            return new AiToolResult(false, "Map " + mapId + " does not exist.");
        }

        chr.saveLocationOnWarp();
        chr.changeMap(dest, dest.getRandomPlayerSpawnpoint());
        return new AiToolResult(true, "Warped " + name + " to map " + mapId + " (" + dest.getMapName() + ").");
    }

    private Character findOnline(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }
}
