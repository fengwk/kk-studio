package fun.fengwk.kkstudio.share.notification;

/**
 * Strict domain codec. Implementations must reject malformed payloads and return immutable values.
 */
public interface NotificationCodec<T> {
  byte[] encode(T payload);

  T decode(byte[] payload);
}
