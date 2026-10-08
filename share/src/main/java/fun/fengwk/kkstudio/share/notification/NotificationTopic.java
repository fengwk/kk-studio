package fun.fengwk.kkstudio.share.notification;

import java.util.Objects;

/**
 * A statically bound domain topic. Hints coalesce identical payloads within a physical transaction.
 */
public record NotificationTopic<T>(String name, NotificationCodec<T> codec, boolean hint) {
  public NotificationTopic {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(codec, "codec");
    if (!name.matches("[a-z][a-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("invalid notification topic");
    }
  }
}
