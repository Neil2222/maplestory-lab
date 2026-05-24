package net.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses Cosmic's handbook/Map.txt into an in-memory list of v83 maps.
 * Format per usable line: "<id> - <region> - <name>"; section headers like
 * "maple" at the top of the file don't match the regex and are silently skipped.
 */
public final class MapHandbook {
    private static final Logger log = LoggerFactory.getLogger(MapHandbook.class);
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s*-\\s*([^-]+?)\\s*-\\s*(.*)$");

    public record MapInfo(int id, String region, String name) {}

    private static volatile List<MapInfo> cached;

    public static synchronized List<MapInfo> load() {
        if (cached != null) return cached;
        Path file = Path.of("handbook", "Map.txt");
        List<MapInfo> all = new ArrayList<>(5000);
        if (!Files.exists(file)) {
            log.warn("Map.txt not found at {}", file.toAbsolutePath());
            cached = List.of();
            return cached;
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) return;
                try {
                    int id = Integer.parseInt(m.group(1));
                    String region = m.group(2).trim();
                    String name = m.group(3).trim();
                    all.add(new MapInfo(id, region, name));
                } catch (NumberFormatException ignored) {}
            });
        } catch (IOException e) {
            log.warn("Failed to read Map.txt", e);
        }
        all.sort((a, b) -> Integer.compare(a.id, b.id));
        cached = List.copyOf(all);
        log.info("Loaded {} maps from handbook.", cached.size());
        return cached;
    }
}
