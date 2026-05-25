package net.server.ai.tools;

import client.Character;
import client.command.CommandsExecutor;
import net.server.Server;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;

import java.util.Map;
import java.util.Set;

public class RunCommandTool implements AiTool {
    private static final Set<String> BLOCKED = Set.of(
            "shutdown", "shutdownnow", "clearquestcache", "clearquest",
            "supplyratecoupon", "pnpc", "playernpc"
    );

    @Override public String name() { return "run_command"; }
    @Override public String description() { return "Execute a server command on a player. Examples: level 30, job 100, heal, godmode, hide, maxskill, str 999, item 1302000 10, spawn 100100 5, buffme, cleardrops, maxstat, fly"; }
    @Override public String parameterSpec() { return "name: string (player name), command: string (command name without ! prefix), args: string (optional space-separated arguments)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String name = args.getOrDefault("name", "");
        if (name.isEmpty() && context.callerCharacter() != null) {
            name = context.callerName();
        }
        if (name.isEmpty()) {
            return new AiToolResult(false, "Player name is required.");
        }

        String command = args.getOrDefault("command", "").trim().toLowerCase();
        if (command.isEmpty()) {
            return new AiToolResult(false, "Command name is required.");
        }
        if (BLOCKED.contains(command)) {
            return new AiToolResult(false, "Command '" + command + "' is not allowed via AI.");
        }

        String cmdArgs = args.getOrDefault("args", "").trim();

        Character chr = findOnline(name);
        if (chr == null) {
            return new AiToolResult(false, "Player '" + name + "' is not online.");
        }

        String[] argArray = cmdArgs.isEmpty() ? new String[]{} : cmdArgs.split("\\s+");
        boolean executed = CommandsExecutor.getInstance().executeDirect(chr.getClient(), command, argArray);

        if (executed) {
            String full = command + (cmdArgs.isEmpty() ? "" : " " + cmdArgs);
            return new AiToolResult(true, "Executed !" + full + " on " + name + ".");
        }
        return new AiToolResult(false, "Command '" + command + "' not found. Check available commands.");
    }

    private Character findOnline(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }
}
