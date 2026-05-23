package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.Stat;
import client.command.Command;

public class SetAttrCommand extends Command {
    {
        setDescription("Set a single character attribute to an absolute value.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Syntax: !setattr <str|dex|int|luk|maxhp|maxmp|fame|ap> <value>");
            return;
        }

        String attr = params[0].toLowerCase();
        int value;
        try {
            value = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Value must be a number.");
            return;
        }

        int clampedShort = Math.max(0, Math.min(Short.MAX_VALUE, value));

        switch (attr) {
            case "str" -> player.updateStarterStats(clampedShort, player.getDex(), player.getInt(), player.getLuk(), player.getRemainingAp());
            case "dex" -> player.updateStarterStats(player.getStr(), clampedShort, player.getInt(), player.getLuk(), player.getRemainingAp());
            case "int" -> player.updateStarterStats(player.getStr(), player.getDex(), clampedShort, player.getLuk(), player.getRemainingAp());
            case "luk" -> player.updateStarterStats(player.getStr(), player.getDex(), player.getInt(), clampedShort, player.getRemainingAp());
            case "maxhp" -> player.updateMaxHp(Math.max(1, value));
            case "maxmp" -> player.updateMaxMp(Math.max(1, value));
            case "fame" -> {
                player.setFame(value);
                player.updateSingleStat(Stat.FAME, value);
            }
            case "ap" -> player.changeRemainingAp(Math.max(0, value), false);
            case "sp" -> player.updateRemainingSp(Math.max(0, value));
            default -> {
                player.yellowMessage("Unknown attribute: " + attr + ". Use str, dex, int, luk, maxhp, maxmp, fame, ap, or sp.");
                return;
            }
        }
        player.dropMessage(5, "Set " + attr + " to " + value + ".");
    }
}
