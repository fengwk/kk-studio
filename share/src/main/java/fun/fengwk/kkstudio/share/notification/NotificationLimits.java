package fun.fengwk.kkstudio.share.notification;

import java.time.Duration;

/** Single budget for publication, subscriber mailboxes and shared transport reassembly. */
public record NotificationLimits(
    int maxMessageBytes,
    int pendingBytes,
    int queueCapacity,
    int reassemblyBytes,
    int reassemblyMessages,
    Duration reassemblyTimeout,
    int sendBatchFrames) {
  /**
   * Hard wire limit for one logical message. {@code defaults()} uses it, and the shared packet and
   * carrier value objects always reject a larger logical body regardless of the per-instance
   * budget, so a hand-built value can never escape the framing format.
   */
  public static final int DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

  public static NotificationLimits defaults() {
    return new NotificationLimits(
        DEFAULT_MAX_MESSAGE_BYTES,
        32 * 1024 * 1024,
        256,
        32 * 1024 * 1024,
        8,
        Duration.ofSeconds(5),
        32);
  }

  public NotificationLimits {
    if (maxMessageBytes <= 0
        || maxMessageBytes > DEFAULT_MAX_MESSAGE_BYTES
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
