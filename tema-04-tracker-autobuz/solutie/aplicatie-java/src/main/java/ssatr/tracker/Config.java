package ssatr.tracker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Setările aplicației, citite din config.properties (ignorat de git) sau din
 * variabilele de mediu cu același nume. Cheia API și parola RabbitMQ nu apar
 * în cod.
 */
public final class Config {

    private final Properties props = new Properties();

    public Config(Path file) throws IOException {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    public String get(String key, String def) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env.trim();
        String v = props.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        return def;
    }

    public String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalStateException("Lipsește " + key
                    + ": completați config.properties (vezi config.example.properties) sau variabila de mediu " + key);
        }
        return v;
    }

    public String student() { return require("STUDENT"); }
    public String apiUrl() { return get("CTP_API_URL", "https://control.aut.utcluj.ro/publictransport"); }
    public String apiKey() { return require("CTP_API_KEY"); }
    public String rabbitHost() { return get("RABBIT_HOST", "control.aut.utcluj.ro"); }
    public int rabbitPort() { return Integer.parseInt(get("RABBIT_PORT", "11156")); }
    public String rabbitUser() { return require("RABBIT_USER"); }
    public String rabbitPass() { return require("RABBIT_PASS"); }
    public String mqttUri() { return get("MQTT_URI", "tcp://control.aut.utcluj.ro:11188"); }
    public String deviceId() { return get("DEVICE_ID", null); }
    public int walkMinutes() { return Integer.parseInt(get("WALK_MIN", "5")); }
}
