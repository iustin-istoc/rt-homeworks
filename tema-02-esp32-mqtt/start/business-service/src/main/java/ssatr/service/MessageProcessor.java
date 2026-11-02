package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 *
 * Ordinea: întâi se salvează mesajul brut (exact cum a sosit, chiar dacă este
 * invalid, ca să rămână urma lui), apoi se interpretează. Un mesaj invalid
 * produce un avertisment și NU oprește thread-ul.
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        try {
            while (true) {
                String message = inbox.take();
                storage.append(kind, message);
                try {
                    process(message);
                } catch (RuntimeException e) {
                    System.out.println("[" + kind + "] mesaj invalid ignorat: '" + message + "' (" + e.getMessage() + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(String message) {
        switch (kind) {
            case "telemetry": {
                String[] f = fields(message, 3);
                dashboard.onTelemetry(f[0], Double.parseDouble(f[2]));
                break;   // telemetria nu se afișează: ar acoperi consola operatorului
            }
            case "alarm": {
                String[] f = fields(message, 4);
                double temp = Double.parseDouble(f[2]);
                double threshold = Double.parseDouble(f[3]);
                dashboard.onAlarm(f[0], temp);
                System.out.printf("!!! ALARMĂ %s: temperatura %.2f > prag %.2f%n", f[0], temp, threshold);
                break;
            }
            case "status": {
                // textul poate conține spații, dar nu ";" -> limităm split-ul la 3 câmpuri
                String[] f = fields(message, 3);
                dashboard.onStatus(f[0], f[2]);
                System.out.println(">>> " + f[0] + ": " + f[2]);
                break;
            }
            default:
                throw new IllegalStateException("tip necunoscut: " + kind);
        }
    }

    /** Separă mesajul în exact n câmpuri și verifică deviceId și timestamp. */
    private static String[] fields(String message, int n) {
        String[] f = message.split(";", n);
        if (f.length != n) {
            throw new IllegalArgumentException("se așteptau " + n + " câmpuri, sunt " + f.length);
        }
        if (f[0].isBlank()) {
            throw new IllegalArgumentException("deviceId lipsă");
        }
        Long.parseLong(f[1]);   // timestamp valid
        return f;
    }
}
