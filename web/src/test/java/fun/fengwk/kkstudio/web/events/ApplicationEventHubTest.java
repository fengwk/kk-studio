package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventSource;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Sink;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** ApplicationEventHub：资源上游 refcount 生命周期、ack cursor、激活前缓冲与 fan-out 过滤。 */
class ApplicationEventHubTest {

  private static final UUID THREAD = new UUID(0L, 1L);
  private static final UUID CANVAS = new UUID(0L, 2L);
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);
  private static final ResourceKey CANVAS_KEY = new ResourceKey(ResourceKind.CANVAS, CANVAS);

  private ThreadRevisionEventSource revisionSource;
  private RealtimeEventSource realtimeSource;
  private CanvasVersionEventSource versionSource;
  private ApplicationEventHub hub;

  @BeforeEach
  void setUp() {
    revisionSource = mock(ThreadRevisionEventSource.class);
    realtimeSource = mock(RealtimeEventSource.class);
    versionSource = mock(CanvasVersionEventSource.class);
    when(revisionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(5L, () -> {}));
    when(versionSource.subscribe(any(), any())).thenReturn(new SourceSubscribed(3L, () -> {}));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(() -> {});
    hub = new ApplicationEventHub(revisionSource, realtimeSource, versionSource);
  }

  @Test
  void firstThreadSubscribeEstablishesBothUpstreamsAndReturnsAckCursor() {
    Subscription subscription = hub.subscribe(THREAD_KEY, sink());

    assertEquals(5L, subscription.cursor());
    verify(revisionSource).subscribe(any(UUID.class), any());
    verify(realtimeSource).subscribe(any(UUID.class), any(), any());
  }

  @Test
  void threadUpstreamsAreReleasedWhenLastSubscriberCloses() throws Exception {
    AutoCloseable revisionHandle = mock(AutoCloseable.class);
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(revisionSource.subscribe(any(), any()))
        .thenReturn(new SourceSubscribed(5L, revisionHandle));
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn(realtimeHandle);

    Subscription first = hub.subscribe(THREAD_KEY, sink());
    Subscription second = hub.subscribe(THREAD_KEY, sink());
    verify(revisionSource, times(1)).subscribe(any(), any());
    verify(realtimeSource, times(1)).subscribe(any(), any(), any());

    first.close();
    verify(revisionHandle, never()).close();
    verify(realtimeHandle, never()).close();

    second.close();
    verify(revisionHandle).close();
    verify(realtimeHandle).close();
  }

  @Test
  void canvasSubscribeEstablishesVersionUpstreamAndRefcounts() {
    AtomicReference<AutoCloseable> versionHandle = new AtomicReference<>();
    when(versionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              SourceSubscribed subscribed = new SourceSubscribed(3L, () -> {});
              versionHandle.set(subscribed.handle());
              return subscribed;
            });

    Subscription first = hub.subscribe(CANVAS_KEY, sink());
    Subscription second = hub.subscribe(CANVAS_KEY, sink());
    assertEquals(3L, first.cursor());
    verify(versionSource, times(1)).subscribe(any(), any());

    first.close();
    second.close();
  }

  @Test
  void eventsAreBufferedUntilActivateAndFlushedInOrder() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    List<Signal> signals = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, signals::add);

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    assertTrue(signals.isEmpty(), "events must be buffered before activate");

    subscription.activate();
    assertEquals(List.of(new Signal.Revision("6")), signals);
  }

  @Test
  void staleDurableSignalsAtOrBelowAckCursorAreFiltered() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    List<Signal> signals = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, signals::add);
    subscription.activate();

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("5", false));
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    assertEquals(
        List.of(new Signal.Revision("6")), signals, "revision <= ack cursor must be dropped");
  }

  @Test
  void revisionResyncAndRealtimeSignalsFanOutToEverySubscriber() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    AtomicReference<Consumer<RealtimeEvent>> realtimeConsumer = new AtomicReference<>();
    AtomicReference<Runnable> realtimeResync = new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              realtimeConsumer.set(inv.getArgument(1));
              realtimeResync.set(inv.getArgument(2));
              return (AutoCloseable) () -> {};
            });

    List<Signal> first = new ArrayList<>();
    List<Signal> second = new ArrayList<>();
    Subscription firstSub = hub.subscribe(THREAD_KEY, first::add);
    Subscription secondSub = hub.subscribe(THREAD_KEY, second::add);
    firstSub.activate();
    secondSub.activate();

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event(null, true));
    assertEquals(List.of(new Signal.Resync()), first);
    assertEquals(List.of(new Signal.Resync()), second);

    realtimeConsumer
        .get()
        .accept(
            new RealtimeEvent.ModelDelta(
                THREAD,
                new UUID(0L, 9L),
                1,
                1L,
                new ProviderStreamEvent.TextDelta("hi"),
                Instant.parse("2026-08-05T00:00:00Z")));
    assertEquals(2, first.size(), "resync then realtime");
    assertTrue(first.get(1) instanceof Signal.Realtime);

    realtimeResync.get().run();
    assertEquals(3, first.size());
    assertEquals(new Signal.Resync(), first.get(2));
  }

  @Test
  void multiplexesThreadAndCanvasSubscriptionsIndependently() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    AtomicReference<Consumer<CanvasVersionEventSource.Event>> versionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            });
    when(versionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              versionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(3L, () -> {});
            });

    List<Signal> threadSignals = new ArrayList<>();
    List<Signal> canvasSignals = new ArrayList<>();
    Subscription threadSub = hub.subscribe(THREAD_KEY, threadSignals::add);
    Subscription canvasSub = hub.subscribe(CANVAS_KEY, canvasSignals::add);
    threadSub.activate();
    canvasSub.activate();

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    versionConsumer.get().accept(new CanvasVersionEventSource.Event(4L, false));
    assertEquals(List.of(new Signal.Revision("6")), threadSignals);
    assertEquals(List.of(new Signal.Version(4L)), canvasSignals);
  }

  @Test
  void unknownResourceFailsWithoutLeakingState() {
    when(revisionSource.subscribe(any(), any()))
        .thenThrow(new IllegalArgumentException("unknown thread: " + THREAD));
    assertThrows(IllegalArgumentException.class, () -> hub.subscribe(THREAD_KEY, sink()));

    // 失败后状态干净：修复 source 后同资源可正常订阅。
    doReturn(new SourceSubscribed(5L, () -> {})).when(revisionSource).subscribe(any(), any());
    Subscription subscription = hub.subscribe(THREAD_KEY, sink());
    assertEquals(5L, subscription.cursor());
    subscription.close();
  }

  @Test
  void hubCloseReleasesEveryResourceUpstream() {
    Subscription first = hub.subscribe(THREAD_KEY, sink());
    hub.subscribe(CANVAS_KEY, sink());
    hub.close();
    first.close(); // 关闭后释放是幂等的
  }

  @Test
  void signalsArrivingDuringEstablishAreBufferedAndDeliveredAfterCursorFilter() {
    // establish 期间（第一个订阅者加入前）上游就回调：durable 事件（含 stale）、realtime resync。
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              Consumer<ThreadRevisionEventSource.Event> consumer = inv.getArgument(1);
              consumer.accept(new ThreadRevisionEventSource.Event("4", false)); // <= ack cursor，丢弃
              consumer.accept(new ThreadRevisionEventSource.Event("6", false)); // > ack cursor，保留
              return new SourceSubscribed(5L, () -> {});
            });
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              Runnable resync = inv.getArgument(2);
              resync.run(); // 断连 resync 早于订阅者加入
              return (AutoCloseable) () -> {};
            });

    List<Signal> received = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);
    assertEquals(5L, subscription.cursor());
    subscription.activate();

    // early 信号先按 ack cursor 过滤（4 被丢弃），再缓冲到激活后投递。
    assertEquals(List.of(new Signal.Revision("6"), new Signal.Resync()), received);
    subscription.close();
  }

  @Test
  void concurrentSubscribeReleaseAndFanoutNeverLoseSignalsForLiveSubscribers() throws Exception {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(0L, () -> {});
            });
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn((AutoCloseable) () -> {});

    List<Signal> live = Collections.synchronizedList(new ArrayList<>());
    Subscription keep = hub.subscribe(THREAD_KEY, live::add);
    keep.activate();

    int fanouts = 500;
    int churners = 4;
    ExecutorService executor = Executors.newFixedThreadPool(churners);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(churners);
    try {
      for (int t = 0; t < churners; t++) {
        executor.submit(
            () -> {
              try {
                start.await();
                for (int i = 0; i < 250; i++) {
                  Subscription subscription = hub.subscribe(THREAD_KEY, sink());
                  subscription.close();
                }
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      for (int i = 1; i <= fanouts; i++) {
        revisionConsumer
            .get()
            .accept(new ThreadRevisionEventSource.Event(Integer.toString(i), false));
      }
      done.await(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    // fanout 与 release 都在资源锁内互斥：存活的订阅者在每次 fanout 时必然在集合中，一个信号都不丢。
    assertEquals(fanouts, live.size(), "live subscriber must receive every fanout signal");
    for (int i = 0; i < fanouts; i++) {
      assertTrue(
          live.get(i) instanceof Signal.Revision,
          "signals must arrive in fanout order, got: " + live.get(i));
    }
    keep.close();
  }

  @Test
  void subscribeConcurrentWithLastReleaseNeverRebuildsUpstreamOnDetachedState() throws Exception {
    // 确定性复现 Review 竞态：subscribe 已取得旧 state、最后 release 删除 map 后 subscribe 才继续。
    // release 在状态锁内先 retire + identity 移除 map entry、再关闭旧上游（用 latch 卡住关闭）；subscribe
    // 任务在 release 卡住期间已开始执行（start-pin 保证其状态查找先于 release 的 map 删除）。修复前 release
    // 在 closeQuietly 之后才 remove，此时 subscribe 会拿到旧 state，在 detached state 上重建上游——该订阅
    // close 后其上游句柄永远不会被释放（orphan）；修复后 subscribe 只能取得全新 state，上游可正常释放。
    CountDownLatch releaseClosingStarted = new CountDownLatch(1);
    CountDownLatch releaseClosingDone = new CountDownLatch(1);
    CountDownLatch subscribeTaskStarted = new CountDownLatch(1);
    AtomicReference<AutoCloseable> firstRevisionHandle = new AtomicReference<>();
    AtomicReference<AutoCloseable> secondRevisionHandle = new AtomicReference<>();
    doAnswer(
            inv -> {
              AutoCloseable handle = mock(AutoCloseable.class);
              if (firstRevisionHandle.compareAndSet(null, handle)) {
                doAnswer(
                        blocked -> {
                          releaseClosingStarted.countDown();
                          releaseClosingDone.await();
                          return null;
                        })
                    .when(handle)
                    .close();
                return new SourceSubscribed(5L, handle);
              }
              secondRevisionHandle.set(handle);
              return new SourceSubscribed(5L, handle);
            })
        .when(revisionSource)
        .subscribe(any(), any());

    Subscription first = hub.subscribe(THREAD_KEY, sink());
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> release =
          executor.submit(
              () -> {
                first.close(); // 最后释放：retire + 移除 map entry，随后卡在关闭旧上游句柄
                return null;
              });
      assertTrue(
          releaseClosingStarted.await(5, TimeUnit.SECONDS), "release must reach handle close");

      Future<Subscription> subscribe =
          executor.submit(
              () -> {
                subscribeTaskStarted.countDown();
                return hub.subscribe(THREAD_KEY, sink());
              });
      // 等 subscribe 任务已开始执行（其状态查找必然先于 release 放行后的 map 删除）。
      assertTrue(subscribeTaskStarted.await(5, TimeUnit.SECONDS), "subscribe task must start");
      releaseClosingDone.countDown(); // 放行旧上游关闭
      release.get(5, TimeUnit.SECONDS);
      Subscription second = subscribe.get(5, TimeUnit.SECONDS);

      verify(revisionSource, times(2)).subscribe(any(), any()); // 同一资源只建立两组上游
      verify(firstRevisionHandle.get()).close(); // 最后释放关闭了第一组上游
      second.close();
      verify(secondRevisionHandle.get()).close(); // 修复前 second 建立在 detached state 上，上游永远关不掉
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void lateCallbackAfterLastReleaseIsDropped() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    doAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            })
        .when(revisionSource)
        .subscribe(any(), any());
    List<Signal> received = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);
    subscription.activate();
    subscription.close(); // 最后释放：上游关闭、state 淘汰并移出 map

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    assertTrue(received.isEmpty(), "late callback after last release must be dropped");
  }

  @Test
  void failedEstablishRetiresStateAndDropsLateCallbacks() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> staleConsumer =
        new AtomicReference<>();
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> liveConsumer =
        new AtomicReference<>();
    doAnswer(
            inv -> {
              staleConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            })
        .doAnswer(
            inv -> {
              liveConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            })
        .when(revisionSource)
        .subscribe(any(), any());
    doThrow(new IllegalArgumentException("realtime unavailable"))
        .doReturn((AutoCloseable) () -> {})
        .when(realtimeSource)
        .subscribe(any(), any(), any());

    assertThrows(
        IllegalArgumentException.class,
        () -> hub.subscribe(THREAD_KEY, sink()),
        "establish failure must fail the subscribe");

    // 建立失败后旧 consumer 的迟到回调必须被丢弃（state 已 retired），不得泄漏到后续订阅。
    List<Signal> received = new ArrayList<>();
    staleConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);
    subscription.activate();
    assertTrue(
        received.isEmpty(),
        "late callback from failed establish must not leak into the new subscription");

    liveConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false));
    assertEquals(List.of(new Signal.Revision("6")), received);
    subscription.close();
  }

  @Test
  void concurrentSubscribeAndCloseLeavesNoLiveSubscriptionOrUpstream() throws Exception {
    // 复现 close 与 publish 竞态：closed 检查若只在 compute 内、close 只观察 isEmpty，subscribe 可在
    // close 排空后插入存活状态并建立上游。围栏把「设立 closed」与「发布状态」串在同一把锁上后，
    // 并发 subscribe 要么在 close 前完成（随后被 close 淘汰），要么看到 closed 被拒绝。
    AtomicInteger established = new AtomicInteger();
    AtomicInteger closedUpstreams = new AtomicInteger();
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              established.incrementAndGet();
              return new SourceSubscribed(5L, closedUpstreams::incrementAndGet);
            });
    when(realtimeSource.subscribe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              established.incrementAndGet();
              return (AutoCloseable) closedUpstreams::incrementAndGet;
            });

    int workers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(workers + 1);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> tasks = new ArrayList<>();
    List<Subscription> accepted = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < workers; i++) {
        ResourceKey key = new ResourceKey(ResourceKind.THREAD, new UUID(0L, 100L + i));
        tasks.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    accepted.add(hub.subscribe(key, sink()));
                  } catch (IllegalStateException ignored) {
                    // close 已设立边界：拒绝是正确结果。
                  }
                  return null;
                }));
      }
      tasks.add(
          executor.submit(
              () -> {
                start.await();
                hub.close();
                return null;
              }));
      start.countDown();
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }

      // close() 返回后上游必须已经全部释放：若再先 close 本地订阅，会把泄漏的存活上游误清掉。
      assertEquals(
          established.get(),
          closedUpstreams.get(),
          "every established upstream must be closed; none may survive hub close");
      assertThrows(IllegalStateException.class, () -> hub.subscribe(THREAD_KEY, sink()));
      assertEquals(
          established.get(),
          closedUpstreams.get(),
          "post-close subscribe must not establish another upstream");
      for (Subscription subscription : accepted) {
        subscription.activate();
        subscription.close();
      }
      assertEquals(
          established.get(),
          closedUpstreams.get(),
          "accepted subscriptions must already be retired; release is a no-op");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void hubCloseMarksSubscriptionsClosedAndRejectsFurtherSubscribe() {
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    doAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            })
        .when(revisionSource)
        .subscribe(any(), any());
    List<Signal> received = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);

    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("6", false)); // 激活前缓冲
    hub.close();

    // 关闭后：本地订阅被标记关闭，旧回调与迟到 activate 都不能再向 sink 投递。
    revisionConsumer.get().accept(new ThreadRevisionEventSource.Event("7", false));
    subscription.activate();
    assertTrue(received.isEmpty(), "no delivery after hub close");
    subscription.close(); // 幂等
    hub.close(); // 幂等

    assertThrows(IllegalStateException.class, () -> hub.subscribe(THREAD_KEY, sink()));
  }

  @Test
  void pendingOverflowCollapsesToSingleResyncAndStopsAccumulating() {
    // 激活前缓冲超过 MAX_BUFFERED_SIGNALS：清空并折叠为单个 Resync，后续信号不再累积（内存有界）；激活后恢复直接投递。
    AtomicReference<Consumer<ThreadRevisionEventSource.Event>> revisionConsumer =
        new AtomicReference<>();
    doAnswer(
            inv -> {
              revisionConsumer.set(inv.getArgument(1));
              return new SourceSubscribed(5L, () -> {});
            })
        .when(revisionSource)
        .subscribe(any(), any());
    List<Signal> received = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);

    for (int i = 1; i <= ApplicationEventHub.MAX_BUFFERED_SIGNALS; i++) {
      revisionConsumer
          .get()
          .accept(new ThreadRevisionEventSource.Event(Integer.toString(i + 5), false));
    }
    // 第 MAX_BUFFERED_SIGNALS + 1 个信号触发折叠。
    revisionConsumer
        .get()
        .accept(
            new ThreadRevisionEventSource.Event(
                Integer.toString(ApplicationEventHub.MAX_BUFFERED_SIGNALS + 6), false));
    // 折叠后不再累积。
    revisionConsumer
        .get()
        .accept(
            new ThreadRevisionEventSource.Event(
                Integer.toString(ApplicationEventHub.MAX_BUFFERED_SIGNALS + 7), false));

    subscription.activate();
    assertEquals(List.of(new Signal.Resync()), received, "overflow must collapse to one resync");

    // 激活后直接投递，不再折叠。
    revisionConsumer
        .get()
        .accept(
            new ThreadRevisionEventSource.Event(
                Integer.toString(ApplicationEventHub.MAX_BUFFERED_SIGNALS + 8), false));
    assertEquals(
        List.of(
            new Signal.Resync(),
            new Signal.Revision(Integer.toString(ApplicationEventHub.MAX_BUFFERED_SIGNALS + 8))),
        received);
    subscription.close();
  }

  @Test
  void earlyOverflowCollapsesToSingleResyncBeforeFirstSubscriber() {
    // establish 期间（首个订阅者加入前）信号超过 MAX_BUFFERED_SIGNALS：early 折叠为单个 Resync 且不再累积；
    // 首订阅者回放得到 Resync（可恢复），之后建立期间回调不再缓冲。
    when(revisionSource.subscribe(any(), any()))
        .thenAnswer(
            inv -> {
              Consumer<ThreadRevisionEventSource.Event> consumer = inv.getArgument(1);
              for (int i = 1; i <= ApplicationEventHub.MAX_BUFFERED_SIGNALS + 1; i++) {
                consumer.accept(
                    new ThreadRevisionEventSource.Event(Integer.toString(i + 5), false));
              }
              consumer.accept(
                  new ThreadRevisionEventSource.Event(
                      Integer.toString(ApplicationEventHub.MAX_BUFFERED_SIGNALS + 7), false));
              return new SourceSubscribed(5L, () -> {});
            });
    when(realtimeSource.subscribe(any(), any(), any())).thenReturn((AutoCloseable) () -> {});

    List<Signal> received = new ArrayList<>();
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add);
    subscription.activate();

    assertEquals(List.of(new Signal.Resync()), received, "early overflow must fold to one resync");
    subscription.close();
  }

  private static Sink sink() {
    return signal -> {};
  }
}
