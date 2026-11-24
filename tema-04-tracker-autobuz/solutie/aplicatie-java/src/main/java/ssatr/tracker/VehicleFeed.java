package ssatr.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Pozițiile vehiculelor din RabbitMQ: o coadă proprie, temporară, legată de
 * exchange-ul fanout cluj.vehicles. Fiecare mesaj este un vehicul, în JSON.
 * Nu se folosește coada comună ctp.cluj.vehicles (și-ar împărți mesajele cu colegii).
 */
public final class VehicleFeed implements AutoCloseable {

    private static final String EXCHANGE = "cluj.vehicles";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection connection;
    private final Channel channel;

    public VehicleFeed(Config cfg, Consumer<Tracker.Vehicle> sink) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(cfg.rabbitHost());
        f.setPort(cfg.rabbitPort());
        f.setUsername(cfg.rabbitUser());
        f.setPassword(cfg.rabbitPass());
        f.setAutomaticRecoveryEnabled(true);  // reconectare automată, cu coada și legătura refăcute

        connection = f.newConnection("ssatr-tracker-" + cfg.student());
        channel = connection.createChannel();
        channel.exchangeDeclare(EXCHANGE, "fanout");             // exact ca pe server: fanout, nedurabil
        String queue = channel.queueDeclare().getQueue();         // exclusivă, ștearsă la închidere
        channel.queueBind(queue, EXCHANGE, "");

        channel.basicConsume(queue, true, (tag, msg) -> {
            try {
                Tracker.Vehicle v = parse(new String(msg.getBody(), StandardCharsets.UTF_8));
                if (v != null) sink.accept(v);
            } catch (Exception e) {
                // un mesaj stricat nu oprește aplicația
                System.err.println("[rabbitmq] mesaj ignorat: " + e.getMessage());
            }
        }, tag -> { });
    }

    /** Câmpurile lipsă (trip_id null, route_id absent etc.) sunt tratate, nu presupuse. */
    static Tracker.Vehicle parse(String json) throws Exception {
        JsonNode n = JSON.readTree(json);
        if (!n.hasNonNull("id") || !n.hasNonNull("route_id") || !n.hasNonNull("lat") || !n.hasNonNull("lon")) {
            return null;
        }
        String trip = n.hasNonNull("trip_id") && !n.get("trip_id").asText().isBlank() ? n.get("trip_id").asText() : null;
        Instant ts = null;
        if (n.hasNonNull("timestamp")) {
            try {
                ts = Instant.parse(n.get("timestamp").asText());
            } catch (Exception ignored) {
                // timestamp ilizibil: vehiculul e ignorat de Tracker
            }
        }
        return new Tracker.Vehicle(n.get("id").asInt(), n.path("label").asText("?"), n.get("route_id").asInt(),
                trip, n.get("lat").asDouble(), n.get("lon").asDouble(), ts);
    }

    @Override
    public void close() {
        try {
            if (channel.isOpen()) channel.close();
            if (connection.isOpen()) connection.close();
        } catch (Exception ignored) {
            // la oprire, erorile de închidere nu mai contează
        }
    }
}
