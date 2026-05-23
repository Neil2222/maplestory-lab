/*
    Local lab command for Cosmic/HeavenMS-derived servers.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;

public class StarterStatsCommand extends Command {
    private static final int MIN_STAT = 4;
    private static final int STARTER_TOTAL = 25;

    {
        setDescription("Set level 1 beginner starter stats.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 4) {
            player.yellowMessage("Syntax: @starterstats <str> <dex> <int> <luk>");
            player.yellowMessage("Example: @starterstats 4 4 13 4");
            return;
        }

        Job job = player.getJob();
        if (player.getLevel() != 1 || !(job == Job.BEGINNER || job == Job.NOBLESSE || job == Job.LEGEND)) {
            player.yellowMessage("Starter stats can only be changed at level 1 before first job advancement.");
            return;
        }

        try {
            int str = Integer.parseInt(params[0]);
            int dex = Integer.parseInt(params[1]);
            int int_ = Integer.parseInt(params[2]);
            int luk = Integer.parseInt(params[3]);
            int total = str + dex + int_ + luk;

            if (str < MIN_STAT || dex < MIN_STAT || int_ < MIN_STAT || luk < MIN_STAT) {
                player.yellowMessage("Each starter stat must be at least " + MIN_STAT + ".");
                return;
            }

            if (total != STARTER_TOTAL) {
                player.yellowMessage("Starter stats must total " + STARTER_TOTAL + ". Your total is " + total + ".");
                return;
            }

            player.updateStarterStats(str, dex, int_, luk, 0);
            player.dropMessage(5, "Starter stats set to STR " + str + ", DEX " + dex + ", INT " + int_ + ", LUK " + luk + ".");
        } catch (NumberFormatException e) {
            player.yellowMessage("Stats must be numbers.");
        }
    }
}
