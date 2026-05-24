package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.command.Command;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.List;

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

        // Per-level HP/MP gain ranges, mirroring the Randomizer calls in
        // Character.levelUp(). Picking a fresh roll per level here makes the
        // resulting character look like 'what if I'd just levelled normally',
        // not a deterministic always-the-same midpoint character.
        int hpMin, hpMax, mpMin, mpMax;
        Job job = player.getJob();
        if (job.isA(Job.WARRIOR) || job.isA(Job.DAWNWARRIOR1)) {
            hpMin = 24; hpMax = 28; mpMin = 4;  mpMax = 6;
        } else if (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1)) {
            hpMin = 10; hpMax = 14; mpMin = 22; mpMax = 24;
        } else if (job.isA(Job.BOWMAN) || job.isA(Job.THIEF) || (job.getId() > 1299 && job.getId() < 1500)) {
            hpMin = 20; hpMax = 24; mpMin = 14; mpMax = 16;
        } else if (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1)) {
            hpMin = 22; hpMax = 28; mpMin = 18; mpMax = 23;
        } else if (job.isA(Job.ARAN1)) {
            hpMin = 44; hpMax = 48; mpMin = 4;  mpMax = 8;
        } else {
            // Beginner / Noblesse / Legend / unknown -> beginner curve
            hpMin = 12; hpMax = 16; mpMin = 10; mpMax = 12;
        }

        int newMaxHp = 50;
        int newMaxMp = 5;
        for (int i = 1; i < level; i++) {
            newMaxHp += Randomizer.rand(hpMin, hpMax);
            newMaxMp += Randomizer.rand(mpMin, mpMax);
        }
        int newRemainingAp = Math.max(0, 5 * level + 4);

        // Wipe learned skills: changeSkillLevel(..., -1, ...) removes from both
        // memory and the skills table. Snapshot the keyset first to avoid
        // ConcurrentModificationException while iterating.
        List<Skill> toWipe = new ArrayList<>(player.getSkills().keySet());
        for (Skill s : toWipe) {
            player.changeSkillLevel(s, (byte) -1, 0, -1);
        }

        // Natural SP for the level: 3 per level after 9 for any non-Beginner branch.
        // Beginner/Noblesse/Legend gain no SP per level in vanilla v83.
        // (Evan splits SP across job-tier books — updateRemainingSp here only
        // touches the current book, so on Evan the earlier tier books are left
        // untouched. The standard Adventurer/Cygnus/Aran path stores all SP
        // in book 0 so refilling that one slot is correct.)
        boolean noBranchSp = job.isA(Job.BEGINNER) || job.isA(Job.NOBLESSE) || job.isA(Job.LEGEND);
        int newSp = noBranchSp ? 0 : 3 * Math.max(0, level - 9);

        player.updateStarterStats(4, 4, 4, 4, newRemainingAp);
        player.updateRemainingSp(newSp);
        player.updateMaxHp(newMaxHp);
        player.updateMaxMp(newMaxMp);

        player.dropMessage(5,
            "Naturalized to level " + level + " " + job.name()
            + ": STR/DEX/INT/LUK = 4, AP = " + newRemainingAp
            + ", SP = " + newSp + ", HP = " + newMaxHp + ", MP = " + newMaxMp
            + ", wiped " + toWipe.size() + " skill(s).");
    }
}
