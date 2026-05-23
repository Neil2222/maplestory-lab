package net.server.admin;

import client.BuffStat;
import client.Character;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.id.MobId;
import constants.skills.Hero;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Pair;

import java.awt.Point;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Old-school "trainer" UX layered on top of the dashboard. Cheats are
 * per-player toggles; a single scheduled tick iterates the active map
 * every {@link #TICK_MS} milliseconds and re-applies them, so the
 * player can keep moving in-game while loot/mobs are continuously vacuumed.
 */
public final class TrainerEngine {
    private static final Logger log = LoggerFactory.getLogger(TrainerEngine.class);
    private static final long TICK_MS = 300L;

    public enum Cheat { MOB_VAC, AUTO_LOOT, KILL_ALL, GODMODE, HEAL_TICK, NO_KNOCKBACK }

    private static final Map<String, Set<Cheat>> active = new ConcurrentHashMap<>();
    private static final Map<String, Integer> vacOffset = new ConcurrentHashMap<>();
    private static ScheduledExecutorService scheduler;

    private TrainerEngine() {}

    public static synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TrainerEngine");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(TrainerEngine::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("TrainerEngine ticking every {} ms.", TICK_MS);
    }

    public static synchronized void setCheat(String name, Cheat cheat, boolean enabled) {
        Character chr = findOnline(name);
        if (enabled) {
            active.computeIfAbsent(name, k -> EnumSet.noneOf(Cheat.class)).add(cheat);
            // Sticky toggles applied immediately so the trainer state is consistent.
            if (cheat == Cheat.GODMODE && chr != null) chr.setLocalGodMode(true);
            if (cheat == Cheat.NO_KNOCKBACK && chr != null) applyStanceBuff(chr);
        } else {
            Set<Cheat> set = active.get(name);
            if (set != null) {
                set.remove(cheat);
                if (set.isEmpty() && vacOffset.get(name) == null) active.remove(name);
            }
            if (cheat == Cheat.GODMODE && chr != null) chr.setLocalGodMode(false);
            if (cheat == Cheat.NO_KNOCKBACK && chr != null) chr.cancelBuffStats(BuffStat.STANCE);
        }
    }

    public static synchronized void setVacOffset(String name, int offset) {
        vacOffset.put(name, offset);
        // Make sure there's an entry in active so getActive sees this character.
        active.computeIfAbsent(name, k -> EnumSet.noneOf(Cheat.class));
    }

    public static int getVacOffset(String name) {
        return vacOffset.getOrDefault(name, 0);
    }

    public static Map<String, Set<Cheat>> snapshot() {
        Map<String, Set<Cheat>> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Set<Cheat>> e : active.entrySet()) {
            copy.put(e.getKey(), EnumSet.copyOf(e.getValue()));
        }
        return copy;
    }

    public static void onPlayerOffline(String name) {
        active.remove(name);
    }

    private static void tick() {
        try {
            for (Map.Entry<String, Set<Cheat>> e : active.entrySet()) {
                Character chr = findOnline(e.getKey());
                if (chr == null) {
                    // Player logged out; drop their toggles so we stop polling.
                    active.remove(e.getKey());
                    continue;
                }
                Set<Cheat> cheats = e.getValue();
                // Order matters: vac before kill so mobs end up where the player is,
                // then loot after kill so drops are scooped up the same tick.
                if (cheats.contains(Cheat.MOB_VAC))      doMobVac(chr);
                if (cheats.contains(Cheat.KILL_ALL))     doKillAll(chr);
                if (cheats.contains(Cheat.AUTO_LOOT))    doAutoLoot(chr);
                if (cheats.contains(Cheat.HEAL_TICK))    doHealTick(chr);
                if (cheats.contains(Cheat.NO_KNOCKBACK)) applyStanceBuff(chr); // re-applies buff so it never expires
            }
        } catch (Throwable t) {
            log.warn("Trainer tick threw", t);
        }
    }

    private static Character findOnline(String name) {
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterByName(name);
            if (chr != null && chr.isLoggedin()) return chr;
        }
        return null;
    }

    private static boolean isVacuumableMob(Monster m) {
        return !m.getStats().isFriendly()
            && !(m.getId() >= MobId.DEAD_HORNTAIL_MIN && m.getId() <= MobId.HORNTAIL);
    }

    private static void doMobVac(Character player) {
        try {
            MapleMap map = player.getMap();
            if (map == null) return;
            Point ground = map.getGroundBelow(player.getPosition());
            int offset = getVacOffset(player.getName());
            Point target = new Point(ground.x + offset, ground.y);
            List<MapObject> monsters = map.getMapObjectsInRange(
                    player.getPosition(), Double.POSITIVE_INFINITY,
                    Arrays.asList(MapObjectType.MONSTER));
            for (MapObject mo : monsters) {
                Monster m = (Monster) mo;
                if (isVacuumableMob(m)) {
                    m.resetMobPosition(new Point(target));
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Sets BuffStat.STANCE = 100 (100% knockback resist) on the player and notifies the
     * client with a giveBuff packet. Cosmic's stance check is value-based, so the buff
     * is effective regardless of which skill id we attribute it to. We pick Hero.STANCE
     * since it's the canonical Stance skill — the client will recognise it. Duration is
     * a few seconds so it survives until the next tick refreshes it; cancelling the
     * cheat removes it cleanly.
     */
    private static void applyStanceBuff(Character player) {
        try {
            player.setBuffedValue(BuffStat.STANCE, 100);
            List<Pair<BuffStat, Integer>> stat = Collections.singletonList(new Pair<>(BuffStat.STANCE, 100));
            // 5s buff duration — comfortably longer than TICK_MS so it never lapses while active.
            player.sendPacket(PacketCreator.giveBuff(Hero.STANCE, 5000, stat));
        } catch (Exception ignored) {}
    }

    private static void doKillAll(Character player) {
        try {
            MapleMap map = player.getMap();
            if (map == null) return;
            List<MapObject> monsters = map.getMapObjectsInRange(
                    player.getPosition(), Double.POSITIVE_INFINITY,
                    Arrays.asList(MapObjectType.MONSTER));
            for (MapObject mo : monsters) {
                Monster m = (Monster) mo;
                if (isVacuumableMob(m)) {
                    map.damageMonster(player, m, Integer.MAX_VALUE);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void doAutoLoot(Character player) {
        try {
            MapleMap map = player.getMap();
            if (map == null) return;
            List<MapObject> items = map.getMapObjectsInRange(
                    player.getPosition(), Double.POSITIVE_INFINITY,
                    Arrays.asList(MapObjectType.ITEM));
            for (MapObject obj : items) {
                MapItem mapItem = (MapItem) obj;
                mapItem.lockItem();
                try {
                    if (mapItem.isPickedUp()) continue;
                    if (mapItem.getMeso() > 0) {
                        player.gainMeso(mapItem.getMeso(), true);
                    } else if (player.applyConsumeOnPickup(mapItem.getItemId())) {
                        // consumed on pickup
                    } else if (ItemId.isNxCard(mapItem.getItemId())) {
                        player.getCashShop().gainCash(1, mapItem.getItemId() == ItemId.NX_CARD_100 ? 100 : 250);
                    } else if (mapItem.getItem().getItemId() >= 5000000 && mapItem.getItem().getItemId() <= 5000100) {
                        int petId = Pet.createPet(mapItem.getItem().getItemId());
                        if (petId == -1) continue;
                        InventoryManipulator.addById(player.getClient(), mapItem.getItem().getItemId(), mapItem.getItem().getQuantity(), null, petId);
                    } else if (!InventoryManipulator.addFromDrop(player.getClient(), mapItem.getItem(), true)) {
                        continue;
                    }
                    map.pickItemDrop(PacketCreator.removeItemFromMap(mapItem.getObjectId(), 2, player.getId()), mapItem);
                } finally {
                    mapItem.unlockItem();
                }
            }
        } catch (Exception ignored) {}
    }

    private static void doHealTick(Character player) {
        try { player.healHpMp(); } catch (Exception ignored) {}
    }
}
