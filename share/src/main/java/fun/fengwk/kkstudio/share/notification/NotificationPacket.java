package fun.fengwk.kkstudio.share.notification;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One complete logical notification body shared by every transfer. The body is copied once at
 * construction and never handed out by reference: {@link #bytes()} returns a defensive copy and
 * {@link #byteLength()} exposes the size without copying. {@code toString} never renders the body.
 *
 * <p>The packet is transport-agnostic and carries no authorization: the fixed topic and publisher
 * identify the message, while the receiving channel must still validate the real peer.
 */
public final class NotificationPacket {
  private final UUID publisher;
  private final UUID target;
  private final String topic;
  private final UUID messageId;
  private final byte[] bytes;

  public NotificationPacket(
      UUID publisher, UUID target, String topic, UUID messageId, byte[] bytes) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.target = target;
    this.topic = NotificationCarrier.requireTopic(topic);
    this.messageId = Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(bytes, "bytes");
    // Validate the logical size before copying, so an oversized body is never cloned into the
    // packet; the per-instance budget may be smaller and is enforced by decode/reassembly.
    if (bytes.length > NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES) {
      throw new IllegalArgumentException("notification packet exceeds logical message limit");
    }
    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  public UUID publisher() {
    return publisher;
  }

  /** Null broadcasts to every receiver; a non-null value selects one receiver identity. */
  public UUID target() {
    return target;
  }

  public String topic() {
    return topic;
  }

  public UUID messageId() {
    return messageId;
  }

  public byte[] bytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }

  public int byteLength() {
    return bytes.length;
  }

  /**
   * Owned body for in-package framing. Never expose it: a caller would be able to observe or mutate
   * the packet body, and slicing through {@link #bytes()} would clone the whole message per chunk.
   */
  byte[] rawBytes() {
    return bytes;
  }

  @Override
  public String toString() {
    return "NotificationPacket[publisher="
        + publisher
        + ", target="
        + target
        + ", topic="
        + topic
        + ", messageId="
        + messageId
        + ", byteLength="
        + bytes.length
        + "]";
  }
}
