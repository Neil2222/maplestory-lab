package net.server.ai.tools;

import net.server.admin.ItemHandbook;
import net.server.ai.AiTool;
import net.server.ai.AiToolContext;
import net.server.ai.AiToolResult;

import java.util.Map;

public class SearchItemTool implements AiTool {
    @Override public String name() { return "search_item"; }
    @Override public String description() { return "Search for items by name. Returns item IDs and names."; }
    @Override public String parameterSpec() { return "query: string (search term)"; }

    @Override
    public AiToolResult execute(AiToolContext context, Map<String, String> args) {
        String query = args.getOrDefault("query", "").toLowerCase();
        if (query.isEmpty()) {
            return new AiToolResult(false, "Search query is required.");
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ItemHandbook.Item item : ItemHandbook.load()) {
            if (item.name().toLowerCase().contains(query)) {
                if (count > 0) sb.append("\r\n");
                sb.append("#i").append(item.id()).append("# #b#t").append(item.id()).append("##k")
                  .append(" (ID:").append(item.id()).append(")");
                count++;
                if (count >= 10) break;
            }
        }

        if (count == 0) {
            return new AiToolResult(true, "No items found matching '" + query + "'.");
        }
        return new AiToolResult(true, count + " items found: " + sb);
    }
}
