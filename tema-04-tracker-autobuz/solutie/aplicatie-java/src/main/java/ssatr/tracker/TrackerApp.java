package ssatr.tracker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracker personal de autobuz: aplicația Java.
 *
 * Utilizatorul alege în consolă linia, sensul și stația. Aplicația primește
 * pozițiile vehiculelor din RabbitMQ, estimează la fiecare 5 s cel mai apropiat
 * autobuz și îi trimite plăcii (MQTT) cât mai are până la stație și în cât timp
 * trebuie să plece utilizatorul. Când placa raportează BOARDED (butonul BOOT),
 * aplicația înregistrează urcarea și oprește urmărirea.
 *
 * Rulare: mvn -q compile exec:java   (cu config.properties completat)
 */
public final class TrackerApp {

    private static final int UPDATE_PERIOD_S = 5;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Config cfg;
    private final CtpApi api;
    private final Tracker tracker = new Tracker();
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private DeviceLink device;

    // urmărirea curentă (null = nu urmărim nimic); scrise din consolă, citite de planificator și de MQTT
    private volatile String lineName;
    private volatile String stopName;
    private volatile int walkS;
    private volatile Optional<Tracker.Estimate> lastEstimate = Optional.empty();

    private TrackerApp(Config cfg) {
        this.cfg = cfg;
        this.api = new CtpApi(cfg.apiUrl(), cfg.apiKey());
    }

    public static void main(String[] args) throws Exception {
        new TrackerApp(new Config(Path.of("config.properties"))).run();
    }

