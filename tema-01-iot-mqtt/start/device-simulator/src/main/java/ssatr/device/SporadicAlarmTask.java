package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<AlarmEvent> alarmEvents) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        long lastRelease = 0;
        boolean hasReleased = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                AlarmEvent event = alarmEvents.take();
                long now = System.nanoTime();
                long elapsed = now - lastRelease;
                if (hasReleased && elapsed < TimeUnit.MILLISECONDS.toNanos(MIN_INTERARRIVAL_MS)) {
                    // Reject arrivals that violate the sporadic task's minimum inter-arrival constraint.
                    System.out.printf("[sporadic] alarmă respinsă: au trecut %d ms (minim %d ms)%n",
                            TimeUnit.NANOSECONDS.toMillis(elapsed), MIN_INTERARRIVAL_MS);
                    continue;
                }

                lastRelease = now;
                hasReleased = true;
                String message = String.format(Locale.US, "%s;%d;%.2f;%.2f",
                        deviceId, System.currentTimeMillis(), event.temperature(), event.threshold());
                mqtt.publish(Config.alarmTopic(deviceId), message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
