package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul este sincronizat: toate metodele publice sunt synchronized pe
 * același obiect, astfel încât print() vede o stare consistentă a tuturor
 * device-urilor (nu un amestec între două actualizări).
 *
 * Vechimea ultimului mesaj se calculează din momentul RECEPȚIEI (ceasul
 * serviciului), nu din timestamp-ul trimis de device: ceasurile celor două
 * mașini pot fi desincronizate.
 */
public class Dashboard {

    /** starea unui device; câmpurile sunt accesate doar sub lacătul Dashboard-ului */
    private static class DeviceState {
        Double lastTemperature;      // null până la prima telemetrie
        long lastMessageMillis;      // momentul recepției ultimului mesaj (orice tip)
        int alarmCount;
        String lastStatus = "-";
    }

    // TreeMap: device-urile apar sortate după ID în tabel
    private final Map<String, DeviceState> devices = new TreeMap<>();

    public synchronized void onTelemetry(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.lastTemperature = temperature;
    }

    public synchronized void onAlarm(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.lastTemperature = temperature;
        s.alarmCount++;
    }

    public synchronized void onStatus(String deviceId, String text) {
        state(deviceId).lastStatus = text;
    }

    public synchronized boolean isKnown(String deviceId) {
        return devices.containsKey(deviceId);
    }

    /** obține (sau creează) starea și marchează recepția unui mesaj */
    private DeviceState state(String deviceId) {
        DeviceState s = devices.computeIfAbsent(deviceId, id -> new DeviceState());
        s.lastMessageMillis = System.currentTimeMillis();
        return s;
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("(niciun device nu a trimis încă date)");
            return;
        }
        long now = System.currentTimeMillis();
        String fmt = "%-12s %8s %15s %7s   %s%n";
        System.out.printf(fmt, "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME", "ULTIMUL STATUS");
        for (Map.Entry<String, DeviceState> e : devices.entrySet()) {
            DeviceState s = e.getValue();
            String temp = s.lastTemperature == null ? "-" : String.format(Locale.US, "%.2f", s.lastTemperature);
            System.out.printf(fmt, e.getKey(), temp, age(now - s.lastMessageMillis),
                    s.alarmCount, s.lastStatus);
        }
    }

    private static String age(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return "acum " + sec + " s";
        if (sec < 3600) return "acum " + sec / 60 + " min";
        return "acum " + sec / 3600 + " h";
    }
}
