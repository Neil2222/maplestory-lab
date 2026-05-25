package net.server.ai;

import client.Character;

public record AiToolContext(
        String sessionId,
        Character callerCharacter,
        String callerName
) {}
