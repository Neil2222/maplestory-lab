package net.server.ai;

import java.util.List;

public record ChatResult(String response, List<String> toolsUsed) {}
