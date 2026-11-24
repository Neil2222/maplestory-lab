package config;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.string.CharsetConstants;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;


public class YamlConfig {
    public static final String CONFIG_FILE_NAME = "config.yaml";
    public static final String LOCAL_OVERRIDES_FILE_NAME = "config.local.properties";
    public static final YamlConfig config = loadConfig();

    public List<WorldConfig> worlds;
    public ServerConfig server;

    private static YamlConfig loadConfig() {
        try {
            YamlReader reader = new YamlReader(Files.newBufferedReader(Path.of(CONFIG_FILE_NAME), CharsetConstants.CHARSET));
            YamlConfig config = reader.read(YamlConfig.class);
            reader.close();
            applyLocalOverrides(config);
            return config;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not read config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("Could not successfully parse config file " + YamlConfig.CONFIG_FILE_NAME + ": " + e.getMessage());
        }
    }

    /**
     * Per-machine network addresses, kept out of Git: config.yaml stays on 127.0.0.1 and an optional
     * config.local.properties next to it sets HOST (the public address clients connect to), LANHOST and LOCALHOST
     * for online or LAN play. Without the file nothing changes.
     */
    private static void applyLocalOverrides(YamlConfig config) {
        Path file = Path.of(LOCAL_OVERRIDES_FILE_NAME);
        if (!Files.exists(file) || config.server == null) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(file, CharsetConstants.CHARSET)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + LOCAL_OVERRIDES_FILE_NAME + ": " + e.getMessage());
        }
        String host = props.getProperty("HOST");
        String lanHost = props.getProperty("LANHOST");
        String localHost = props.getProperty("LOCALHOST");
        if (host != null && !host.isBlank()) {
            config.server.HOST = host.trim();
        }
        if (lanHost != null && !lanHost.isBlank()) {
            config.server.LANHOST = lanHost.trim();
        }
        if (localHost != null && !localHost.isBlank()) {
            config.server.LOCALHOST = localHost.trim();
        }
    }
}
