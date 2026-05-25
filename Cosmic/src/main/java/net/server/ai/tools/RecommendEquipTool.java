package net.server.ai.tools;

import client.Character;
import client.Job;
import net.server.Server;
import net.server.admin.ItemHandbook;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;
import net.server.world.World;
import server.ItemInformationProvider;

import java.util.*;

public class RecommendEquipTool implements AiTool {
    @Override public String name() { return "recommend_equip"; }
    @Override public String description() { return "Recommend the best equipment for a player based on their level, job, and stats. ALWAYS use this tool instead of making up item IDs. Returns items with icons."; }
    @Override public String parameterSpec() { return "name: string (player name), type: string (weapon, hat, top, overall, bottom, shoes, gloves, shield, cape -- default: weapon)"; }

    private static final Map<String, int[]> TYPE_RANGES = Map.ofEntries(
            Map.entry("weapon",  new int[]{1300000, 1500000}),
            Map.entry("hat",     new int[]{1000000, 1010000}),
            Map.entry("top",     new int[]{1040000, 1050000}),
            Map.entry("overall", new int[]{1050000, 1060000}),
            Map.entry("bottom",  new int[]{1060000, 1070000}),
            Map.entry("shoes",   new int[]{1070000, 1080000}),
            Map.entry("gloves",  new int[]{1080000, 1090000}),
            Map.entry("shield",  new int[]{1090000, 1100000}),
            Map.entry("cape",    new int[]{1100000, 1110000}),
            Map.entry("earring", new int[]{1030000, 1040000})
    );

    private static int jobToReqBit(Job job) {
        int id = job.getId();
        if (id == 0) return 0;
        if (id >= 100 && id < 200) return 1;
        if (id >= 200 && id < 300) return 2;
        if (id >= 300 && id < 400) return 4;
        if (id >= 400 && id < 500) return 8;
        if (id >= 500 && id < 600) return 16;
        if (id >= 1000 && id < 1100) return 0;
        if (id >= 1100 && id < 1200) return 1;
        if (id >= 1200 && id < 1300) return 2;
        if (id >= 1300 && id < 1400) return 4;
        if (id >= 1400 && id < 1500) return 8;
        if (id >= 1500 && id < 1600) return 16;
        return 0;
    }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String name = args.getOrDefault("name", "");
        if (name.isEmpty() && context.callerCharacter() != null) {
            name = context.callerName();
        }
        if (name.isEmpty()) {
            return new AiToolResult(false, "Player name is required.");
        }

        String type = args.getOrDefault("type", "weapon").toLowerCase();
        int[] range = TYPE_RANGES.get(type);
        if (range == null) {
            return new AiToolResult(false, "Unknown type '" + type + "'. Use: " + String.join(", ", TYPE_RANGES.keySet()));
        }

        Character chr = findOnline(name);
        if (chr == null) {
            return new AiToolResult(false, "Player '" + name + "' is not online.");
        }

        int level = chr.getLevel();
        int str = chr.getStr();
        int dex = chr.getDex();
        int inte = chr.getInt();
        int luk = chr.getLuk();
        int jobBit = jobToReqBit(chr.getJob());
        boolean isMage = (jobBit == 2);

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        record Candidate(int id, String name, int atk, int matk, int def, int mdef, int reqLv) {}
        List<Candidate> candidates = new ArrayList<>();

        for (ItemHandbook.Item item : ItemHandbook.load()) {
            if (item.id() < range[0] || item.id() >= range[1]) continue;

            Map<String, Integer> stats = ii.getEquipStats(item.id());
            if (stats == null) continue;
            if (stats.getOrDefault("cash", 0) != 0) continue;

            int reqJob = stats.getOrDefault("reqJob", 0);
            if (reqJob != 0 && jobBit != 0 && (reqJob & jobBit) == 0) continue;

            int reqLv = stats.getOrDefault("reqLevel", 0);
            if (reqLv > level) continue;
            if (stats.getOrDefault("reqSTR", 0) > str) continue;
            if (stats.getOrDefault("reqDEX", 0) > dex) continue;
            if (stats.getOrDefault("reqINT", 0) > inte) continue;
            if (stats.getOrDefault("reqLUK", 0) > luk) continue;

            int pad = stats.getOrDefault("PAD", 0);
            int mad = stats.getOrDefault("MAD", 0);
            int pdd = stats.getOrDefault("PDD", 0);
            int mdd = stats.getOrDefault("MDD", 0);

            candidates.add(new Candidate(item.id(), item.name(), pad, mad, pdd, mdd, reqLv));
        }

        candidates.sort((a, b) -> {
            if (type.equals("weapon")) {
                return isMage ? Integer.compare(b.matk, a.matk) : Integer.compare(b.atk, a.atk);
            }
            return Integer.compare((b.def + b.mdef), (a.def + a.mdef));
        });

        if (candidates.isEmpty()) {
            return new AiToolResult(true, "No " + type + " found for " + name + " (Lv" + level + " " + chr.getJob().name() + ").");
        }

        int show = Math.min(5, candidates.size());
        StringBuilder sb = new StringBuilder();
        sb.append("#eTop ").append(show).append(" ").append(type).append(" for ").append(name)
          .append(" (Lv").append(level).append(" ").append(chr.getJob().name()).append(")#n\r\n\r\n");

        for (int i = 0; i < show; i++) {
            Candidate c = candidates.get(i);
            sb.append("#i").append(c.id).append("# #b#t").append(c.id).append("##k");
            if (c.atk > 0) sb.append(" ATK:#r").append(c.atk).append("#k");
            if (c.matk > 0) sb.append(" MATK:#r").append(c.matk).append("#k");
            if (c.def > 0) sb.append(" DEF:").append(c.def);
            if (c.mdef > 0) sb.append(" MDEF:").append(c.mdef);
            sb.append(" #d(Lv").append(c.reqLv).append(")#k");
            sb.append(" (ID:").append(c.id).append(")");
            if (i < show - 1) sb.append("\r\n");
        }

        return new AiToolResult(true, sb.toString());
    }

    private Character findOnline(String name) {
        for (World w : Server.getInstance().getWorlds()) {
            Character chr = w.getPlayerStorage().getCharacterByName(name);
            if (chr != null) return chr;
        }
        return null;
    }
}
