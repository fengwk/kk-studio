package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
import java.util.concurrent.TimeUnit;
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

  private static Sink sink() {
    return signal -> {};
  }
}
