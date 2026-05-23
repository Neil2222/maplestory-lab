package net.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses Cosmic's handbook/*.txt files into an in-memory list of give-able items.
 *
 * Format per line: "<itemId> - <name> - <description>". Whitespace is trimmed.
 * Lines that don't match are skipped silently (some handbook files have stray
 * blank lines or section headers).
 *
 * We only load categories that make sense to hand to a player from the
 * dashboard: Use, Etc, Setup, Cash, Pet, and every file under Equip/.
 * Map/Mob/NPC/Skill/Quest/Job/Gender/Commands aren't items.
 */
public final class ItemHandbook {
    private static final Logger log = LoggerFactory.getLogger(ItemHandbook.class);
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s*-\\s*([^-]+?)\\s*-\\s*(.*)$");

    public record Item(int id, String name, String category, String description, Map<String, Integer> stats) {}

    private static volatile List<Item> cached;

    private ItemHandbook() {}

    public static synchronized List<Item> load() {
        if (cached != null) return cached;
        List<Item> all = new ArrayList<>(25000);

        Path handbook = Path.of("handbook");
        if (!Files.isDirectory(handbook)) {
            log.warn("handbook/ directory not found relative to working dir {} — items list will be empty.", Path.of("").toAbsolutePath());
            cached = List.of();
            return cached;
        }

        // Top-level files we care about. Anything else (Mob.txt, Map.txt, etc.)
        // is intentionally skipped.
        String[] topLevel = { "Use.txt", "Etc.txt", "Setup.txt", "Cash.txt", "Pet.txt" };
        for (String fname : topLevel) {
            Path p = handbook.resolve(fname);
            if (Files.exists(p)) parseInto(p, fname.replace(".txt", ""), all);
        }

        // Every file under Equip/ — each filename is the equip slot (Cap, Coat, etc.).
        Path equipDir = handbook.resolve("Equip");
        if (Files.isDirectory(equipDir)) {
            try (Stream<Path> walk = Files.list(equipDir)) {
                walk.filter(p -> p.toString().endsWith(".txt"))
                    .forEach(p -> parseInto(p, "Equip/" + p.getFileName().toString().replace(".txt", ""), all));
            } catch (IOException e) {
                log.warn("Failed to list Equip handbook directory", e);
            }
        }

        // Enrich equip items with canonical name (from WZ via ItemInformationProvider)
        // and a slim copy of equip stats. We only call IIP for items whose category
        // starts with "Equip/" — for Use/Etc/Setup/etc. the handbook name is fine and
        // there are no equip stats to surface.
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        for (int i = 0; i < all.size(); i++) {
            Item it = all.get(i);
            if (!it.category.startsWith("Equip/")) continue;
            try {
                String canonical = iip.getName(it.id);
                Map<String, Integer> raw = iip.getEquipStats(it.id);
                Map<String, Integer> slim = raw == null ? Map.of() : slimStats(raw);
                all.set(i, new Item(it.id, canonical != null && !canonical.isBlank() ? canonical : it.name,
                        it.category, it.description, slim));
            } catch (Exception e) {
                // WZ data missing or malformed — keep the handbook-only record.
            }
        }

        all.sort((a, b) -> Integer.compare(a.id, b.id));
        cached = List.copyOf(all);
        log.info("Loaded {} items from handbook (equips enriched with IIP stats).", cached.size());
        return cached;
    }

    /** Drops zero values and uninteresting keys so the response payload stays small. */
    private static Map<String, Integer> slimStats(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            Integer v = e.getValue();
            if (v == null || v == 0) continue;
            String k = e.getKey();
            if (k.equals("cursed") || k.equals("success") || k.equals("fs") || k.equals("cash")) continue;
            out.put(k, v);
        }
        return out;
    }

    private static void parseInto(Path file, String category, List<Item> sink) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) return;
                try {
                    int id = Integer.parseInt(m.group(1));
                    String name = m.group(2).trim();
                    String desc = m.group(3).trim();
                    sink.add(new Item(id, name, category, desc, Map.of()));
                } catch (NumberFormatException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Failed to read handbook file {}: {}", file, e.getMessage());
        }
    }
}
