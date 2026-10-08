package fun.fengwk.kkstudio.notification;

import java.time.Duration;

/** Single budget for publication, subscriber mailboxes and PG reassembly. */
public record NotificationLimits(
    int maxMessageBytes,
    int pendingBytes,
    int queueCapacity,
    int reassemblyBytes,
    int reassemblyMessages,
    Duration reassemblyTimeout,
    int sendBatchFrames) {
  public static NotificationLimits defaults() {
    return new NotificationLimits(
        8 * 1024 * 1024, 32 * 1024 * 1024, 256, 32 * 1024 * 1024, 8, Duration.ofSeconds(5), 32);
  }

  public NotificationLimits {
    if (maxMessageBytes <= 0
        || pendingBytes < maxMessageBytes
        || queueCapacity <= 0
        || reassemblyBytes < maxMessageBytes
        || reassemblyMessages <= 0
        || reassemblyTimeout == null
        || reassemblyTimeout.isNegative()
        || reassemblyTimeout.isZero()
        || sendBatchFrames <= 0) {
      throw new IllegalArgumentException("invalid notification limits");
    }
  }
}
