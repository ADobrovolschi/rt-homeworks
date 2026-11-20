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
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        if (!kind.equals("telemetry") && !kind.equals("alarm") && !kind.equals("status")) {
            throw new IllegalArgumentException("Unsupported message kind: " + kind);
        }
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String message = inbox.take();
                // Preserve the original MQTT payload for diagnosis, even if validation fails.
                storage.append(kind, message);
                try {
                    process(message);
                } catch (IllegalArgumentException e) {
                    System.err.printf("[%s] mesaj invalid, salvat în CSV: %s (%s)%n",
                            kind, message, e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void process(String message) {
        String[] fields = message.split(";", -1);
        int expectedFields = kind.equals("alarm") ? 4 : 3;
        if (fields.length != expectedFields || fields[0].isBlank()) {
            throw new IllegalArgumentException("wrong number of fields");
        }

        parseTimestamp(fields[1]);
        switch (kind) {
            case "telemetry":
                double temperature = parseFiniteDouble(fields[2], "temperature");
                dashboard.updateTelemetry(fields[0], temperature);
                break;
            case "alarm":
                double alarmTemperature = parseFiniteDouble(fields[2], "temperature");
                double threshold = parseFiniteDouble(fields[3], "threshold");
                dashboard.updateAlarm(fields[0], alarmTemperature);
                System.out.printf("[alarm] %s: %.2f °C (prag %.2f °C)%n",
                        fields[0], alarmTemperature, threshold);
                break;
            case "status":
                if (fields[2].isBlank()) {
                    throw new IllegalArgumentException("empty status text");
                }
                dashboard.updateStatus(fields[0]);
                System.out.printf("[status] %s: %s%n", fields[0], fields[2]);
                break;
            default:
                throw new IllegalStateException("Unsupported message kind: " + kind);
        }
    }

    private static long parseTimestamp(String value) {
        try {
            long timestamp = Long.parseLong(value);
            if (timestamp <= 0) {
                throw new IllegalArgumentException("timestamp must be positive");
            }
            return timestamp;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid timestamp");
        }
    }

    private static double parseFiniteDouble(String value, String fieldName) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException(fieldName + " must be finite");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + fieldName);
        }
    }
}
