package fun.fengwk.kkstudio.harness.infra.realtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** RealtimeBus：本地优先、跨节点发布、origin 去回环与关闭边界。 */
class RealtimeBusTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final UUID LOCAL = id(900L);
  private static final UUID REMOTE = id(901L);

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();

  /** 单节点：本地立即投递，节点存在判定为 false 时不产生任何远端发布。 */
  @Test
  void singleNodeDeliversLocallyWithoutRemotePublish() {
    RecordingSink remote = new RecordingSink();
    AtomicInteger presenceChecks = new AtomicInteger();
    try (RealtimeBus bus =
        bus(
            remote,
            () -> {
              presenceChecks.incrementAndGet();
              return false;
            })) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});

      RealtimeEvent event = modelDelta("one");
      bus.append(event);

      assertEquals(List.of(event), received);
      assertEquals(0, remote.appends);
      assertEquals(1, presenceChecks.get());
    }
  }

  /** 双节点：同一事件本地投递一次，并恰好跨 PostgreSQL 发布一次。 */
  @Test
  void multiNodeDeliversLocallyThenPublishesOnce() {
    RecordingSink remote = new RecordingSink();
    try (RealtimeBus bus = bus(remote, () -> true)) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});

      RealtimeEvent event = modelDelta("one");
      bus.append(event);

      assertEquals(List.of(event), received);
      assertEquals(1, remote.appends);
      assertEquals(event, remote.events.getFirst());
    }
  }

  /** 远端资源失败不影响已经完成的本地投递，异常继续向上报告。 */
  @Test
  void remoteFailureStillDeliversLocallyAndPropagates() {
    IllegalStateException failure = new IllegalStateException("postgres unavailable");
    RealtimeEventSink remote =
        event -> {
          throw failure;
        };
    try (RealtimeBus bus = bus(remote, () -> true)) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});

      RealtimeEvent event = modelDelta("one");

      assertEquals(failure, assertThrows(IllegalStateException.class, () -> bus.append(event)));
      assertEquals(List.of(event), received);
    }
  }

  /** 节点存在查询失败同样在本地投递后可见，不静默退化为单节点。 */
  @Test
  void presenceFailureStillDeliversLocallyAndPropagates() {
    IllegalStateException failure = new IllegalStateException("cluster query unavailable");
    try (RealtimeBus bus =
        bus(
            new RecordingSink(),
            () -> {
              throw failure;
            })) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});

      RealtimeEvent event = modelDelta("one");

      assertEquals(failure, assertThrows(IllegalStateException.class, () -> bus.append(event)));
      assertEquals(List.of(event), received);
    }
  }

  /** PostgreSQL 会把 NOTIFY 回送给发布者；本节点 envelope 必须被丢弃，不能本地再投一次。 */
  @Test
  void ownOriginNotificationIsIgnored() {
    RecordingSink remote = new RecordingSink();
    try (RealtimeBus bus = bus(remote, () -> false)) {
      List<RealtimeEvent> received = new ArrayList<>();
      AtomicInteger resyncs = new AtomicInteger();
      bus.subscribe(id(1L), received::add, resyncs::incrementAndGet);

      bus.onNotification(codec.encodeEvent(LOCAL, modelDelta("loopback")));

      assertEquals(List.of(), received);
      assertEquals(0, resyncs.get());
      assertEquals(0, remote.appends);
    }
  }

  /** 远端事件只投递给本地订阅一次，绝不再次发布形成跨节点 echo。 */
  @Test
  void remoteNotificationIsDeliveredOnceWithoutEcho() {
    RecordingSink remote = new RecordingSink();
    try (RealtimeBus bus = bus(remote, () -> true)) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});
      RealtimeEvent event = modelDelta("remote");

      bus.onNotification(codec.encodeEvent(REMOTE, event));

      assertEquals(List.of(event), received);
      assertEquals(0, remote.appends);
    }
  }

  /** 远端 RESYNC 同样按 origin 过滤，只触发目标 Thread 的本地恢复。 */
  @Test
  void remoteResyncIsDeliveredByOrigin() {
    try (RealtimeBus bus = bus(new RecordingSink(), () -> false)) {
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();
      bus.subscribe(id(1L), ignored -> {}, first::incrementAndGet);
      bus.subscribe(id(2L), ignored -> {}, second::incrementAndGet);

      bus.onNotification(codec.encodeResync(REMOTE, id(1L), "test"));
      bus.onNotification(codec.encodeResync(LOCAL, id(2L), "loopback"));

      assertEquals(1, first.get());
      assertEquals(0, second.get());
    }
  }

  /** 批量先完成全部本地投递，再以一次远端批量发布；顺序与输入一致。 */
  @Test
  void appendAllDeliversAllLocallyThenPublishesOneBatch() {
    RecordingSink remote = new RecordingSink();
    try (RealtimeBus bus = bus(remote, () -> true)) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});
      List<RealtimeEvent> events = List.of(modelDelta("one"), modelDelta("two"));

      bus.appendAll(events);

      assertEquals(events, received);
      assertEquals(1, remote.batches);
      assertEquals(events, remote.events);
    }
  }

  /** 畸变 notification 无法安全定位 Thread，触发全部本地订阅恢复。 */
  @Test
  void malformedNotificationResyncsAllLocalSubscribers() {
    try (RealtimeBus bus = bus(new RecordingSink(), () -> false)) {
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();
      bus.subscribe(id(1L), ignored -> {}, first::incrementAndGet);
      bus.subscribe(id(2L), ignored -> {}, second::incrementAndGet);

      bus.onNotification("{not-json");

      assertEquals(1, first.get());
      assertEquals(1, second.get());
    }
  }

  /** close 后不再产生本地回调，也不再发布；重复 close 幂等。 */
  @Test
  void closeStopsCallbacksAndPublishing() {
    RecordingSink remote = new RecordingSink();
    RealtimeBus bus = bus(remote, () -> true);
    AtomicInteger events = new AtomicInteger();
    AtomicInteger resyncs = new AtomicInteger();
    bus.subscribe(id(1L), ignored -> events.incrementAndGet(), resyncs::incrementAndGet);

    bus.close();
    bus.close();
    bus.onNotification(codec.encodeEvent(REMOTE, modelDelta("dropped")));
    bus.onNotification("{not-json");
    bus.onResync();
    bus.append(modelDelta("dropped"));

    assertEquals(0, events.get());
    assertEquals(0, resyncs.get());
    assertEquals(0, remote.appends);
    assertThrows(IllegalStateException.class, () -> bus.subscribe(id(1L), ignored -> {}, () -> {}));
  }

  private RealtimeBus bus(RealtimeEventSink remote, BooleanSupplier otherActiveNodes) {
    return new RealtimeBus(
        LOCAL, codec, new PostgresqlRealtimeEventSource(codec), remote, otherActiveNodes);
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return new RealtimeEvent.ModelDelta(
        id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  private static final class RecordingSink implements RealtimeEventSink {

    private final List<RealtimeEvent> events = new ArrayList<>();
    private int appends;
    private int batches;

    @Override
    public void append(RealtimeEvent event) {
      appends++;
      events.add(event);
    }

    @Override
    public void appendAll(List<RealtimeEvent> batch) {
      batches++;
      events.addAll(batch);
    }
  }
}
