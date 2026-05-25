package net.server.ai;

import java.util.Map;

public interface AiTool {
    String name();
    String description();
    String parameterSpec();
    AiToolResult execute(AiToolContext context, Map<String, String> args);
}
