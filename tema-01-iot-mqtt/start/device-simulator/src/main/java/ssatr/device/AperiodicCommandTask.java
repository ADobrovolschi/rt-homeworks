package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

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
            while (!Thread.currentThread().isInterrupted()) {
                String command = commands.take();
                String response;
                try {
                    response = execute(command);
                } catch (IllegalArgumentException e) {
                    response = "ERROR " + e.getMessage();
                }
                String message = String.format(Locale.US, "%s;%d;%s",
                        deviceId, System.currentTimeMillis(), response);
                mqtt.publish(Config.statusTopic(deviceId), message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String execute(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("empty command");
        }
        String[] parts = command.trim().split("\\s+");
        switch (parts[0]) {
            case "SET_INTERVAL":
                requireArgumentCount(parts, 2);
                long periodMs;
                try {
                    periodMs = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid interval");
                }
                if (periodMs <= 0) {
                    throw new IllegalArgumentException("interval must be positive");
                }
                periodic.setPeriodMs(periodMs);
                return "OK SET_INTERVAL " + periodMs;
            case "SET_THRESHOLD":
                requireArgumentCount(parts, 2);
                double threshold;
                try {
                    threshold = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid threshold");
                }
                if (!Double.isFinite(threshold)) {
                    throw new IllegalArgumentException("threshold must be finite");
                }
                periodic.setThreshold(threshold);
                return String.format(Locale.US, "OK SET_THRESHOLD %.2f", threshold);
            case "RESET":
                requireArgumentCount(parts, 1);
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                requireArgumentCount(parts, 1);
                return String.format(Locale.US, "OK STATUS interval=%d threshold=%.2f",
                        periodic.getPeriodMs(), periodic.getThreshold());
            default:
                throw new IllegalArgumentException("unknown command " + parts[0]);
        }
    }

    private static void requireArgumentCount(String[] parts, int expected) {
        if (parts.length != expected) {
            throw new IllegalArgumentException("wrong number of arguments for " + parts[0]);
        }
    }
}
