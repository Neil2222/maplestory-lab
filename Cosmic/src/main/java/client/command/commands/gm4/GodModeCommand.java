/*
    Local lab command for Cosmic/HeavenMS-derived servers.
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;

public class GodModeCommand extends Command {
    {
        setDescription("Toggle local server-side godmode.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        boolean enabled = player.toggleLocalGodMode();
        player.yellowMessage("Local godmode " + (enabled ? "enabled" : "disabled") + ".");
    }
}
