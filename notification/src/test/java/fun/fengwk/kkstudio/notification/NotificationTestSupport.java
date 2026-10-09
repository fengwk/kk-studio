package fun.fengwk.kkstudio.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.share.notification.Notification;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationCodec;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Strict UTF-8 fixture and deterministic callback barriers, shared by unit and real PG tests. */
final class NotificationTestSupport {
  static final NotificationTopic<String> EVENTS = topic("test.events", false);
  static final NotificationTopic<String> HINTS = topic("test.hints", true);

  static NotificationTopic<String> topic(String name, boolean hint) {
    return new NotificationTopic<>(
        name,
        new NotificationCodec<>() {
          @Override
          public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
          }

          @Override
          public String decode(byte[] bytes) {
            try {
              return StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes))
                  .toString();
            } catch (CharacterCodingException error) {
              throw new IllegalArgumentException("invalid UTF-8", error);
            }
          }
        },
        hint);
  }

  static NotificationLimits smallLimits(int capacity) {
    return new NotificationLimits(20000, 40000, capacity, 40000, 2, Duration.ofSeconds(5), 1);
  }

  static NotificationPacket wire(UUID publisher, UUID target, String payload) {
    return new NotificationPacket(
        publisher, target, EVENTS.name(), UUID.randomUUID(), EVENTS.codec().encode(payload));
  }

  static Notification<String> notification(String payload) {
    return new Notification<>(
        UUID.randomUUID(), UUID.randomUUID(), NotificationAddress.broadcast(), EVENTS, payload);
  }

  static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(5, TimeUnit.SECONDS), "callback barrier timed out");
  }

  static void hold(CountDownLatch latch) {
    try {
      await(latch);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("callback interrupted", error);
    }
  }

  private NotificationTestSupport() {}
}
