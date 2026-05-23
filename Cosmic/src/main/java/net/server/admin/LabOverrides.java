package net.server.admin;

import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Side-car persistence for runtime tweaks the dashboard applies.
 *
 * Reason this exists: Cosmic loads YamlConfig at startup and never writes back,
 * so any `setExpRate`, `addWorld`, etc. evaporates on restart. Editing config.yaml
 * round-trip is fragile (loses comments, structure) — instead we keep a tiny
 * Properties file next to config.yaml and re-apply it on every boot.
 *
 * Keys:
 *   world.count        = N           // overrides YamlConfig.WORLDS if larger
 *   world.<id>.exp_rate  = N
 *   world.<id>.drop_rate = N
 *   world.<id>.meso_rate = N
 *   world.<id>.flag      = N
 */
public final class LabOverrides {
    private static final Logger log = LoggerFactory.getLogger(LabOverrides.class);
    private static final Path FILE = Path.of("lab-overrides.properties");
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    private LabOverrides() {}

    public static synchronized void load() {
        loaded = true;
        if (!Files.exists(FILE)) {
            log.info("No {} found; lab overrides start empty.", FILE);
            return;
        }
        try (var in = Files.newBufferedReader(FILE)) {
            props.load(in);
            log.info("Loaded {} entries from {}", props.size(), FILE);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", FILE, e.getMessage());
        }
    }

    public static synchronized void save() {
        try (var out = Files.newBufferedWriter(FILE)) {
            props.store(out, "Lab runtime overrides — written by AdminDashboardServer. Reapplied on startup.");
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", FILE, e.getMessage());
        }
    }

    public static synchronized int getWorldCountOverride(int defaultCount) {
        if (!loaded) load();
        String v = props.getProperty("world.count");
        try {
            return v == null ? defaultCount : Math.max(defaultCount, Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return defaultCount;
        }
    }

    public static synchronized void setWorldCount(int n) {
        if (!loaded) load();
        props.setProperty("world.count", Integer.toString(n));
        save();
    }

    public static synchronized void setWorldRate(int worldId, String type, int value) {
        if (!loaded) load();
        props.setProperty("world." + worldId + "." + type, Integer.toString(value));
        save();
    }

    public static synchronized void setWorldFlag(int worldId, int flag) {
        if (!loaded) load();
        props.setProperty("world." + worldId + ".flag", Integer.toString(flag));
        save();
    }

    /** Apply rate and flag overrides to live worlds after they've been constructed by Server.init. */
    public static synchronized void applyToLiveWorlds() {
        if (!loaded) load();
        for (World world : Server.getInstance().getWorlds()) {
            int id = world.getId();
            String prefix = "world." + id + ".";
            applyInt(prefix + "exp_rate",  world::setExpRate);
            applyInt(prefix + "drop_rate", world::setDropRate);
            applyInt(prefix + "meso_rate", world::setMesoRate);
            String flagVal = props.getProperty(prefix + "flag");
            if (flagVal != null) {
                try { world.setFlag((byte) Integer.parseInt(flagVal)); }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    private static void applyInt(String key, java.util.function.IntConsumer setter) {
        String v = props.getProperty(key);
        if (v == null) return;
        try { setter.accept(Integer.parseInt(v)); }
        catch (NumberFormatException ignored) {}
    }
}
