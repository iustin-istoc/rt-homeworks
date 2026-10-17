package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzile se execută în ordinea sosirii (FIFO), fără garanții de termen:
 * dacă sosesc în rafală, ultimele așteaptă în coadă.
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După fiecare comandă se publică pe Config.statusTopic(deviceId)
 * "deviceId;timestamp;OK ..." sau "deviceId;timestamp;ERROR <motiv>".
 */
public class AperiodicCommandTask implements Runnable {

    /** limite de bun-simț: o perioadă prea mică ar inunda brokerul partajat */
    static final long MIN_PERIOD_MS = 100;
    static final long MAX_PERIOD_MS = 60_000;

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String command = commands.take().trim();
                long start = System.nanoTime();

                String result;
                try {
                    result = execute(command);
                } catch (IllegalArgumentException e) {
                    // comandă greșită: raportăm eroarea, taskul continuă
                    result = "ERROR " + e.getMessage();
                }

                mqtt.publish(Config.statusTopic(deviceId),
                        deviceId + ";" + System.currentTimeMillis() + ";" + result);
                System.out.printf("      [aperiodic] '%s' -> %s  (C=%d us)%n",
                        command, result, (System.nanoTime() - start) / 1000);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Execută o comandă și întoarce textul confirmării; aruncă IllegalArgumentException la eroare. */
    private String execute(String command) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("comanda goala");
        }
        String[] parts = command.split("\\s+");
        String name = parts[0].toUpperCase(Locale.ROOT);

        switch (name) {
            case "SET_INTERVAL": {
                long ms = parseLong(requireArg(parts, name), name);
                if (ms < MIN_PERIOD_MS || ms > MAX_PERIOD_MS) {
                    throw new IllegalArgumentException(name + ": interval in afara limitelor ["
                            + MIN_PERIOD_MS + ", " + MAX_PERIOD_MS + "] ms: " + ms);
                }
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                double value = parseDouble(requireArg(parts, name), name);
                periodic.setThreshold(value);
                return String.format(Locale.US, "OK SET_THRESHOLD %.2f", value);
            }
            case "RESET":
                requireNoArg(parts, name);
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                requireNoArg(parts, name);
                return String.format(Locale.US, "OK STATUS interval=%d threshold=%.2f",
                        periodic.getPeriodMs(), periodic.getThreshold());
            default:
                throw new IllegalArgumentException("comanda necunoscuta: " + parts[0]);
        }
    }

    private static String requireArg(String[] parts, String name) {
        if (parts.length != 2) {
            throw new IllegalArgumentException(name + " necesita exact un parametru");
        }
        return parts[1];
    }

    private static void requireNoArg(String[] parts, String name) {
        if (parts.length != 1) {
            throw new IllegalArgumentException(name + " nu are parametri");
        }
    }

    private static long parseLong(String s, String name) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": valoare invalida: " + s);
        }
    }

    private static double parseDouble(String s, String name) {
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": valoare invalida: " + s);
        }
    }
}
