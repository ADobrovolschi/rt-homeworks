package ssatr.device;

/** Temperature and threshold captured together when a periodic job detects an alarm. */
record AlarmEvent(double temperature, double threshold) {
}
