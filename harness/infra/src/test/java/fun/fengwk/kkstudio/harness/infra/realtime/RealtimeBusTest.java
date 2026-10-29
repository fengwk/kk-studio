package fun.fengwk.kkstudio.harness.infra.realtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.postgresql.RealtimeNotificationCodec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimePayloadFetcher;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadUnavailableException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** RealtimeBus：本地优先、跨节点发布、origin 去回环与关闭边界。 */
class RealtimeBusTest {

  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
  private static final UUID LOCAL = id(900L);
  private static final UUID REMOTE = id(901L);

  private final RealtimeNotificationCodec codec = new RealtimeNotificationCodec();
  private final RealtimePayloadBuffer payloadBuffer = new RealtimePayloadBuffer();

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

  /** 本地回调关闭 bus 后，append 不得继续查询 presence 或发布远端。 */
  @Test
  void callbackClosingBusPreventsRemotePublishOnAppend() {
    RecordingSink remote = new RecordingSink();
    AtomicInteger presenceChecks = new AtomicInteger();
    RealtimeBus bus =
        bus(
            remote,
            () -> {
              presenceChecks.incrementAndGet();
              return true;
            });
    bus.subscribe(id(1L), ignored -> bus.close(), () -> {});

    bus.append(modelDelta("closing"));

    assertEquals(0, remote.appends);
    assertEquals(0, presenceChecks.get());
  }

  /** 批量中间某个本地回调关闭 bus 后，appendAll 不得继续发布整批远端事件。 */
  @Test
  void callbackClosingBusPreventsRemotePublishOnAppendAll() {
    RecordingSink remote = new RecordingSink();
    AtomicInteger presenceChecks = new AtomicInteger();
    RealtimeBus bus =
        bus(
            remote,
            () -> {
              presenceChecks.incrementAndGet();
              return true;
            });
    bus.subscribe(id(1L), ignored -> bus.close(), () -> {});

    bus.appendAll(List.of(modelDelta("one"), modelDelta("two")));

    assertEquals(0, remote.batches);
    assertEquals(0, presenceChecks.get());
  }

  /** REFERENCE 回拉期间 bus 被关闭时不得再投递或触发 resync。 */
  @Test
  void closeDuringReferenceFetchPreventsDelivery() {
    RealtimeEvent event = modelDelta("fetched");
    String canonical = codec.encodeEventPayload(event);
    UUID payloadId = payloadBuffer.put(event.threadId(), canonical).orElseThrow();
    AtomicReference<RealtimeBus> busRef = new AtomicReference<>();
    RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            event1 -> {
              throw new AssertionError("must not echo");
            },
            () -> false,
            (origin, thread, id) -> {
              busRef.get().close();
              return canonical;
            },
            payloadBuffer,
            new InlineExecutorService());
    busRef.set(bus);
    List<RealtimeEvent> received = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    bus.subscribe(event.threadId(), received::add, resyncs::incrementAndGet);

    bus.onNotification(codec.encodeReference(REMOTE, event.threadId(), payloadId));

    assertEquals(List.of(), received);
    assertEquals(0, resyncs.get());
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

  /** REFERENCE 从 origin 回拉完整事件后只本地投递一次，绝不再次跨节点发布。 */
  @Test
  void referenceIsFetchedAndDeliveredOnceWithoutEcho() {
    RecordingSink remote = new RecordingSink();
    RealtimeEvent event = modelDelta("oversized");
    String canonical = codec.encodeEventPayload(event);
    UUID payloadId = payloadBuffer.put(event.threadId(), canonical).orElseThrow();
    List<UUID> fetched = new ArrayList<>();
    try (RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            remote,
            () -> true,
            (origin, thread, id) -> {
              assertEquals(REMOTE, origin);
              assertEquals(event.threadId(), thread);
              fetched.add(id);
              return canonical;
            },
            payloadBuffer,
            new InlineExecutorService())) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(event.threadId(), received::add, () -> {});

      bus.onNotification(codec.encodeReference(REMOTE, event.threadId(), payloadId));

