package fun.fengwk.kkstudio.share.notification;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * One ASCII carrier for all messages, including {@code count=1}. Byte chunks cannot split UTF-8
 * semantics: only the fully reassembled bytes are passed to the domain codec.
 *
 * <p>The carrier is a strictly canonical, deep-immutable value: identity, topic, numeric fields and
 * Base64 are validated on every path, the byte array is copied on construction and on access, and
 * {@code toString} never renders the body. Public errors describe only the failed field or rule and
 * never echo the rejected input.
 */
public final class NotificationCarrier {
  /**
   * Maximum bytes per fragment. Splitting is by byte, so a fragment boundary may fall inside a
   * multi-byte UTF-8 sequence; fragments are never decoded individually, only the fully reassembled
   * bytes reach the domain codec.
   */
  public static final int CHUNK_BYTES = 5400;

  private static final int PAYLOAD_LIMIT = 7900;
  private static final String TOPIC_PATTERN = "[a-z][a-z0-9_.-]{0,95}";

  private final UUID publisher;
  private final UUID target;
  private final String topic;
  private final UUID messageId;
  private final int index;
  private final int count;
  private final int totalBytes;
  private final byte[] bytes;

  public NotificationCarrier(
      UUID publisher,
      UUID target,
      String topic,
      UUID messageId,
      int index,
      int count,
      int totalBytes,
      byte[] bytes) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.target = target;
    this.topic = requireTopic(topic);
    this.messageId = Objects.requireNonNull(messageId, "messageId");
    if (totalBytes < 0
        || totalBytes > NotificationLimits.DEFAULT_MAX_MESSAGE_BYTES
        || count != count(totalBytes)
        || index < 0
        || index >= count) {
      throw new IllegalArgumentException("invalid notification carrier dimensions");
    }
    Objects.requireNonNull(bytes, "bytes");
    // Validate the expected fragment length before copying, so a hand-built value cannot make the
    // carrier copy an arbitrary array.
    int expected = Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES);
    if (bytes.length != expected) {
      throw new IllegalArgumentException("invalid notification carrier chunk length");
    }
    this.index = index;
    this.count = count;
    this.totalBytes = totalBytes;
    this.bytes = Arrays.copyOf(bytes, bytes.length);
  }

  /**
   * Number of fragments a logical payload of {@code bytes} bytes occupies; empty still means one.
   */
  public static int count(int bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException("invalid notification payload size");
    }
    long fragments = ((long) bytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    return (int) Math.max(1L, fragments);
  }

  /**
   * Copies only the requested slice out of the packet's owned body; it never clones the whole
   * message, so framing a large payload stays proportional to the fragment size.
   */
  public static NotificationCarrier chunk(NotificationPacket message, int index) {
    Objects.requireNonNull(message, "message");
    byte[] payload = message.rawBytes();
    int total = count(payload.length);
    if (index < 0 || index >= total) {
      throw new IllegalArgumentException("invalid notification carrier index");
    }
    int start = (int) ((long) index * CHUNK_BYTES);
    int end = (int) Math.min(payload.length, (long) start + CHUNK_BYTES);
    byte[] slice = Arrays.copyOfRange(payload, start, end);
    return new NotificationCarrier(
        message.publisher(),
        message.target(),
        message.topic(),
        message.messageId(),
        index,
        total,
        payload.length,
        slice);
  }

  public UUID publisher() {
    return publisher;
  }

  public UUID target() {
    return target;
  }

  public String topic() {
    return topic;
  }

  public UUID messageId() {
    return messageId;
  }

  public int index() {
    return index;
  }

  public int count() {
    return count;
  }

  public int totalBytes() {
    return totalBytes;
  }

  public byte[] bytes() {
    return Arrays.copyOf(bytes, bytes.length);
  }

  public String encode() {
    String encoded =
        "1|"
            + publisher
            + "|"
            + (target == null ? "*" : target)
            + "|"
            + topic
            + "|"
            + messageId
            + "|"
            + index
            + "|"
            + count
            + "|"
            + totalBytes
            + "|"
            + Base64.getEncoder().encodeToString(bytes);
    if (encoded.getBytes(StandardCharsets.UTF_8).length >= PAYLOAD_LIMIT) {
      throw new IllegalArgumentException("notification carrier exceeds payload limit");
    }
    return encoded;
  }

  /**
   * Parses one carrier. The publisher is read from the fixed header first: an own echo returns
   * {@code null} before Base64 decoding or any reassembly allocation.
   */
  public static NotificationCarrier decode(String value, NotificationLimits limits, UUID self) {
    Objects.requireNonNull(value, "carrier");
    Objects.requireNonNull(limits, "limits");
    if (value.length() >= PAYLOAD_LIMIT
        || value.getBytes(StandardCharsets.UTF_8).length >= PAYLOAD_LIMIT) {
      throw new IllegalArgumentException("oversized notification carrier");
    }
    String[] fields = value.split("\\|", -1);
    if (fields.length != 9 || !fields[0].equals("1") || !fields[3].matches(TOPIC_PATTERN)) {
      throw new IllegalArgumentException("invalid notification carrier header");
    }
    UUID publisher = canonicalUuid(fields[1]);
    if (publisher.equals(self)) {
      return null;
    }
    UUID target = fields[2].equals("*") ? null : canonicalUuid(fields[2]);
    UUID id = canonicalUuid(fields[4]);
    int index = canonicalInt(fields[5]);
    int count = canonicalInt(fields[6]);
    int total = canonicalInt(fields[7]);
    if (total > limits.maxMessageBytes() || count != count(total) || index >= count) {
      throw new IllegalArgumentException("invalid notification carrier dimensions");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(fields[8]);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid notification carrier chunk");
    }
    int expected = Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES);
    if (bytes.length != expected || !Base64.getEncoder().encodeToString(bytes).equals(fields[8])) {
      throw new IllegalArgumentException("invalid notification carrier chunk");
    }
    return new NotificationCarrier(publisher, target, fields[3], id, index, count, total, bytes);
  }

  @Override
  public String toString() {
    return "NotificationCarrier[version=1, publisher="
        + publisher
        + ", target="
        + target
        + ", topic="
        + topic
        + ", messageId="
        + messageId
        + ", index="
        + index
        + ", count="
        + count
        + ", totalBytes="
        + totalBytes
        + "]";
  }

  static String requireTopic(String topic) {
    Objects.requireNonNull(topic, "topic");
    if (!topic.matches(TOPIC_PATTERN)) {
      throw new IllegalArgumentException("invalid notification topic");
    }
    return topic;
  }

  /**
   * Only the lowercase canonical 36-character form is accepted, so {@code 1-1-1-1-1} and mixed case
   * are rejected instead of silently normalized.
   */
  private static UUID canonicalUuid(String field) {
    UUID value;
    try {
      value = UUID.fromString(field);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid notification carrier identity");
    }
    if (!value.toString().equals(field)) {
      throw new IllegalArgumentException("invalid notification carrier identity");
    }
    return value;
  }

  /** Canonical non-negative decimal: no sign, no leading zeros and no integer overflow. */
  private static int canonicalInt(String field) {
    if (field.isEmpty() || field.length() > 10) {
      throw new IllegalArgumentException("invalid notification carrier dimension");
    }
    long value = 0;
    for (int position = 0; position < field.length(); position++) {
      char digit = field.charAt(position);
      if (digit < '0' || digit > '9' || (position == 0 && digit == '0' && field.length() > 1)) {
        throw new IllegalArgumentException("invalid notification carrier dimension");
      }
      value = value * 10 + (digit - '0');
    }
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("invalid notification carrier dimension");
    }
    return (int) value;
  }
}
