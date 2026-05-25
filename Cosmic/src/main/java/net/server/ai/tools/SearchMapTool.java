package net.server.ai.tools;

import net.server.admin.MapHandbook;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;

import java.util.Map;

public class SearchMapTool implements AiTool {
    @Override public String name() { return "search_map"; }
    @Override public String description() { return "Search for maps by name. Returns map IDs, region, and name."; }
    @Override public String parameterSpec() { return "query: string (search term)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String query = args.getOrDefault("query", "").toLowerCase();
        if (query.isEmpty()) {
            return new AiToolResult(false, "Search query is required.");
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MapHandbook.MapInfo map : MapHandbook.load()) {
            if (map.name().toLowerCase().contains(query) || map.region().toLowerCase().contains(query)) {
                if (count > 0) sb.append("; ");
                sb.append(map.name()).append(" [").append(map.region()).append("] (ID: ").append(map.id()).append(")");
                count++;
                if (count >= 10) break;
            }
        }

        if (count == 0) {
            return new AiToolResult(true, "No maps found matching '" + query + "'.");
        }
        return new AiToolResult(true, count + " maps found: " + sb);
    }
}
