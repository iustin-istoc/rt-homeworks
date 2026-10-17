package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 *
 * Pragul vine în eveniment (vezi AlarmEvent): este pragul care a fost depășit
 * în momentul citirii, nu cel curent, care se poate schimba între timp.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;
    /** termenul-limită relativ D al unui job de alarmă (verificat, nu garantat de Java) */
    static final long DEADLINE_MS = 500;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    private long lastReleaseNanos;
    private boolean released = false;   // încă nicio eliberare: prima alarmă este acceptată

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        try {
            while (true) {
                AlarmEvent e = alarmEvents.take();   // blocat până sosește un eveniment

                // intervalul se măsoară între SOSIRI (momentul evenimentului),
                // nu între momentele în care le scoatem din coadă
                long sinceLastMs = (e.arrivalNanos() - lastReleaseNanos) / 1_000_000;
                if (released && sinceLastMs < MIN_INTERARRIVAL_MS) {
                    System.out.printf(Locale.US,
                            "   [sporadic] RESPINS: temp=%.2f, au trecut doar %d ms (minim %d ms)%n",
                            e.temperature(), sinceLastMs, MIN_INTERARRIVAL_MS);
                    continue;
                }
                released = true;
                lastReleaseNanos = e.arrivalNanos();   // eliberarea jobului = sosirea evenimentului

                // ---- jobul: publicarea alarmei ----
                String msg = String.format(Locale.US, "%s;%d;%.2f;%.2f",
                        deviceId, System.currentTimeMillis(), e.temperature(), e.threshold());
                mqtt.publish(Config.alarmTopic(deviceId), msg);

                long responseMs = (System.nanoTime() - e.arrivalNanos()) / 1_000_000;
                System.out.printf("   [sporadic] ALARMĂ publicată: %s  R=%d ms -> %s (D=%d ms)%n",
                        msg, responseMs, responseMs <= DEADLINE_MS ? "termen respectat" : "TERMEN RATAT",
                        DEADLINE_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
