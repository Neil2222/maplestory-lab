/*
    Local lab command for Cosmic/HeavenMS-derived servers.
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import constants.id.MobId;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;

public class MobVacCommand extends Command {
    {
        setDescription("Move all mobs in the map to your location.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        MapleMap map = player.getMap();
        Point target = map.getGroundBelow(player.getPosition());
        List<MapObject> monsters = map.getMapObjectsInRange(player.getPosition(), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.MONSTER));

        int count = 0;
        for (MapObject monstermo : monsters) {
            Monster monster = (Monster) monstermo;
            if (!monster.getStats().isFriendly() && !(monster.getId() >= MobId.DEAD_HORNTAIL_MIN && monster.getId() <= MobId.HORNTAIL)) {
                monster.resetMobPosition(new Point(target));
                count++;
            }
        }

        player.dropMessage(5, "Moved " + count + " monsters.");
    }
}
