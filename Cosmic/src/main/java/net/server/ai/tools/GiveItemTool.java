package net.server.ai.tools;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;

import java.util.Map;

public class GiveItemTool implements AiTool {
    @Override public String name() { return "give_item"; }
    @Override public String description() { return "Give an item to a player by item ID."; }
    @Override public String parameterSpec() { return "name: string, item_id: int, quantity: int (optional, default 1)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String name = args.getOrDefault("name", "");
        if (name.isEmpty() && context.callerCharacter() != null) {
            name = context.callerName();
        }
        if (name.isEmpty()) {
            return new AiToolResult(false, "Player name is required.");
        }

        int itemId;
        try {
            itemId = Integer.parseInt(args.getOrDefault("item_id", "0"));
        } catch (NumberFormatException e) {
            return new AiToolResult(false, "Invalid item_id.");
        }
        if (itemId <= 0) {
            return new AiToolResult(false, "item_id is required.");
        }

        boolean isEquip = (itemId / 1000000 == 1);
        short quantity;
        if (isEquip) {
            quantity = 1;
        } else {
            try {
                quantity = (short) Math.max(1, Math.min(Short.MAX_VALUE,
                        Integer.parseInt(args.getOrDefault("quantity", "1"))));
            } catch (NumberFormatException e) {
                quantity = 1;
            }
        }

        Character chr = findOnline(name);
        if (chr == null) {
            return new AiToolResult(false, "Player '" + name + "' is not online.");
        }

        boolean added = InventoryManipulator.addById(chr.getClient(), itemId, quantity);
        if (added) {
            return new AiToolResult(true, "Gave " + quantity + "x item " + itemId + " to " + name + ".");
        }
        return new AiToolResult(false, "Could not add item — inventory full or invalid item ID.");
    }

    private Character findOnline(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }
}
