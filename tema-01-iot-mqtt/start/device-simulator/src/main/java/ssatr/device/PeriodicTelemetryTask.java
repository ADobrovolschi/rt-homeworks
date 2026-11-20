package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<AlarmEvent> alarmEvents;
    private final Object periodLock = new Object();

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
        long scheduledRelease = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                double temperature = sensor.read();
                double activeThreshold = threshold;
                long timestamp = System.currentTimeMillis();
                String message = String.format(Locale.US, "%s;%d;%.2f",
                        deviceId, timestamp, temperature);
                mqtt.publish(Config.telemetryTopic(deviceId), message);

                if (temperature > activeThreshold) {
                    // Capture the threshold with the reading so a later command cannot change this alarm's meaning.
                    alarmEvents.put(new AlarmEvent(temperature, activeThreshold));
                }

                scheduledRelease = awaitNextRelease(scheduledRelease);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long awaitNextRelease(long previousRelease) throws InterruptedException {
        synchronized (periodLock) {
            // Use a monotonic clock and advance from the prior release to avoid accumulating job execution time.
            long nextRelease = previousRelease + TimeUnit.MILLISECONDS.toNanos(periodMs);
            while (true) {
                long remaining = nextRelease - System.nanoTime();
                if (remaining <= 0) {
                    return nextRelease;
                }
                TimeUnit.NANOSECONDS.timedWait(periodLock, remaining);
                nextRelease = previousRelease + TimeUnit.MILLISECONDS.toNanos(periodMs);
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("periodMs must be positive");
        }
        synchronized (periodLock) {
            this.periodMs = periodMs;
            periodLock.notifyAll();
        }
    }

    public void setThreshold(double threshold) {
        if (!Double.isFinite(threshold)) {
            throw new IllegalArgumentException("threshold must be finite");
        }
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