    private void run() throws Exception {
        System.out.println("Încarc liniile...");
        List<CtpApi.Line> lines = api.lines();
        System.out.println(lines.size() + " linii.");

        device = new DeviceLink(cfg, new DeviceLink.Listener() {
            @Override public void onStatus(String deviceId, String mode) { }
            @Override public void onBoarded(String deviceId) { boarded(); }
        });
        System.out.println("MQTT conectat. Placa: "
                + (device.deviceId() != null ? device.deviceId() : "se descoperă din primul mesaj status"));

        VehicleFeed feed = new VehicleFeed(cfg, v -> tracker.onVehicle(v, Instant.now()));
        System.out.println("RabbitMQ conectat (coadă proprie pe cluj.vehicles). Flota se adună în ~1 minut.");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::update, UPDATE_PERIOD_S, UPDATE_PERIOD_S, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            device.send("IDLE");
            scheduler.shutdownNow();
            feed.close();
            device.close();
        }));

        while (true) {
            if (!select(lines)) break;
            System.out.println("Urmăresc. Enter = altă stație, q = ieșire. Pe placă: BOOT = am urcat.");
            String cmd = in.readLine();
            stopTracking();
            if (cmd == null || cmd.trim().equalsIgnoreCase("q")) break;
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Alegerea liniei, sensului și stației, din datele reale
    // ------------------------------------------------------------------
    private boolean select(List<CtpApi.Line> lines) throws Exception {
        CtpApi.Line line = null;
        while (line == null) {
            String q = ask("Linia (ex. 24B, M26; q = ieșire): ");
            if (q == null || q.equalsIgnoreCase("q")) return false;
            List<CtpApi.Line> exact = new ArrayList<>();
            List<CtpApi.Line> partial = new ArrayList<>();
            for (CtpApi.Line l : lines) {
                if (l.line().equalsIgnoreCase(q)) exact.add(l);
                else if (l.line().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))
                        || l.name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) partial.add(l);
            }
            List<CtpApi.Line> found = exact.isEmpty() ? partial : exact;  // „12” există de două ori
            if (found.isEmpty()) {
                System.out.println("Nicio linie găsită.");
            } else if (found.size() == 1) {
                line = found.get(0);
            } else {
                line = choose("Mai multe linii:", found);
            }
        }

        List<CtpApi.Route> routes = api.routes(line.routeId());
        routes.removeIf(r -> r.stops().isEmpty());
        if (routes.isEmpty()) {
            System.out.println("Linia nu are stații în API.");
            return select(lines);
        }
        CtpApi.Route route = routes.size() == 1 ? routes.get(0) : chooseRoute(routes);

        List<CtpApi.Stop> stops = route.stops();
        for (int i = 0; i < stops.size(); i++) System.out.printf("  %2d. %s%n", i + 1, stops.get(i).name());
        int stopIndex = -1;
        while (stopIndex < 0) {
            String s = ask("Stația ta (număr sau nume): ");
            if (s == null) return false;
            stopIndex = parseIndex(s, stops.size());
            if (stopIndex < 0) {
                for (int i = 0; i < stops.size(); i++) {
                    if (stops.get(i).name().toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT))) {
                        stopIndex = i;
                        break;
                    }
                }
            }
            if (stopIndex == 0) {
                System.out.println("(Prima stație este capătul: autobuzul pleacă de aici, estimarea nu are sens. Alege alta.)");
                stopIndex = -1;
            }
        }

        String w = ask("Minute de mers până la stație [" + cfg.walkMinutes() + "]: ");
        int walk = cfg.walkMinutes();
        try {
            if (w != null && !w.isBlank()) walk = Integer.parseInt(w.trim());
        } catch (NumberFormatException ignored) {
            // rămâne valoarea implicită
        }

        tracker.select(line.routeId(), route, stopIndex);
        lineName = line.line();
        stopName = stops.get(stopIndex).name();
        walkS = Math.max(0, walk) * 60;
        lastEstimate = Optional.empty();
        System.out.printf("Linia %s spre %s, stația %s, %d min de mers.%n", lineName, route.headsign(), stopName, walk);
        update();
        return true;
    }

    private CtpApi.Route chooseRoute(List<CtpApi.Route> routes) throws Exception {
        for (int i = 0; i < routes.size(); i++) {
            CtpApi.Route r = routes.get(i);
            System.out.printf("  %d. spre %s (%d stații)%n", i + 1, r.headsign(), r.stops().size());
        }
        while (true) {
            int i = parseIndex(ask("Sensul: "), routes.size());
            if (i >= 0) return routes.get(i);
        }
    }

    private <T> T choose(String title, List<T> items) throws Exception {
        System.out.println(title);
        for (int i = 0; i < items.size(); i++) System.out.printf("  %d. %s%n", i + 1, items.get(i));
        while (true) {
            int i = parseIndex(ask("Alege: "), items.size());
            if (i >= 0) return items.get(i);
        }
    }

    private static int parseIndex(String s, int size) {
        try {
            int i = Integer.parseInt(s.trim()) - 1;
            return i >= 0 && i < size ? i : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String ask(String prompt) throws Exception {
        System.out.print(prompt);
        String s = in.readLine();
        return s == null ? null : s.trim();
    }

    // ------------------------------------------------------------------
    // La fiecare 5 s: estimarea și comanda pentru placă
    // ------------------------------------------------------------------
    private synchronized void update() {
        String line = lineName;
        if (line == null) return;
        Optional<Tracker.Estimate> est = tracker.best(Instant.now());
        lastEstimate = est;
        String time = LocalTime.now().format(HMS);
        if (est.isEmpty()) {
            device.send("NOBUS;" + line);
            System.out.printf("[%s] %s → %s: niciun autobuz spre stație (vehicule urmărite: %d) | placa %s: %s%n",
                    time, line, stopName, tracker.trackedVehicles(), deviceName(), device.lastMode());
            return;
        }
        Tracker.Estimate e = est.get();
        int leave = e.etaS() - walkS;
        device.send(String.format(Locale.ROOT, "TRACK;%s;%d;%d;%d;%d", line, e.etaS(), leave, e.stops(), e.distM()));
        System.out.printf("[%s] %s → %s: vehiculul %s, %.1f km, %d stații, sosește în ~%s, %s | placa %s: %s%n",
                time, line, stopName, e.label(), e.distM() / 1000.0, e.stops(), mmss(e.etaS()),
                leave > 0 ? "pleacă în " + mmss(leave) : "PLEACĂ ACUM", deviceName(), device.lastMode());
    }

    private synchronized void stopTracking() {
        lineName = null;
        tracker.clear();
        device.send("IDLE");
    }

    /** Placa a raportat BOARDED: utilizatorul a urcat. */
    private synchronized void boarded() {
        String line = lineName;
        if (line == null) {
            System.out.println("[placa] BOARDED primit, dar nu urmăream nicio linie");
            return;
        }
        String label = lastEstimate.map(Tracker.Estimate::label).orElse("?");
        System.out.printf("[%s] URCARE CONFIRMATĂ: linia %s, stația %s, vehiculul %s%n",
                LocalTime.now().format(HMS), line, stopName, label);
        device.send("ACK;" + label);
        lineName = null;
        tracker.clear();
        device.send("IDLE");
        System.out.println("Urmărire încheiată. Enter = urmărire nouă, q = ieșire.");
    }

    private String deviceName() {
        return device.deviceId() != null ? device.deviceId() : "(nedescoperită)";
    }

    private static String mmss(int s) {
        s = Math.max(0, s);
        return s >= 60 ? (s / 60) + " min " + (s % 60) + " s" : s + " s";
    }
}
