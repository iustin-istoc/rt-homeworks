package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 *
 * Eliberările sunt pe grila r_k = r_(k-1) + T (așteptare ABSOLUTĂ, cu timp
 * monoton System.nanoTime()). Cu Thread.sleep(T) după fiecare job, perioada
 * reală ar fi T + C + întârzierea de trezire, iar eroarea s-ar acumula (derivă).
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long release = System.nanoTime();   // r_0: prima eliberare este acum
        long k = 0;

        while (!Thread.currentThread().isInterrupted()) {
            waitUntil(release);

            // ---- jobul k ----
            long start = System.nanoTime();
            double latenessMs = (start - release) / 1e6;   // L_k = s_k - r_k

            double t = sensor.read();
            double currentThreshold = threshold;           // o singură citire, valoare consistentă
            String temp = String.format(Locale.US, "%.2f", t);
            mqtt.publish(Config.telemetryTopic(deviceId),
                    deviceId + ";" + System.currentTimeMillis() + ";" + temp);

            if (t > currentThreshold) {
                // offer: nu blochează niciodată taskul periodic (coada este nemărginită)
                alarmEvents.offer(new AlarmEvent(t, currentThreshold, start));
            }

            long computationUs = (System.nanoTime() - start) / 1000;
            System.out.printf("[periodic] job #%d  T=%d ms  L=%.3f ms  C=%d us  temp=%s%s%n",
                    k, periodMs, latenessMs, computationUs, temp,
                    t > currentThreshold ? "  > prag " + currentThreshold : "");

            // ---- următoarea eliberare, pe grilă ----
            // periodMs se citește la fiecare job: SET_INTERVAL are efect de la
            // următoarea eliberare (grila continuă din r_k, cu noua perioadă).
            long periodNs = periodMs * 1_000_000L;
            release += periodNs;
            k++;

            // Depășire (overrun): dacă jobul a terminat după eliberarea următoare,
            // nu recuperăm joburile pierdute „în rafală”; sărim peste ele și
            // rămânem aliniați la grilă.
            long now = System.nanoTime();
            if (now > release) {
                long missed = (now - release) / periodNs + 1;
                release += missed * periodNs;
                k += missed;
                System.out.printf("[periodic] DEPĂȘIRE: %d eliberări sărite%n", missed);
            }
        }
    }

    /** Așteptare absolută până la momentul target (nanoTime). */
    private static void waitUntil(long target) {
        long remaining;
        // parkNanos poate reveni mai devreme (trezire falsă), de aceea bucla
        while ((remaining = target - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
