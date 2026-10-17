package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equals("exit")) {
                break;
            } else if (line.equals("help")) {
                printHelp();
            } else if (line.equals("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        // "device-1 SET_INTERVAL 1000" -> ["device-1", "SET_INTERVAL 1000"]
        String[] parts = line.split("\\s+", 2);
        if (parts.length < 2) {
            System.out.println("Format greșit. Exemplu: device-1 SET_INTERVAL 1000 (scrieți help)");
            return;
        }
        String deviceId = parts[0];
        String command = parts[1].trim();

        // deviceId devine nivel de topic: "+", "#" sau "/" ar schimba topic-ul
        if (deviceId.contains("/") || deviceId.contains("+") || deviceId.contains("#")) {
            System.out.println("ID de device invalid: " + deviceId);
            return;
        }
        // validarea comenzii o face device-ul (răspunde cu ERROR); aici doar avertizăm
        if (!dashboard.isKnown(deviceId)) {
            System.out.println("Atenție: " + deviceId + " nu a trimis încă date; comanda se trimite oricum.");
        }

        mqtt.publish(Config.commandTopic(deviceId), command);
        System.out.println("Trimis către " + deviceId + ": " + command);
    }

    private void printHelp() {
        System.out.println("Comenzi: show | <deviceId> SET_INTERVAL <ms> | <deviceId> SET_THRESHOLD <valoare>");
        System.out.println("         <deviceId> RESET | <deviceId> STATUS | help | exit");
    }
}
