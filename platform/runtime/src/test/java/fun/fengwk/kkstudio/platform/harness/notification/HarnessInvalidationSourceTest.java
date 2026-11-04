package fun.fengwk.kkstudio.platform.harness.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * HarnessInvalidationSource：只把 PostgreSQL NOTIFY 投递的 canonical key 转成公开的提示型失效事件。
 *
 * <p>覆盖三条语义：按 key 定点订阅只收本 key、全局订阅收全部、空/畸形 payload 与 LISTEN 重连退化为全量 resync；消费者异常不影响
 * 其他订阅者，释放后不再收到回调。
 */
class HarnessInvalidationSourceTest {

  private static final UUID ROOT_A = new UUID(0L, 1L);
  private static final UUID ROOT_B = new UUID(0L, 2L);

  private HarnessInvalidationSource source;

  @BeforeEach
  void setUp() {
    source = new HarnessInvalidationSource();
  }

  @Test
  void keyedSubscriptionReceivesOnlyItsOwnKeyAndAckCursorIsAlwaysZero() {
    List<Event> rootA = new ArrayList<>();
    List<Event> rootB = new ArrayList<>();
    HarnessChangeSubscription subscriptionA = source.subscribe(ROOT_A, rootA::add);
    HarnessChangeSubscription subscriptionB = source.subscribe(ROOT_B, rootB::add);

    assertEquals(0L, subscriptionA.cursor());
    assertEquals(0L, subscriptionB.cursor());

    source.onNotification(ROOT_A.toString());

    assertEquals(List.of(new Event.Changed(ROOT_A)), rootA);
    assertTrue(rootB.isEmpty(), "其他 key 的通知不得泄漏到本资源");

    subscriptionA.close();
  }

  @Test
  void globalSubscriptionReceivesEveryKeyedNotification() {
    List<Event> global = new ArrayList<>();
    source.subscribe(null, global::add);

    source.onNotification(ROOT_A.toString());
    source.onNotification(ROOT_B.toString());

    assertEquals(List.of(new Event.Changed(ROOT_A), new Event.Changed(ROOT_B)), global);
  }

  @Test
  void emptyOrMalformedPayloadBroadcastsResyncToKeyedAndGlobalSubscribers() {
    List<Event> keyed = new ArrayList<>();
    List<Event> global = new ArrayList<>();
    source.subscribe(ROOT_A, keyed::add);
    source.subscribe(null, global::add);

    source.onNotification("");
    source.onNotification(null);
    source.onNotification("not-a-uuid");
    source.broadcastResync();

    assertEquals(
        List.of(new Event.Resync(), new Event.Resync(), new Event.Resync(), new Event.Resync()),
        keyed,
        "空/畸形 payload 与 LISTEN 重连都必须要求 keyed 订阅整体回读");
    assertEquals(4, global.size());
    assertTrue(global.stream().allMatch(Event.Resync.class::isInstance));
  }

  @Test
  void nonCanonicalUuidPayloadIsTreatedAsMalformed() {
    UUID hexRoot = new UUID(0xabcdef0L, 1L);
    List<Event> global = new ArrayList<>();
    source.subscribe(null, global::add);

    // 大写十六进制与无连字符形式都不是 canonical UUID（与权威读取的字符串不一致），必须退化为 resync 而不是定点失效。
    source.onNotification(hexRoot.toString().toUpperCase(Locale.ROOT));
    source.onNotification(hexRoot.toString().replace("-", ""));

    assertEquals(List.of(new Event.Resync(), new Event.Resync()), global);

    // 同一 UUID 的 canonical 形式仍然定点失效，证明判定的是编码形式而不是 UUID 取值。
    source.onNotification(hexRoot.toString());

    assertEquals(new Event.Changed(hexRoot), global.get(2));
  }

  @Test
  void failingConsumerIsIsolatedFromOtherSubscribers() {
    List<Event> healthy = new ArrayList<>();
    source.subscribe(
        null,
        event -> {
          throw new IllegalStateException("listener failed");
        });
    source.subscribe(null, healthy::add);

    source.onNotification(ROOT_A.toString());

    assertEquals(List.of(new Event.Changed(ROOT_A)), healthy);
  }

  @Test
  void releasedSubscriptionsStopReceivingNotifications() {
    List<Event> keyed = new ArrayList<>();
    HarnessChangeSubscription subscription = source.subscribe(ROOT_A, keyed::add);

    subscription.close();
    subscription.close();
    source.onNotification(ROOT_A.toString());

    assertTrue(keyed.isEmpty());
  }

  @Test
  void changedEventRequiresAKey() {
    assertThrows(NullPointerException.class, () -> new Event.Changed(null));
  }
}
