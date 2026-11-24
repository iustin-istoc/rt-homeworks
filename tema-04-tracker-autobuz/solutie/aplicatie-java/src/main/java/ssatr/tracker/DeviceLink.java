package ssatr.tracker;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Legătura MQTT cu placa, pe brokerul cursului, sub ssatr/<student>/devices/<deviceId>/:
 *   commands  aplicație -> placă   TRACK;linie;etaS;plecareS;statii;distM | NOBUS;linie | IDLE | ACK;vehicul
 *   status    placă -> aplicație   deviceId;timestamp;MOD;plecareS   (la fiecare 5 s)
 *   events    placă -> aplicație   deviceId;timestamp;BOARDED
 */
public final class DeviceLink implements AutoCloseable {

    public interface Listener {
        void onStatus(String deviceId, String mode);
        void onBoarded(String deviceId);
    }

    private final MqttClient client;
    private final String prefix;
    private volatile String deviceId;
    private volatile String lastMode = "-";

    public DeviceLink(Config cfg, Listener listener) throws MqttException {
        prefix = "ssatr/" + cfg.student() + "/devices/";
        deviceId = cfg.deviceId();
        client = new MqttClient(cfg.mqttUri(), cfg.student() + "-tracker-" + UUID.randomUUID().toString().substring(0, 8),
                new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setAutomaticReconnect(true);
        opts.setCleanSession(true);

        client.setCallback(new MqttCallbackExtended() {
            @Override public void connectComplete(boolean reconnect, String uri) {
                try {
                    client.subscribe(prefix + "+/status", 1);
                    client.subscribe(prefix + "+/events", 1);
                } catch (MqttException e) {
                    System.err.println("[mqtt] abonare eșuată: " + e.getMessage());
                }
            }

            @Override public void connectionLost(Throwable cause) {
                System.err.println("[mqtt] conexiune pierdută, reconectare automată");
            }

            @Override public void messageArrived(String topic, MqttMessage message) {
                String[] parts = topic.substring(prefix.length()).split("/");
                if (parts.length != 2) return;
                String dev = parts[0];
                if (deviceId == null) {
                    deviceId = dev;
                    System.out.println("[mqtt] placa descoperită: " + dev);
                }
                if (!dev.equals(deviceId)) return;
                String[] f = new String(message.getPayload(), StandardCharsets.UTF_8).trim().split(";");
                if (parts[1].equals("status") && f.length >= 3) {
                    lastMode = f[2];
                    listener.onStatus(dev, f[2]);
                } else if (parts[1].equals("events") && f.length >= 3 && f[2].equals("BOARDED")) {
                    listener.onBoarded(dev);
                }
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        client.connect(opts);  // abonările se fac în connectComplete, și la fiecare reconectare
    }

    public String deviceId() { return deviceId; }

    public String lastMode() { return lastMode; }

    /** Trimite o comandă plăcii. Fără placă descoperită încă, comanda se pierde (următoarea vine în 5 s). */
    public void send(String command) {
        String dev = deviceId;
        if (dev == null || !client.isConnected()) return;
        try {
            MqttMessage m = new MqttMessage(command.getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            client.publish(prefix + dev + "/commands", m);
        } catch (MqttException e) {
            System.err.println("[mqtt] trimitere eșuată: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) client.disconnect();
            client.close();
        } catch (MqttException ignored) {
            // la oprire
        }
    }
}
