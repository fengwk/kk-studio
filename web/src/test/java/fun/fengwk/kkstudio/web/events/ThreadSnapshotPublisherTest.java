package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ThreadSnapshotPublisherTest {

  private static final UUID THREAD = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID BASE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID HEAD = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void activatedSubscribersWithSameCursorShareOneProjectionAndReceiveSameVersionUpdates() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    List<List<UUID>> requestedCursors = new ArrayList<>();
    AtomicInteger deliveries = new AtomicInteger();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              requestedCursors.add(cursors);
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            Executors.newSingleThreadScheduledExecutor());
    ThreadSnapshotPublisher.Handle first =
        publisher.subscribe(THREAD, BASE, ignored -> deliveries.incrementAndGet(), () -> {});
    ThreadSnapshotPublisher.Handle second =
        publisher.subscribe(THREAD, BASE, ignored -> deliveries.incrementAndGet(), () -> {});

    first.activate();
    second.activate();
    tasks.remove().run();
    first.request(false);
    tasks.remove().run();

    assertEquals(List.of(List.of(BASE), List.of(HEAD)), requestedCursors);
    assertEquals(4, deliveries.get());
    first.close();
    second.close();
  }

  @Test
  void fullRefreshDuringProjectionFencesOldBatchAndReadsLatestHead() throws InterruptedException {
    CountDownLatch firstProjectionStarted = new CountDownLatch(1);
    CountDownLatch continueFirstProjection = new CountDownLatch(1);
    List<List<UUID>> requestedCursors = new ArrayList<>();
    List<String> deliveredHeads = new ArrayList<>();
    CountDownLatch delivered = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            executor,
            (threadId, cursors) -> {
              synchronized (requestedCursors) {
                requestedCursors.add(cursors);
              }
              if (firstProjectionStarted.getCount() == 1) {
                firstProjectionStarted.countDown();
                try {
                  if (!continueFirstProjection.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("projection was not released");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              }
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            Executors.newSingleThreadScheduledExecutor());
    ThreadSnapshotPublisher.Handle handle =
        publisher.subscribe(
            THREAD,
            BASE,
            value -> {
              deliveredHeads.add(value.getThread().getHeadEntryId());
              delivered.countDown();
            },
            () -> {});
    handle.activate();
    assertTrue(firstProjectionStarted.await(5, TimeUnit.SECONDS));
    handle.request(true);
    continueFirstProjection.countDown();
    assertTrue(delivered.await(5, TimeUnit.SECONDS));
    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    assertEquals(List.of(List.of(BASE), Collections.singletonList(null)), requestedCursors);
    assertEquals(List.of(HEAD.toString()), deliveredHeads);
    handle.close();
  }

  @Test
  void projectionFailureReportsBeforeOneConcurrentDirtyReadAndThenStops() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    AtomicInteger projections = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    ThreadSnapshotPublisher.Handle[] handleRef = new ThreadSnapshotPublisher.Handle[1];
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              if (projections.incrementAndGet() == 1) {
                handleRef[0].request(false);
                throw new IllegalStateException("temporary read failure");
              }
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            Executors.newSingleThreadScheduledExecutor());
    handleRef[0] = publisher.subscribe(THREAD, BASE, ignored -> {}, failures::incrementAndGet);
    handleRef[0].activate();

    tasks.remove().run();
    assertEquals(1, projections.get());
    assertEquals(1, failures.get());
    assertEquals(1, tasks.size());

    tasks.remove().run();
    assertEquals(2, projections.get());
    assertTrue(tasks.isEmpty());
    handleRef[0].request(false);
    tasks.remove().run();
    assertEquals(3, projections.get());
    handleRef[0].close();
  }

  /** 测试意图：投递回调报错时订阅游标不得推进，下一次投影仍用原游标（未被接受的快照不能当已投递）。 */
  @Test
  void rejectedDeliveryKeepsTheSubscriberCursorAtItsPreviousEntry() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    List<List<UUID>> requestedCursors = new ArrayList<>();
    AtomicInteger deliveries = new AtomicInteger();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              requestedCursors.add(cursors);
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            Executors.newSingleThreadScheduledExecutor());
    ThreadSnapshotPublisher.Handle handle =
        publisher.subscribe(
            THREAD,
            BASE,
            ignored -> {
              if (deliveries.incrementAndGet() == 1) {
                // 传输层拒绝该快照（未启动 drain），发布器必须把它当未投递。
                throw new IllegalStateException("thread snapshot was not queued");
              }
            },
            () -> {});
    handle.activate();
    tasks.remove().run();

    handle.request(false);
    tasks.remove().run();

    assertEquals(List.of(List.of(BASE), List.of(BASE)), requestedCursors);
    handle.close();
  }

  @Test
  void closeOldThenActivatePendingHandlesJoinTheCurrentSharedState() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    AtomicInteger projections = new AtomicInteger();
    AtomicInteger oldDeliveries = new AtomicInteger();
    AtomicInteger pendingDeliveries = new AtomicInteger();
    AtomicInteger currentDeliveries = new AtomicInteger();
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            tasks::add,
            (threadId, cursors) -> {
              projections.incrementAndGet();
              return cursors.stream().map(ignored -> snapshot(HEAD)).toList();
            },
            Executors.newSingleThreadScheduledExecutor());

    ThreadSnapshotPublisher.Handle old =
        publisher.subscribe(THREAD, BASE, ignored -> oldDeliveries.incrementAndGet(), () -> {});
    old.activate();
    tasks.remove().run();
    assertEquals(1, oldDeliveries.get());

    ThreadSnapshotPublisher.Handle pending =
        publisher.subscribe(THREAD, BASE, ignored -> pendingDeliveries.incrementAndGet(), () -> {});
    old.close();
    ThreadSnapshotPublisher.Handle current =
        publisher.subscribe(THREAD, BASE, ignored -> currentDeliveries.incrementAndGet(), () -> {});
    current.activate();
    pending.activate();

    assertEquals(1, tasks.size(), "both handles must enqueue work on the current Thread state");
    tasks.remove().run();
    assertEquals(2, projections.get(), "pending and current subscribers share one projection");
    assertEquals(1, pendingDeliveries.get());
    assertEquals(1, currentDeliveries.get());
    current.close();
    pending.close();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshnessDeadlineSchedulesOneSidecarRefresh(boolean crossesDeadline)
      throws InterruptedException {
    Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    CountDownLatch freshnessQueued = new CountDownLatch(1);
    AtomicInteger projections = new AtomicInteger();
    AtomicInteger submissions = new AtomicInteger();
    var scheduler = new ScheduledThreadPoolExecutor(1);
    scheduler.setRemoveOnCancelPolicy(true);
    ThreadSnapshotPublisher publisher =
        new ThreadSnapshotPublisher(
            command -> {
              tasks.add(command);
              if (submissions.incrementAndGet() > 1) {
                freshnessQueued.countDown();
              }
            },
            (threadId, cursors) -> {
              int projection = projections.incrementAndGet();
              HarnessThreadSnapshotDTO snapshot = snapshot(HEAD);
              ToolInvocationDTO tool = new ToolInvocationDTO();
              Instant deadline = Instant.now().plusMillis(500);
              if (crossesDeadline && projection == 1) {
                // A lease observed live at read time can expire before the DTO is published.
                deadline = Instant.now().plusMillis(10);
                while (Instant.now().isBefore(deadline)) {
                  try {
                    Thread.sleep(10);
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  }
                }
              }
              tool.setEnvironmentWaitFreshnessAt(deadline);
              snapshot.setToolInvocations(List.of(tool));
              return cursors.stream().map(ignored -> snapshot).toList();
            },
            scheduler);
    ThreadSnapshotPublisher.Handle handle =
        publisher.subscribe(THREAD, BASE, ignored -> {}, () -> {});
    handle.activate();
    tasks.remove().run();

    assertTrue(freshnessQueued.await(3, TimeUnit.SECONDS));
    tasks.remove().run();
    assertEquals(2, projections.get());
    handle.close();
    assertEquals(0, scheduler.getQueue().size(), "last unsubscribe cancels the freshness timer");
    scheduler.shutdownNow();
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
