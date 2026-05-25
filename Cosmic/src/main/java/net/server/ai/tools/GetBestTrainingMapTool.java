package net.server.ai.tools;

import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;

import java.util.Map;

public class GetBestTrainingMapTool implements AiTool {
    @Override public String name() { return "get_best_training_map"; }
    @Override public String description() { return "Suggest the best training map for a given level."; }
    @Override public String parameterSpec() { return "level: int"; }

    private record Spot(int minLv, int maxLv, int mapId, String name, String mobs) {}

    private static final Spot[] SPOTS = {
            new Spot(1, 10, 100000000, "Henesys Hunting Ground", "Snails, Shrooms"),
            new Spot(10, 20, 104040000, "Perion Wild Boar Land", "Iron Hog"),
            new Spot(15, 25, 101010100, "Ellinia Tree Dungeon", "Evil Eyes, Jr. Necki"),
            new Spot(20, 30, 103000101, "Kerning City Subway Line 1", "Stirges, Jr. Wraiths"),
            new Spot(25, 35, 105040300, "Ant Tunnel", "Zombie Mushrooms, Horny Mushrooms"),
            new Spot(30, 40, 101030100, "Sleepywood Deep Forest", "Curse Eyes, Cold Eyes"),
            new Spot(35, 45, 105070001, "Drakes Hunting Ground", "Copper Drakes, Dark Drakes"),
            new Spot(40, 55, 600010100, "NLC Haunted House", "Bigfoot/Headless Horseman area"),
            new Spot(50, 60, 211041400, "Orbis Tower", "Lunar Pixies, Luster Pixies"),
            new Spot(55, 70, 220050300, "Ludibrium Toy Factory", "Robo, Master Robo"),
            new Spot(60, 75, 220070301, "Clock Tower Lower Floor", "Platoon Chronos, Master Chronos"),
            new Spot(70, 85, 541010010, "Singapore Ghost Ship", "Mr. Anchor, Slimy"),
            new Spot(75, 90, 600020300, "NLC Phantom Forest", "Bigfoot's footsteps"),
            new Spot(85, 100, 220070201, "Deep Clocktower", "Grim Phantom Watch"),
            new Spot(95, 110, 240040500, "Leafre Skelosaurus", "Skelosaurus, Nest Golem"),
            new Spot(100, 120, 240060200, "Leafre Dragon Forest", "Cornian"),
            new Spot(110, 135, 541010030, "Singapore Mysterious Path 3", "Berserkie"),
            new Spot(120, 150, 240040511, "Leafre Skelegon", "Skelegon"),
            new Spot(140, 170, 240040520, "Leafre Skelosaurus", "Nest Golem"),
            new Spot(150, 200, 270050000, "Temple of Time", "Pink Bean pathway mobs"),
    };

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        int level;
        try {
            level = Integer.parseInt(args.getOrDefault("level", "1"));
        } catch (NumberFormatException e) {
            return new AiToolResult(false, "Invalid level.");
        }

        Spot best = null;
        for (Spot s : SPOTS) {
            if (level >= s.minLv && level <= s.maxLv) {
                best = s;
            }
        }
        if (best == null) {
            best = SPOTS[SPOTS.length - 1];
        }

        return new AiToolResult(true, String.format(
                "Best map for level %d: %s (map ID %d). Mobs: %s. Recommended level range: %d-%d.",
                level, best.name, best.mapId, best.mobs, best.minLv, best.maxLv
        ));
    }
}
