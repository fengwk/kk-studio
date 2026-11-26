package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.notification.CanvasVersionEventSource;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscriptions;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges;
import fun.fengwk.kkstudio.platform.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.events.SourceSubscribed;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;
import fun.fengwk.kkstudio.platform.harness.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationSource;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

class ThreadSnapshotHubIntegrationTest {

  private static final UUID THREAD = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID BASE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID HEAD = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final ResourceKey THREAD_KEY = new ResourceKey(ResourceKind.THREAD, THREAD);

  private HarnessChangeSubscriptions changes;
  private HarnessInvalidationChanges threadSnapshots;
  private RealtimeEventSource realtimeSource;
  private RealtimeSubscriptionService realtimeSubscriptions;
  private CanvasVersionEventSource canvasVersions;
  private NotificationInvalidationSource environments;
  private ScheduledExecutorService scheduler;
  private AtomicReference<Consumer<HarnessInvalidationChanges.Event>> threadInvalidation;
  private AtomicReference<Consumer<NotificationInvalidationSource.Event>> environmentInvalidation;
  private ApplicationEventHub hub;

  @BeforeEach
  void setUp() {
    changes = mock(HarnessChangeSubscriptions.class);
    threadSnapshots = mock(HarnessInvalidationChanges.class);
    realtimeSource = mock(RealtimeEventSource.class);
    realtimeSubscriptions = mock(RealtimeSubscriptionService.class);
    canvasVersions = mock(CanvasVersionEventSource.class);
    environments = mock(NotificationInvalidationSource.class);
    scheduler = Executors.newSingleThreadScheduledExecutor();
    threadInvalidation = new AtomicReference<>();
    environmentInvalidation = new AtomicReference<>();
    when(changes.threadSnapshots()).thenReturn(threadSnapshots);
    when(threadSnapshots.subscribe(eq(THREAD), any()))
        .thenAnswer(
            invocation -> {
              threadInvalidation.set(invocation.getArgument(1));
              return subscribed(() -> {});
            });
    when(realtimeSource.subscribe(eq(THREAD), any(), any())).thenReturn(() -> {});
    when(canvasVersions.subscribe(any(), any())).thenReturn(new SourceSubscribed(6L, () -> {}));
    when(environments.subscribe(any(), any()))
        .thenAnswer(
            invocation -> {
              environmentInvalidation.set(invocation.getArgument(1));
              return (Runnable) () -> {};
            });
  }

  @AfterEach
  void tearDown() {
    if (hub != null) {
      hub.close();
    }
    scheduler.shutdownNow();
  }

