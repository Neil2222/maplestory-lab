var MapleAIService = Java.type('net.server.ai.MapleAIService');
var AiToolContext = Java.type('net.server.ai.AiToolContext');
var CommandsExecutor = Java.type('client.command.CommandsExecutor');

function start() {
    var pending = "" + (cm.getPlayer().getAiPendingResponse() || "");
    if (pending.length > 0) {
        cm.getPlayer().setAiPendingResponse(null);
        cm.sendGetText("#b[MapleAI]#k\r\n\r\n" + pending + "\r\n\r\n#eType a follow-up question below, or close this window.#n");
    } else {
        cm.sendGetText("#b[MapleAI]#k\r\n\r\nHoi! Ik ben MapleAI. Stel me een vraag!\r\n\r\n#eType your question below:#n");
    }
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }

    var input = "" + (cm.getText() || "");
    if (input.trim().length === 0) {
        cm.dispose();
        return;
    }

    var player = cm.getPlayer();
    var question = input.trim();
    player.setLastCommandMessage(question);
    cm.dispose();

    CommandsExecutor.getInstance().executeDirect(player.getClient(), "ai", [question]);
}
