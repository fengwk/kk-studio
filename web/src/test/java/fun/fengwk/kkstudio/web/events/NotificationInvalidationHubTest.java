package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.web.events.InvalidationEventSource.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * NotificationInvalidationHub：只把 PostgreSQL NOTIFY 投递的 canonical key 转成提示型失效信号。
 *
 * <p>覆盖三条语义：按 key 定点订阅只收本 key、全局订阅收全部、空/畸形 payload 与 LISTEN 重连退化为全量 resync；消费者异常不
 * 影响其他订阅者，释放后不再收到回调。
 */
class NotificationInvalidationHubTest {

  private static final UUID ROOT_A = new UUID(0L, 1L);
  private static final UUID ROOT_B = new UUID(0L, 2L);

  private NotificationInvalidationHub hub;

  @BeforeEach
  void setUp() {
    hub = new NotificationInvalidationHub();
  }

  @Test
  void keyedSubscriptionReceivesOnlyItsOwnKeyAndAckCursorIsAlwaysZero() throws Exception {
    List<Event> rootA = new ArrayList<>();
    List<Event> rootB = new ArrayList<>();
    SourceSubscribed subscriptionA = hub.subscribe(ROOT_A, rootA::add);
    SourceSubscribed subscriptionB = hub.subscribe(ROOT_B, rootB::add);

    assertEquals(0L, subscriptionA.cursor());
    assertEquals(0L, subscriptionB.cursor());

    hub.onNotification(ROOT_A.toString());

    assertEquals(List.of(Event.changed(ROOT_A)), rootA);
    assertTrue(rootB.isEmpty(), "其他 key 的通知不得泄漏到本资源");

    subscriptionA.handle().close();
  }

  @Test
  void globalSubscriptionReceivesEveryKeyedNotification() {
    List<Event> global = new ArrayList<>();
    hub.subscribe(null, global::add);

    hub.onNotification(ROOT_A.toString());
    hub.onNotification(ROOT_B.toString());

    assertEquals(List.of(Event.changed(ROOT_A), Event.changed(ROOT_B)), global);
  }

  @Test
  void emptyOrMalformedPayloadBroadcastsResyncToKeyedAndGlobalSubscribers() {
    List<Event> keyed = new ArrayList<>();
    List<Event> global = new ArrayList<>();
    hub.subscribe(ROOT_A, keyed::add);
    hub.subscribe(null, global::add);

    hub.onNotification("");
    hub.onNotification(null);
    hub.onNotification("not-a-uuid");
    hub.broadcastResync();

    assertEquals(
        List.of(Event.fullResync(), Event.fullResync(), Event.fullResync(), Event.fullResync()),
        keyed,
        "空/畸形 payload 与 LISTEN 重连都必须要求 keyed 订阅整体回读");
    assertEquals(4, global.size());
    assertTrue(global.stream().allMatch(Event::resync));
  }

  @Test
  void nonCanonicalUuidPayloadIsTreatedAsMalformed() {
    UUID hexRoot = new UUID(0xabcdef0L, 1L);
    List<Event> global = new ArrayList<>();
    hub.subscribe(null, global::add);

    // 大写十六进制与无连字符形式都不是 canonical UUID（与权威读取的字符串不一致），必须退化为 resync 而不是定点失效。
    hub.onNotification(hexRoot.toString().toUpperCase(Locale.ROOT));
    hub.onNotification(hexRoot.toString().replace("-", ""));

    assertEquals(List.of(Event.fullResync(), Event.fullResync()), global);

    // 同一 UUID 的 canonical 形式仍然定点失效，证明判定的是编码形式而不是 UUID 取值。
    hub.onNotification(hexRoot.toString());

    assertEquals(Event.changed(hexRoot), global.get(2));
  }

  @Test
  void failingConsumerIsIsolatedFromOtherSubscribers() {
    List<Event> healthy = new ArrayList<>();
    hub.subscribe(
        null,
        event -> {
          throw new IllegalStateException("listener failed");
        });
    hub.subscribe(null, healthy::add);

    hub.onNotification(ROOT_A.toString());

    assertEquals(List.of(Event.changed(ROOT_A)), healthy);
  }

  @Test
  void releasedSubscriptionsStopReceivingNotifications() throws Exception {
    List<Event> keyed = new ArrayList<>();
    SourceSubscribed subscription = hub.subscribe(ROOT_A, keyed::add);

    subscription.handle().close();
    hub.onNotification(ROOT_A.toString());

    assertTrue(keyed.isEmpty());
  }

  @Test
  void resyncEventRejectsAKey() {
    assertThrows(IllegalArgumentException.class, () -> new Event(ROOT_A, true));
  }
}
