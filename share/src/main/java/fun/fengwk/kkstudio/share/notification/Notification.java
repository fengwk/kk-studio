package fun.fengwk.kkstudio.share.notification;

import java.util.Objects;
import java.util.UUID;

/** Payloads must be immutable. Transport metadata is not a domain ownership fence. */
public record Notification<T>(
    UUID messageId,
    UUID publisherNodeId,
    NotificationAddress address,
    NotificationTopic<T> topic,
    T payload) {
  public Notification {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(publisherNodeId, "publisherNodeId");
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(payload, "payload");
  }
}
