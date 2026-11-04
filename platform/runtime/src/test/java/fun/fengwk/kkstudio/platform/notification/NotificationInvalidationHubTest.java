package fun.fengwk.kkstudio.platform.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NotificationInvalidationHub：canonical UUID payload 按 key 定点失效，全局订阅收全部；畸形 payload 与重连退化为 resync。
 *
 * <p>生产方（Project / Environment / Harness）只定义各自 channel 与订阅入口，本测试固定通用 fan-out 语义。
 */
class NotificationInvalidationHubTest {

  private static final UUID KEY_A = new UUID(0L, 1L);
  private static final UUID KEY_B = new UUID(0L, 2L);

  @Test
  void dispatchesCanonicalKeyAndReleasesSubscription() {
    NotificationInvalidationHub hub = new NotificationInvalidationHub();
    List<Event> changed = new ArrayList<>();
    Runnable release = hub.subscribe(KEY_A, changed::add);

    hub.onNotification(KEY_A.toString());
    assertEquals(List.of(new Event.Changed(KEY_A)), changed);

    release.run();
    hub.onNotification(KEY_A.toString());
    assertEquals(List.of(new Event.Changed(KEY_A)), changed);
  }

  @Test
  void keyedSubscriptionReceivesOnlyItsOwnKeyAndGlobalReceivesEveryKey() {
    NotificationInvalidationHub hub = new NotificationInvalidationHub();
    List<Event> keyed = new ArrayList<>();
    List<Event> global = new ArrayList<>();
    hub.subscribe(KEY_A, keyed::add);
    hub.subscribe(null, global::add);

    hub.onNotification(KEY_A.toString());
    hub.onNotification(KEY_B.toString());

    assertEquals(List.of(new Event.Changed(KEY_A)), keyed);
    assertEquals(List.of(new Event.Changed(KEY_A), new Event.Changed(KEY_B)), global);
  }

  @Test
  void emptyMalformedAndNonCanonicalPayloadsTriggerResync() {
    NotificationInvalidationHub hub = new NotificationInvalidationHub();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(
        KEY_A,
        event -> {
          if (event instanceof Event.Resync) {
            resyncs.incrementAndGet();
          }
        });

    UUID hexKey = new UUID(0xabcdef0L, 1L);
    hub.onNotification(null);
    hub.onNotification("");
    hub.onNotification("not-a-key");
    hub.onNotification(hexKey.toString().toUpperCase(Locale.ROOT));
    hub.onNotification(hexKey.toString().replace("-", ""));
    hub.broadcastResync();

    assertEquals(6, resyncs.get());
  }

  @Test
  void isolatesFailingSubscribers() {
    NotificationInvalidationHub hub = new NotificationInvalidationHub();
    hub.subscribe(
        KEY_A,
        event -> {
          throw new IllegalStateException("listener failed");
        });
    List<Event> healthy = new ArrayList<>();
    hub.subscribe(KEY_A, healthy::add);
    UUID key = KEY_A;

    hub.onNotification(key.toString());
    hub.broadcastResync();

    assertEquals(List.of(new Event.Changed(key), new Event.Resync()), healthy);
  }

  @Test
  void changedEventRequiresAKey() {
    assertThrows(NullPointerException.class, () -> new Event.Changed(null));
  }

  @Test
  void releaseIsIdempotentAndStopsDelivery() {
    NotificationInvalidationHub hub = new NotificationInvalidationHub();
    List<Event> received = new ArrayList<>();
    Runnable release = hub.subscribe(null, received::add);

    release.run();
    release.run();
    hub.onNotification(KEY_A.toString());

    assertTrue(received.isEmpty());
  }
}
