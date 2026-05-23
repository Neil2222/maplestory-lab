package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.Job;
import client.command.Command;

/**
 * Resets a character to "natural" defaults for its current level + job:
 *   - STR/DEX/INT/LUK all 4
 *   - Remaining AP = (5 * level) + 4         (v83 mechanics: 25 starter pool + 5 per level-up - 16 base)
 *   - Max HP and Max MP scaled by job-class midpoints from Character.levelUp()
 *
 * Useful as an "undo" after maxing out a lab character via !maxstat / !setstat.
 * Optional first arg overrides the level used for the computation.
 */
public class NaturalizeCommand extends Command {
    {
        setDescription("Reset character stats to natural defaults for its (current or given) level.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int level;
        try {
            level = params.length >= 1 ? Math.max(1, Integer.parseInt(params[0])) : player.getLevel();
        } catch (NumberFormatException e) {
            player.yellowMessage("Level must be a number.");
            return;
        }

        // Per-level HP/MP midpoints derived from the random ranges in Character.levelUp().
        int hpPerLvl, mpPerLvl;
        Job job = player.getJob();
        if (job.isA(Job.WARRIOR) || job.isA(Job.DAWNWARRIOR1)) {
            hpPerLvl = 26; mpPerLvl = 5;
        } else if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            hpPerLvl = 12; mpPerLvl = 23;
        } else if (job.isA(Job.BOWMAN) || job.isA(Job.THIEF) || (job.getId() > 1299 && job.getId() < 1500)) {
            hpPerLvl = 22; mpPerLvl = 15;
        } else if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
            hpPerLvl = 25; mpPerLvl = 20;
        } else if (job.isA(Job.ARAN1)) {
            hpPerLvl = 46; mpPerLvl = 6;
        } else {
            // Beginner / Noblesse / Legend / unknown -> beginner curve
            hpPerLvl = 14; mpPerLvl = 11;
        }

        int newMaxHp = 50 + (level - 1) * hpPerLvl;
        int newMaxMp = 5 + (level - 1) * mpPerLvl;
        int newRemainingAp = Math.max(0, 5 * level + 4);

        player.updateStarterStats(4, 4, 4, 4, newRemainingAp);
        player.updateMaxHp(newMaxHp);
        player.updateMaxMp(newMaxMp);

        player.dropMessage(5,
            "Naturalized to level " + level + " " + job.name()
            + ": STR/DEX/INT/LUK = 4, remaining AP = " + newRemainingAp
            + ", maxHP = " + newMaxHp + ", maxMP = " + newMaxMp + ".");
    }
}
