package fun.fengwk.kkstudio.notification;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * One ASCII carrier for all messages, including count=1. Byte chunks cannot split UTF-8 semantics:
 * only the fully reassembled bytes are passed to the domain codec.
 */
record Carrier(
    UUID publisher,
    UUID target,
    String topic,
    UUID messageId,
    int index,
    int count,
    int totalBytes,
    byte[] bytes) {
  static final int CHUNK_BYTES = 5400;
  static final int PAYLOAD_LIMIT = 7900;

  static int count(int bytes) {
    return Math.max(1, (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES);
  }

  static Carrier chunk(WireMessage message, int index) {
    int start = index * CHUNK_BYTES;
    byte[] payload = message.bytes();
    return new Carrier(
        message.publisher(),
        message.target(),
        message.topic(),
        message.messageId(),
        index,
        count(payload.length),
        payload.length,
        Arrays.copyOfRange(payload, start, Math.min(payload.length, start + CHUNK_BYTES)));
  }

  String encode() {
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

  static Carrier decode(String value, NotificationLimits limits, UUID self) {
    Objects.requireNonNull(value, "carrier");
    if (value.length() >= PAYLOAD_LIMIT
        || value.getBytes(StandardCharsets.UTF_8).length >= PAYLOAD_LIMIT) {
      throw new IllegalArgumentException("oversized carrier");
    }
    String[] fields = value.split("\\|", -1);
    if (fields.length != 9
        || !fields[0].equals("1")
        || !fields[3].matches("[a-z][a-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("invalid carrier header");
    }
    UUID publisher = UUID.fromString(fields[1]);
    if (publisher.equals(self)) {
      return null;
    }
    UUID target = fields[2].equals("*") ? null : UUID.fromString(fields[2]);
    UUID id = UUID.fromString(fields[4]);
    int index = Integer.parseInt(fields[5]);
    int count = Integer.parseInt(fields[6]);
    int total = Integer.parseInt(fields[7]);
    if (total < 0
        || total > limits.maxMessageBytes()
        || count != count(total)
        || index < 0
        || index >= count) {
      throw new IllegalArgumentException("invalid carrier dimensions");
    }
    byte[] bytes = Base64.getDecoder().decode(fields[8]);
    int expected = Math.min(CHUNK_BYTES, total - index * CHUNK_BYTES);
    if (bytes.length != expected || !Base64.getEncoder().encodeToString(bytes).equals(fields[8])) {
      throw new IllegalArgumentException("invalid carrier chunk");
    }
    return new Carrier(publisher, target, fields[3], id, index, count, total, bytes);
  }
}
