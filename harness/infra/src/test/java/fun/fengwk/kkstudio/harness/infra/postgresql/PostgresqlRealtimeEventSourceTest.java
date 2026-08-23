package fun.fengwk.kkstudio.harness.infra.postgresql;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** PostgresqlRealtimeEventSource 的本地订阅、分发、恢复与关闭边界契约。 */
class PostgresqlRealtimeEventSourceTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
  private final PostgresqlRealtimeEventSource source = new PostgresqlRealtimeEventSource(codec);

  /** EVENT 只进入对应 Thread，且同 Thread 的多个本地订阅都收到同一个完整事件。 */
  @Test
  void eventDispatchIsThreadIsolatedAndSharedByMultipleSubscribers() {
    RealtimeEvent.ModelDelta event = modelDelta(1L, "one");
    List<RealtimeEvent> first = new ArrayList<>();
    List<RealtimeEvent> second = new ArrayList<>();
    List<RealtimeEvent> otherThread = new ArrayList<>();
    source.subscribe(id(1L), first::add, () -> {});
    source.subscribe(id(1L), second::add, () -> {});
    source.subscribe(id(2L), otherThread::add, () -> {});

    source.onNotification(codec.encodeEvent(event));

    assertEquals(List.of(event), first);
    assertEquals(List.of(event), second);
    assertEquals(List.of(), otherThread);
  }

  /** 单 subscriber 的事件回调异常必须被隔离，不阻断同 Thread 的其他 subscriber，也不触发 resync。 */
  @Test
  void failingSubscriberDoesNotBlockOtherSubscribersOrTriggerResync() {
    RealtimeEvent.ModelDelta event = modelDelta(1L, "one");
    List<RealtimeEvent> received = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    source.subscribe(
        id(1L),
        ignored -> {
          throw new IllegalStateException("broken subscriber");
        },
        resyncs::incrementAndGet);
    source.subscribe(id(1L), received::add, resyncs::incrementAndGet);

    source.onNotification(codec.encodeEvent(event));

    assertEquals(List.of(event), received);
    assertEquals(0, resyncs.get());
  }

  /** 合法 RESYNC 只恢复 envelope 指定的 Thread，不影响其他本地 Thread。 */
  @Test
  void resyncEnvelopeTargetsItsThread() {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    source.subscribe(id(1L), ignored -> {}, first::incrementAndGet);
    source.subscribe(id(2L), ignored -> {}, second::incrementAndGet);

    source.onNotification(codec.encodeResync(id(1L), "EVENT_TOO_LARGE"));

    assertEquals(1, first.get());
    assertEquals(0, second.get());
  }

  /** malformed 与 unknown notification 都无法安全定位 Thread，因此每次都触发所有本地订阅 resync。 */
  @Test
  void malformedAndUnknownPayloadsResyncAllLocalSubscribers() {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    source.subscribe(id(1L), ignored -> {}, first::incrementAndGet);
    source.subscribe(id(2L), ignored -> {}, second::incrementAndGet);

    source.onNotification("{not-json");
    source.onNotification("{\"kind\":\"UNKNOWN\"}");

    assertEquals(2, first.get());
    assertEquals(2, second.get());
  }

  /** 统一 listener 显式报告 startup/reconnect 时，所有当前本地订阅都必须恢复，异常回调不阻断其他订阅。 */
  @Test
  void explicitReconnectResyncsAllAndIsolatesFailingCallback() {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    source.subscribe(
        id(1L),
        ignored -> {},
        () -> {
          first.incrementAndGet();
          throw new IllegalStateException("broken resync");
        });
    source.subscribe(id(2L), ignored -> {}, second::incrementAndGet);

    source.onResync();

    assertEquals(1, first.get());
    assertEquals(1, second.get());
  }

  /** 关闭单个订阅后立即停止其回调，其他共享订阅继续工作；订阅句柄重复 close 幂等。 */
  @Test
  void subscriptionCloseStopsOnlyThatSubscriber() throws Exception {
    RealtimeEvent.ModelDelta event = modelDelta(1L, "one");
    List<RealtimeEvent> first = new ArrayList<>();
    List<RealtimeEvent> second = new ArrayList<>();
    AutoCloseable firstSubscription = source.subscribe(id(1L), first::add, () -> {});
    source.subscribe(id(1L), second::add, () -> {});

    firstSubscription.close();
    firstSubscription.close();
    source.onNotification(codec.encodeEvent(event));

    assertEquals(List.of(), first);
    assertEquals(List.of(event), second);
  }

  /** 最后一个订阅关闭后移除 Thread 状态，后续该 Thread 的 EVENT/RESYNC 都是 no-op。 */
  @Test
  void lastSubscriptionCloseRemovesThreadState() throws Exception {
    AtomicInteger events = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    AutoCloseable subscription =
        source.subscribe(id(1L), ignored -> events.incrementAndGet(), resyncs::incrementAndGet);

    subscription.close();
    source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));
    source.onNotification(codec.encodeResync(id(1L), "test"));

    assertEquals(0, events.get());
    assertEquals(0, resyncs.get());
  }

  /** 同一批次中先执行的 subscriber 关闭后续 subscriber 时，后者必须被关闭围栏拦截。 */
  @Test
  void subscriberClosedDuringDispatchIsSkipped() {
    AtomicInteger events = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    AutoCloseable[] later = new AutoCloseable[1];
    source.subscribe(id(1L), ignored -> closeUnchecked(later[0]), () -> closeUnchecked(later[0]));
    later[0] =
        source.subscribe(id(1L), ignored -> events.incrementAndGet(), resyncs::incrementAndGet);

    source.onNotification(codec.encodeEvent(modelDelta(1L, "one")));
    assertEquals(0, events.get());

    later[0] =
        source.subscribe(id(1L), ignored -> events.incrementAndGet(), resyncs::incrementAndGet);
    source.onResync();
    assertEquals(0, resyncs.get());
  }

  /** Source close 后拒绝新订阅，已有回调与 resync 永久停止，重复 close 幂等。 */
  @Test
  void sourceCloseRejectsSubscriptionsAndDropsLaterCallbacks() {
    AtomicInteger events = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    source.subscribe(id(1L), ignored -> events.incrementAndGet(), resyncs::incrementAndGet);

    source.close();
    source.close();
    source.onNotification(codec.encodeEvent(modelDelta(1L, "dropped")));
    source.onNotification("{not-json");
    source.onResync();

    assertEquals(0, events.get());
    assertEquals(0, resyncs.get());
    assertThrows(
        IllegalStateException.class, () -> source.subscribe(id(1L), ignored -> {}, () -> {}));
  }

  private static RealtimeEvent.ModelDelta modelDelta(long threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        id(threadId), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private static void closeUnchecked(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