      assertEquals(List.of(payloadId), fetched);
      assertEquals(List.of(event), received);
      assertEquals(0, remote.appends);
    }
  }

  /** 回拉事件的 Thread 与 REFERENCE 不符时不得投递，只对参考 Thread 触发 RESYNC。 */
  @Test
  void mismatchedFetchedThreadResyncsWithoutDelivery() {
    RealtimeEvent otherThread = modelDelta(id(2L), "other");
    String canonical = codec.encodeEventPayload(otherThread);
    UUID payloadId = payloadBuffer.put(otherThread.threadId(), canonical).orElseThrow();
    try (RealtimeBus bus =
        bus(new RecordingSink(), () -> false, (origin, thread, id) -> canonical)) {
      List<RealtimeEvent> received = new ArrayList<>();
      AtomicInteger targetResyncs = new AtomicInteger();
      AtomicInteger otherResyncs = new AtomicInteger();
      bus.subscribe(id(1L), received::add, targetResyncs::incrementAndGet);
      bus.subscribe(otherThread.threadId(), received::add, otherResyncs::incrementAndGet);

      bus.onNotification(codec.encodeReference(REMOTE, id(1L), payloadId));

      assertEquals(List.of(), received);
      assertEquals(1, targetResyncs.get());
      assertEquals(0, otherResyncs.get());
    }
  }

  /** 回拉失败（过期、驱逐、无租约或传输失败）只 RESYNC，不投递猜测内容。 */
  @Test
  void referenceFetchFailureResyncsWithoutDelivery() {
    try (RealtimeBus bus =
        bus(
            new RecordingSink(),
            () -> false,
            (origin, thread, id) -> {
              throw new RealtimePayloadUnavailableException("expired");
            })) {
      List<RealtimeEvent> received = new ArrayList<>();
      AtomicInteger resyncs = new AtomicInteger();
      bus.subscribe(id(1L), received::add, resyncs::incrementAndGet);

      bus.onNotification(codec.encodeReference(REMOTE, id(1L), id(77L)));

      assertEquals(List.of(), received);
      assertEquals(1, resyncs.get());
    }
  }

  /** 自身 origin 的 REFERENCE 与 EVENT 一样在本地 append 时已投递，必须丢弃且不得触发回拉。 */
  @Test
  void ownOriginReferenceIsIgnoredWithoutFetch() {
    AtomicInteger fetches = new AtomicInteger();
    try (RealtimeBus bus =
        bus(
            new RecordingSink(),
            () -> false,
            (origin, thread, id) -> {
              fetches.incrementAndGet();
              throw new AssertionError("own origin must not be fetched");
            })) {
      List<RealtimeEvent> received = new ArrayList<>();
      bus.subscribe(id(1L), received::add, () -> {});

      bus.onNotification(codec.encodeReference(LOCAL, id(1L), id(77L)));

      assertEquals(0, fetches.get());
      assertEquals(List.of(), received);
    }
  }

  /** close 释放 owner payload buffer；之后再读取只能得到空，绝不返回旧内容。 */
  @Test
  void closeReleasesPayloadBuffer() {
    RealtimeEvent event = modelDelta("buffered");
    UUID payloadId =
        payloadBuffer.put(event.threadId(), codec.encodeEventPayload(event)).orElseThrow();
    RealtimeBus bus = bus(new RecordingSink(), () -> false);

    bus.close();

    assertTrue(payloadBuffer.get(payloadId).isEmpty());
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

  /** REFERENCE 回拉期间同 Thread 的后续通知排在其后按到达顺序投递，其它 Thread 立即投递，不被阻塞的回拉拖住。 */
  @Test
  void referenceFetchKeepsPerThreadOrderWithoutBlockingOtherThreads() throws Exception {
    RealtimeEvent oversized = modelDelta("oversized");
    String canonical = codec.encodeEventPayload(oversized);
    UUID payloadId = payloadBuffer.put(oversized.threadId(), canonical).orElseThrow();
    CountDownLatch fetchStarted = new CountDownLatch(1);
    CountDownLatch releaseFetch = new CountDownLatch(1);
    ExecutorService resolver = Executors.newVirtualThreadPerTaskExecutor();
    try (RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            new RecordingSink(),
            () -> false,
            (origin, thread, id) -> {
              fetchStarted.countDown();
              await(releaseFetch);
              return canonical;
            },
            payloadBuffer,
            resolver)) {
      List<RealtimeEvent> sameThread = new CopyOnWriteArrayList<>();
      List<RealtimeEvent> otherThread = new CopyOnWriteArrayList<>();
      bus.subscribe(id(1L), sameThread::add, () -> {});
      bus.subscribe(id(2L), otherThread::add, () -> {});
      RealtimeEvent after = modelDelta("after");
      RealtimeEvent other = modelDelta(id(2L), "other");

      bus.onNotification(codec.encodeReference(REMOTE, id(1L), payloadId));
      assertTrue(fetchStarted.await(5, TimeUnit.SECONDS));
      bus.onNotification(codec.encodeEvent(REMOTE, after));
      bus.onNotification(codec.encodeEvent(REMOTE, other));

      assertEquals(List.of(other), otherThread);
      assertEquals(List.of(), sameThread);
      releaseFetch.countDown();
      awaitSize(sameThread, 2);
      assertEquals(List.of(oversized, after), sameThread);
    }
  }

  /** 通道积压达到上限时丢弃积压并以一条 RESYNC 代替，触发溢出的通知及之后的通知照常排队，内存有界且不静默丢失。 */
  @Test
  void laneOverflowReplacesBacklogWithResync() throws Exception {
    RealtimeEvent oversized = modelDelta("oversized");
    String canonical = codec.encodeEventPayload(oversized);
    UUID payloadId = payloadBuffer.put(oversized.threadId(), canonical).orElseThrow();
    CountDownLatch fetchStarted = new CountDownLatch(1);
    CountDownLatch releaseFetch = new CountDownLatch(1);
    try (RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            new RecordingSink(),
            () -> false,
            (origin, thread, id) -> {
              fetchStarted.countDown();
              await(releaseFetch);
              return canonical;
            },
            payloadBuffer,
            Executors.newVirtualThreadPerTaskExecutor())) {
      List<RealtimeEvent> received = new CopyOnWriteArrayList<>();
      AtomicInteger resyncs = new AtomicInteger();
      bus.subscribe(id(1L), received::add, resyncs::incrementAndGet);

      bus.onNotification(codec.encodeReference(REMOTE, id(1L), payloadId));
      assertTrue(fetchStarted.await(5, TimeUnit.SECONDS));
      for (int i = 0; i < RealtimeBus.MAX_PENDING_PER_THREAD; i++) {
        bus.onNotification(codec.encodeEvent(REMOTE, modelDelta("backlog-" + i)));
      }
      RealtimeEvent tail = modelDelta("tail");
      bus.onNotification(codec.encodeEvent(REMOTE, tail));
      releaseFetch.countDown();

      // 触发溢出的那条通知保留在 RESYNC 之后；更早的积压被丢弃。
      awaitSize(received, 3);
      assertEquals(
          List.of(
              oversized, modelDelta("backlog-" + (RealtimeBus.MAX_PENDING_PER_THREAD - 1)), tail),
          received);
      assertEquals(1, resyncs.get());
    }
  }

  /** 关闭会中断进行中的回拉并释放 resolver；之后不再投递也不触发 resync。 */
  @Test
  void closeInterruptsInFlightReferenceFetch() throws Exception {
    CountDownLatch fetchStarted = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ExecutorService resolver = Executors.newVirtualThreadPerTaskExecutor();
    RealtimeBus bus =
        new RealtimeBus(
            LOCAL,
            codec,
            new PostgresqlRealtimeEventSource(codec),
            new RecordingSink(),
            () -> false,
            (origin, thread, id) -> {
              fetchStarted.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              throw new RealtimePayloadUnavailableException("interrupted");
            },
            payloadBuffer,
            resolver);
    AtomicInteger resyncs = new AtomicInteger();
    bus.subscribe(id(1L), ignored -> {}, resyncs::incrementAndGet);

    bus.onNotification(codec.encodeReference(REMOTE, id(1L), id(77L)));
    assertTrue(fetchStarted.await(5, TimeUnit.SECONDS));
    bus.close();

    assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    assertTrue(resolver.awaitTermination(5, TimeUnit.SECONDS));
    assertEquals(0, resyncs.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void awaitSize(List<?> list, int size) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (list.size() < size && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(size, list.size());
  }

  /** 在调用线程内执行的 ExecutorService：让不关心并发的用例保持同步断言。 */
  private static final class InlineExecutorService extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  private RealtimeBus bus(RealtimeEventSink remote, BooleanSupplier otherActiveNodes) {
    return bus(
        remote,
        otherActiveNodes,
        (origin, thread, payloadId) -> {
          throw new RealtimePayloadUnavailableException("unavailable");
        });
  }

  private RealtimeBus bus(
      RealtimeEventSink remote,
      BooleanSupplier otherActiveNodes,
      RealtimePayloadFetcher payloadFetcher) {
    return new RealtimeBus(
        LOCAL,
        codec,
        new PostgresqlRealtimeEventSource(codec),
        remote,
        otherActiveNodes,
        payloadFetcher,
        payloadBuffer,
        new InlineExecutorService());
  }

  private static RealtimeEvent.ModelDelta modelDelta(String text) {
    return modelDelta(id(1L), text);
  }

  private static RealtimeEvent.ModelDelta modelDelta(UUID threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        threadId, id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
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
