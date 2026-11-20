package ssatr.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    private final Map<String, DeviceState> devices = new HashMap<>();

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        System.out.printf("%-20s %-12s %-20s %s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        long now = System.currentTimeMillis();
        new ArrayList<>(devices.entrySet()).stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    DeviceState state = entry.getValue();
                    String temperature = state.temperature == null
                            ? "--"
                            : String.format(Locale.US, "%.2f °C", state.temperature);
                    System.out.printf("%-20s %-12s %-20s %d%n",
                            entry.getKey(), temperature, formatAge(now - state.lastMessageAt), state.alarms);
                });
        if (devices.isEmpty()) {
            System.out.println("(niciun device cunoscut)");
        }
    }

    public synchronized void updateTelemetry(String deviceId, double temperature) {
        DeviceState state = stateFor(deviceId);
        state.temperature = temperature;
        state.lastMessageAt = System.currentTimeMillis();
    }

    public synchronized void updateAlarm(String deviceId, double temperature) {
        DeviceState state = stateFor(deviceId);
        state.temperature = temperature;
        state.alarms++;
        state.lastMessageAt = System.currentTimeMillis();
    }

    public synchronized void updateStatus(String deviceId) {
        stateFor(deviceId).lastMessageAt = System.currentTimeMillis();
    }

    private DeviceState stateFor(String deviceId) {
        return devices.computeIfAbsent(deviceId, ignored -> new DeviceState());
    }

    private static String formatAge(long ageMs) {
        long seconds = Math.max(0, ageMs) / 1000;
        if (seconds < 60) {
            return seconds == 0 ? "acum" : "acum " + seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "acum " + minutes + " min";
        }
        return "acum " + minutes / 60 + " h";
    }

    private static final class DeviceState {
        private Double temperature;
        private long lastMessageAt;
        private int alarms;
    }
}
