/*
    Local lab command for Cosmic/HeavenMS-derived servers.
*/
package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DeleteCharCommand extends Command {
    {
        setDescription("Delete an offline character by name.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2 || !params[1].equals("confirm")) {
            player.yellowMessage("Syntax: !deletechar <charactername> confirm");
            return;
        }

        String targetName = params[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.yellowMessage("Log into another GM character before deleting this character.");
            return;
        }

        Character onlineTarget = c.getWorldServer().getPlayerStorage().getCharacterByName(targetName);
        if (onlineTarget != null) {
            player.yellowMessage("Character '" + targetName + "' is online. Log it out before deleting it.");
            return;
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, accountid, name FROM characters WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, targetName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    player.yellowMessage("Character '" + targetName + "' was not found.");
                    return;
                }

                int characterId = rs.getInt("id");
                int accountId = rs.getInt("accountid");
                String canonicalName = rs.getString("name");

                Character target = Character.loadCharFromDB(characterId, c, false);
                if (Character.deleteCharFromDBLocalLab(target, accountId)) {
                    player.dropMessage(5, "Deleted character '" + canonicalName + "'. Return to character select to refresh the list.");
                } else {
                    player.yellowMessage("Failed to delete character '" + canonicalName + "'.");
                }
            }
        } catch (SQLException e) {
            player.yellowMessage("Failed to delete character '" + targetName + "': " + e.getMessage());
        }
    }
}
