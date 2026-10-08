package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.share.notification.EntityHint;
import fun.fengwk.kkstudio.share.notification.NotificationCodecs;
import fun.fengwk.kkstudio.web.events.InvalidationEventSource.Event;

import java.nio.charset.StandardCharsets;
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

    hub.onNotification(ROOT_A);

    assertEquals(List.of(Event.changed(ROOT_A)), rootA);
    assertTrue(rootB.isEmpty(), "其他 key 的通知不得泄漏到本资源");

    subscriptionA.handle().close();
  }

  @Test
  void globalSubscriptionReceivesEveryKeyedNotification() {
    List<Event> global = new ArrayList<>();
    hub.subscribe(null, global::add);

    hub.onNotification(ROOT_A);
    hub.onNotification(ROOT_B);

    assertEquals(List.of(Event.changed(ROOT_A), Event.changed(ROOT_B)), global);
  }

  @Test
  void emptyOrMalformedPayloadBroadcastsResyncToKeyedAndGlobalSubscribers() {
    List<Event> keyed = new ArrayList<>();
    List<Event> global = new ArrayList<>();
    hub.subscribe(ROOT_A, keyed::add);
    hub.subscribe(null, global::add);

    hub.onNotification((UUID) null);
    EntityHint emptyHint = new EntityHint(null);
    hub.onNotification(emptyHint.entityId());
    hub.broadcastResync();

    assertEquals(
        List.of(Event.fullResync(), Event.fullResync(), Event.fullResync()),
        keyed,
        "空/null key、空 EntityHint 与总线 resync 都必须要求 keyed 订阅整体回读");
    assertEquals(3, global.size());
    assertTrue(global.stream().allMatch(Event::resync));
  }

  @Test
  void nonCanonicalUuidPayloadIsTreatedAsMalformed() {
    UUID hexRoot = new UUID(0xabcdef0L, 1L);
    List<Event> global = new ArrayList<>();
    hub.subscribe(null, global::add);

    String upper = hexRoot.toString().toUpperCase(Locale.ROOT);
    String noHyphens = hexRoot.toString().replace("-", "");

    // 大写十六进制与无连字符形式都被总线 codec 严格拒绝，并退化为 resync
    assertThrows(
        RuntimeException.class,
        () -> NotificationCodecs.UUID_CODEC.decode(upper.getBytes(StandardCharsets.UTF_8)));
    hub.broadcastResync();

    assertThrows(
        RuntimeException.class,
        () -> NotificationCodecs.UUID_CODEC.decode(noHyphens.getBytes(StandardCharsets.UTF_8)));
    hub.broadcastResync();

    assertEquals(List.of(Event.fullResync(), Event.fullResync()), global);

    // 同一 UUID 的 canonical 形式正常解码并定点失效
    UUID decoded =
        NotificationCodecs.UUID_CODEC.decode(hexRoot.toString().getBytes(StandardCharsets.UTF_8));
    hub.onNotification(decoded);

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

    hub.onNotification(ROOT_A);

    assertEquals(List.of(Event.changed(ROOT_A)), healthy);
  }

  @Test
  void releasedSubscriptionsStopReceivingNotifications() throws Exception {
    List<Event> keyed = new ArrayList<>();
    SourceSubscribed subscription = hub.subscribe(ROOT_A, keyed::add);

    subscription.handle().close();
    hub.onNotification(ROOT_A);

    assertTrue(keyed.isEmpty());
  }

  @Test
  void resyncEventRejectsAKey() {
    assertThrows(IllegalArgumentException.class, () -> new Event(ROOT_A, true));
  }

  /** 消费者异常日志只记录 errorType，绝不回显领域异常消息或堆栈（隐私契约）。 */
  @Test
  void failingConsumerLogOmitsDomainMessageAndThrowable() {
    ListAppender<ILoggingEvent> logs = attachListAppender(NotificationInvalidationHub.class);
    try {
      hub.subscribe(
          null,
          event -> {
            throw new IllegalStateException("SECRET-domain-message");
          });
      hub.onNotification(ROOT_A);
    } finally {
      detachListAppender(NotificationInvalidationHub.class, logs);
    }

    assertEquals(1, logs.list.size());
    ILoggingEvent event = logs.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    String message = event.getFormattedMessage();
    assertTrue(message.contains("errorType=IllegalStateException"), message);
    assertFalse(message.contains("SECRET-domain-message"), message);
    assertNull(event.getThrowableProxy(), "failure log must not retain the domain throwable");
  }

  /** 临时给指定 logger 挂一个 logback ListAppender，用于断言日志 level、格式化消息与 throwable。 */
  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> type) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
    return appender;
  }

  private static void detachListAppender(Class<?> type, ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    appender.stop();
  }
}
