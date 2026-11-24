package ssatr.tracker;

import java.util.List;

/**
 * Traseul unui sens, ca linie frântă. Proiectează o poziție GPS pe traseu și
 * întoarce distanța parcursă de la capăt („abscisa curbilinie”) și cât de
 * departe de traseu este punctul. Diferența de abscise dintre vehicul și stație
 * este distanța pe care o mai are de mers autobuzul.
 */
public final class RouteGeometry {

    private static final double R = 6_371_000.0;

    public record Projection(double alongM, double offM) { }

    private final double lat0;
    private final double cosLat0;
    private final double[] xs;
    private final double[] ys;
    private final double[] cum;  // distanța cumulată până la fiecare punct

    public RouteGeometry(List<double[]> points) {
        if (points.size() < 2) throw new IllegalArgumentException("traseu cu mai puțin de 2 puncte");
        lat0 = points.get(0)[0];
        cosLat0 = Math.cos(Math.toRadians(lat0));
        int n = points.size();
        xs = new double[n];
        ys = new double[n];
        cum = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x(points.get(i)[1]);
            ys[i] = y(points.get(i)[0]);
            if (i > 0) cum[i] = cum[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
    }

    // proiecție echirectangulară locală: suficient de precisă la scara unui oraș
    private double x(double lon) { return Math.toRadians(lon) * R * cosLat0; }
    private double y(double lat) { return Math.toRadians(lat) * R; }

    public double lengthM() { return cum[cum.length - 1]; }

    /**
     * Proiectează (lat, lon) pe traseu. Dacă traseul trece de două ori pe lângă
     * același loc (bucle, capete de linie), alege dintre segmentele aproape la fel
     * de apropiate pe cel mai apropiat de hintAlongM (poziția anterioară a
     * vehiculului); fără hint (NaN), pe cel mai apropiat.
     */
    public Projection project(double lat, double lon, double hintAlongM) {
        double px = x(lon);
        double py = y(lat);
        double[] along = new double[xs.length - 1];
        double[] off = new double[xs.length - 1];
        double best = Double.MAX_VALUE;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : ((px - xs[i]) * dx + (py - ys[i]) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double qx = xs[i] + t * dx;
            double qy = ys[i] + t * dy;
            off[i] = Math.hypot(px - qx, py - qy);
            along[i] = cum[i] + t * Math.sqrt(len2);
            best = Math.min(best, off[i]);
        }
        int chosen = -1;
        for (int i = 0; i < off.length; i++) {
            if (off[i] > best + 25) continue;  // doar candidații aproape la fel de buni
            if (chosen < 0) { chosen = i; continue; }
            if (Double.isNaN(hintAlongM)) {
                if (off[i] < off[chosen]) chosen = i;
            } else if (Math.abs(along[i] - hintAlongM) < Math.abs(along[chosen] - hintAlongM)) {
                chosen = i;
            }
        }
        return new Projection(along[chosen], off[chosen]);
    }
}