  @Test
  void sameThreadAndCursorBatchReadsOnceAndNotificationDuringReadGetsTrailingSnapshot() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    List<List<UUID>> reads = new ArrayList<>();
    List<Signal> firstSignals = new ArrayList<>();
    List<Signal> secondSignals = new ArrayList<>();
    AtomicBoolean notifyDuringRead = new AtomicBoolean(true);
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              reads.add(cursors);
              if (notifyDuringRead.getAndSet(false)) {
                threadInvalidation
                    .get()
                    .accept(new HarnessInvalidationChanges.Event.Changed(THREAD));
              }
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            scheduler);
    hub = createHub(publisher, () -> 16);

    Subscription first = hub.subscribe(THREAD_KEY, firstSignals::add, BASE);
    Subscription second = hub.subscribe(THREAD_KEY, secondSignals::add, BASE);
    first.activate();
    second.activate();
    assertEquals(1, tasks.size(), "the pending catch-up batch shares the per-thread worker");
    tasks.remove().run();

    assertEquals(List.of(List.of(BASE), List.of(HEAD)), reads);
    assertEquals(2, firstSignals.size());
    assertEquals(2, secondSignals.size());
    assertTrue(firstSignals.stream().allMatch(Signal.ThreadSnapshot.class::isInstance));
    assertTrue(secondSignals.stream().allMatch(Signal.ThreadSnapshot.class::isInstance));
    first.close();
    second.close();
  }

  @Test
  void ackCursorIsZeroAndClosingLastSubscriberReleasesSnapshotAndRealtimeUpstreams()
      throws Exception {
    AutoCloseable realtimeHandle = mock(AutoCloseable.class);
    when(realtimeSource.subscribe(eq(THREAD), any(), any())).thenReturn(realtimeHandle);
    AtomicReference<AutoCloseable> snapshotHandle = new AtomicReference<>();
    when(threadSnapshots.subscribe(eq(THREAD), any()))
        .thenAnswer(
            invocation -> {
              threadInvalidation.set(invocation.getArgument(1));
              AutoCloseable handle = mock(AutoCloseable.class);
              snapshotHandle.set(handle);
              return subscribed(handle);
            });
    hub =
        createHub(
            new ThreadSnapshotPublisher(Runnable::run, (id, cursors) -> List.of(), scheduler),
            () -> 8);

    Subscription subscription = hub.subscribe(THREAD_KEY, ignored -> {}, null);
    assertEquals(0, subscription.cursor());
    subscription.close();

    verify(snapshotHandle.get()).close();
    verify(realtimeHandle).close();
    verify(realtimeSubscriptions).unregister(THREAD);
  }

  @Test
  void threadSnapshotsPreserveDtoHeadRatherThanDerivingItFromVisibleEntries() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    List<UUID> requested = new ArrayList<>();
    List<Signal> received = new ArrayList<>();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              requested.addAll(cursors);
              HarnessThreadSnapshotDTO snapshot = snapshot(HEAD);
              snapshot.setEntries(List.of());
              return cursors.stream().map(ignored -> snapshot).toList();
            },
            scheduler);
    hub = createHub(publisher, () -> 8);

    Subscription subscription = hub.subscribe(THREAD_KEY, received::add, BASE);
    Subscription sibling = hub.subscribe(THREAD_KEY, ignored -> {}, BASE);
    subscription.activate();
    sibling.activate();
    tasks.remove().run();
    assertTrue(hub.requestThreadSnapshot(subscription));
    tasks.remove().run();

    assertEquals(Arrays.asList(BASE, null, HEAD), requested);
    assertEquals(2, received.size());
    subscription.close();
    sibling.close();
  }

  @Test
  void environmentChangeRefreshesThreadSidecarWithoutChangingDurableHead() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    AtomicInteger projection = new AtomicInteger();
    List<Signal> received = new ArrayList<>();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              HarnessThreadSnapshotDTO snapshot = snapshot(HEAD);
              ToolInvocationDTO tool = new ToolInvocationDTO();
              tool.setEnvironmentWaitFreshnessAt(null);
              tool.setWaitingForEnvironment(projection.incrementAndGet() > 1);
              snapshot.setToolInvocations(List.of(tool));
              return cursors.stream().map(ignored -> snapshot).toList();
            },
            scheduler);
    hub = createHub(publisher, () -> 8);
    Subscription subscription = hub.subscribe(THREAD_KEY, received::add, BASE);
    subscription.activate();
    tasks.remove().run();

    environmentInvalidation
        .get()
        .accept(new NotificationInvalidationSource.Event.Changed(UUID.randomUUID()));
    tasks.remove().run();

    assertEquals(2, received.size());
    assertEquals(2, projection.get());
    subscription.close();
  }

  @Test
  void invalidationBeforeActivationIsRecoveredByCatchupAfterAck() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    List<UUID> requested = new ArrayList<>();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              requested.addAll(cursors);
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            scheduler);
    hub = createHub(publisher, () -> 8);
    Subscription subscription = hub.subscribe(THREAD_KEY, ignored -> {}, BASE);

    threadInvalidation.get().accept(new HarnessInvalidationChanges.Event.Resync());
    assertTrue(tasks.isEmpty(), "unactivated subscribers are attached only after ACK");
    subscription.activate();
    tasks.remove().run();

    assertEquals(List.of(BASE), requested);
    subscription.close();
  }

  private ApplicationEventHub createHub(ThreadSnapshotPublisher publisher, IntSupplier capacity) {
    return new ApplicationEventHub(
        changes,
        realtimeSource,
        realtimeSubscriptions,
        canvasVersions,
        environments,
        capacity,
        treePublisher(),
        publisher);
  }

  private static ThreadTreeSnapshotPublisher treePublisher() {
    return new ThreadTreeSnapshotPublisher(Runnable::run, ignored -> List.of());
  }

  private static HarnessChangeSubscription subscribed(AutoCloseable closeable) {
    return new HarnessChangeSubscription(
        0,
        () -> {
          try {
            closeable.close();
          } catch (Exception error) {
            throw new IllegalStateException(error);
          }
        });
  }

  private static HarnessThreadSnapshotDTO snapshot(UUID headEntryId) {
    HarnessThreadDTO thread = new HarnessThreadDTO();
    thread.setThreadId(THREAD.toString());
    thread.setHeadEntryId(headEntryId == null ? null : headEntryId.toString());
    HarnessThreadSnapshotDTO snapshot = new HarnessThreadSnapshotDTO();
    snapshot.setThread(thread);
    snapshot.setEntries(List.of());
    snapshot.setToolInvocations(List.of());
    return snapshot;
  }
}
