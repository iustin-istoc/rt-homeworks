package ssatr.tracker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Urmărește vehiculele liniei și sensului alese și estimează care autobuz
 * ajunge primul în stația utilizatorului și în cât timp.
 *
 * Sursa nu are orare și nici estimări de sosire, deci estimarea se face așa:
 *  - poziția fiecărui vehicul se proiectează pe traseu (RouteGeometry);
 *  - distanța rămasă = abscisa stației - abscisa vehiculului;
 *  - viteza medie a fiecărui vehicul se învață din pozițiile lui succesive
 *    (include și opririle în stații și la semafoare), cu o valoare implicită de
 *    18 km/h până avem două poziții;
 *  - între două poziții (vin cam la 20 s) vehiculul este „avansat” cu viteza lui.
 *
 * Datele reale au imperfecțiuni, tratate explicit: poziții vechi (ignorate după
 * 3 minute), vehicule departe de traseu (GPS greșit, la depou), vehicule fără
 * trip_id (acceptate doar după ce s-au văzut mergând în sensul ales), vehicule
 * care dispar (scoase după 3 minute fără poziții).
 */
public final class Tracker {

    public record Vehicle(int id, String label, int routeId, String tripId,
                          double lat, double lon, Instant timestamp) { }

    /** Cel mai apropiat autobuz care încă nu a trecut de stație. */
    public record Estimate(String label, int distM, int stops, int etaS, double speedKmh, long ageS) { }

    private static final double DEFAULT_SPEED = 5.0;  // m/s = 18 km/h, medie urbană cu opriri
    private static final double MIN_SPEED = 2.5;
    private static final double MAX_SPEED = 12.0;
    private static final double MAX_OFF_ROUTE_M = 150;
    private static final double ARRIVED_M = 80;      // sub atât, autobuzul e „în stație”
    private static final double PASSED_M = -60;      // a trecut de stație
    private static final double NO_TRIP_FORWARD_M = 100;
    private static final Duration MAX_AGE = Duration.ofMinutes(3);

    private static final class Track {
        String label;
        boolean tripKnown;
        double along = Double.NaN;
        Instant ts;
        double speed = DEFAULT_SPEED;
        double forwardM;  // cât l-am văzut mergând înainte, pentru vehiculele fără trip_id
    }

    private final Map<Integer, Track> tracks = new HashMap<>();

    private int routeId = -1;
    private String tripId;
    private RouteGeometry geometry;
    private double stopAlong;
    private double[] stopsAlong;
    private long messages;

    /** Alege linia, sensul și stația. Vehiculele urmărite până acum se uită. */
    public synchronized void select(int routeId, CtpApi.Route route, int stopIndex) {
        List<double[]> shape = route.shape();
        if (shape.size() < 2) {  // fără traseu: îl aproximăm prin stații
            shape = route.stops().stream().map(s -> new double[]{s.lat(), s.lon()}).toList();
        }
        RouteGeometry g = new RouteGeometry(shape);
        double[] along = new double[route.stops().size()];
        double prev = Double.NaN;
        for (int i = 0; i < along.length; i++) {
            CtpApi.Stop s = route.stops().get(i);
            along[i] = g.project(s.lat(), s.lon(), prev).alongM();
            prev = along[i];
        }
        this.routeId = routeId;
        this.tripId = route.tripId();
        this.geometry = g;
        this.stopsAlong = along;
        this.stopAlong = along[stopIndex];
        tracks.clear();
    }

    public synchronized void clear() {
        routeId = -1;
        tracks.clear();
    }

    public synchronized long messages() { return messages; }

    public synchronized int trackedVehicles() { return tracks.size(); }

    /** Apelată pentru fiecare poziție primită din RabbitMQ (orice linie). */
    public synchronized void onVehicle(Vehicle v, Instant now) {
        messages++;
        if (routeId < 0 || v.routeId() != routeId) return;
        if (v.tripId() != null && !v.tripId().equals(tripId)) {
            tracks.remove(v.id());  // e pe celălalt sens (de exemplu, a întors la capăt)
            return;
        }
        if (v.timestamp() == null || Duration.between(v.timestamp(), now).compareTo(MAX_AGE) > 0) return;

        Track t = tracks.computeIfAbsent(v.id(), k -> new Track());
        if (t.ts != null && !v.timestamp().isAfter(t.ts)) return;  // aceeași poziție sau una mai veche

        RouteGeometry.Projection p = geometry.project(v.lat(), v.lon(), t.along);
        if (p.offM() > MAX_OFF_ROUTE_M) return;  // GPS greșit sau în afara traseului

        if (t.ts != null && !Double.isNaN(t.along)) {
            double dt = Duration.between(t.ts, v.timestamp()).toMillis() / 1000.0;
            double d = p.alongM() - t.along;
            if (dt >= 5 && d > -30 && d < MAX_SPEED * 1.5 * dt) {
                double inst = Math.max(0, d) / dt;
                t.speed = clamp(0.7 * t.speed + 0.3 * inst);
                if (d > 0) t.forwardM += d;
            }
        }
        t.label = v.label();
        t.tripKnown = v.tripId() != null;
        t.along = p.alongM();
        t.ts = v.timestamp();
    }

    /** Cel mai apropiat autobuz dinaintea stației, sau gol dacă nu există. */
    public synchronized Optional<Estimate> best(Instant now) {
        if (routeId < 0) return Optional.empty();
        tracks.values().removeIf(t -> t.ts == null || Duration.between(t.ts, now).compareTo(MAX_AGE) > 0);

        Estimate best = null;
        for (Track t : tracks.values()) {
            if (!t.tripKnown && t.forwardM < NO_TRIP_FORWARD_M) continue;  // sens nesigur
            double remaining = stopAlong - t.along;
            if (remaining < PASSED_M) continue;  // a trecut de stație

            // avansăm vehiculul de la ultima poziție, cu viteza lui medie
            long ageS = Math.max(0, Duration.between(t.ts, now).toSeconds());
            double est = remaining > ARRIVED_M ? Math.max(ARRIVED_M, remaining - t.speed * ageS) : remaining;

            int stops;
            int eta;
            if (est <= ARRIVED_M) {
                stops = 0;
                eta = 0;
            } else {
                double busAlong = stopAlong - est;
                stops = 0;
                for (double a : stopsAlong) {
                    if (a > busAlong + 30 && a <= stopAlong + 1) stops++;
                }
                stops = Math.max(1, stops);
                eta = (int) Math.round(est / t.speed);
            }
            Estimate e = new Estimate(t.label, (int) Math.round(Math.max(0, est)), stops, eta, t.speed * 3.6, ageS);
            if (best == null || e.distM() < best.distM()) best = e;
        }
        return Optional.ofNullable(best);
    }

    private static double clamp(double s) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, s));
    }
}
