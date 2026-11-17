package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ThreadTreeSnapshotPublisherTest {

  @Test
  void coalescesChangesWhileProjectingAndEmitsOnlyTheLatestChangedSnapshot() {
    Queue<Runnable> pending = new ArrayDeque<>();
    Executor executor = pending::add;
    AtomicInteger projections = new AtomicInteger();
    AtomicInteger snapshots = new AtomicInteger();
    ThreadTreeSnapshotPublisher.Handle[] handleRef = new ThreadTreeSnapshotPublisher.Handle[1];
    HarnessThreadTreeNodeDTO first = node("root");
    HarnessThreadTreeNodeDTO last = node("latest");
    ThreadTreeSnapshotPublisher publisher =
        new ThreadTreeSnapshotPublisher(
            executor,
            ignored -> {
              int current = projections.incrementAndGet();
              if (current == 1) {
                for (int i = 0; i < 8; i++) {
                  handleRef[0].request();
                }
                return List.of(first);
              }
              return List.of(last);
            });
    handleRef[0] =
        publisher.subscribe(UUID.randomUUID(), ignored -> snapshots.incrementAndGet(), () -> {});

    assertEquals(1, pending.size());
    pending.remove().run();

    assertEquals(2, projections.get());
    assertEquals(2, snapshots.get());
    assertTrue(pending.isEmpty());
    handleRef[0].close();
  }

  @Test
  void retriesUnchangedSnapshotAfterDeliveryFailureAndRequestsResync() {
    Queue<Runnable> pending = new ArrayDeque<>();
    AtomicInteger deliveries = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    List<HarnessThreadTreeNodeDTO> nodes = List.of(node("root"));
    ThreadTreeSnapshotPublisher publisher =
        new ThreadTreeSnapshotPublisher(pending::add, ignored -> nodes);
    ThreadTreeSnapshotPublisher.Handle handle =
        publisher.subscribe(
            UUID.randomUUID(),
            ignored -> {
              if (deliveries.incrementAndGet() == 1) {
                throw new IllegalStateException("send failed");
              }
            },
            failures::incrementAndGet);

    pending.remove().run();
    assertEquals(1, failures.get());
    handle.request();
    pending.remove().run();

    assertEquals(2, deliveries.get());
    assertEquals(1, failures.get());
    handle.close();
  }

  @Test
  void executorRejectionCallsFailureOutsideHandleLock() throws InterruptedException {
    // A cross-thread close must proceed while the rejection callback is running.
    Queue<Runnable> pending = new ArrayDeque<>();
    AtomicInteger executions = new AtomicInteger();
    Executor executor =
        command -> {
          if (executions.getAndIncrement() == 0) {
            pending.add(command);
          } else {
            throw new RejectedExecutionException();
          }
        };
    ThreadTreeSnapshotPublisher.Handle[] handleRef = new ThreadTreeSnapshotPublisher.Handle[1];
    CountDownLatch closedFromFailure = new CountDownLatch(1);
    AtomicBoolean callbackCouldAcquireHandle = new AtomicBoolean();
    ThreadTreeSnapshotPublisher publisher =
        new ThreadTreeSnapshotPublisher(executor, ignored -> List.of(node("root")));
    handleRef[0] =
        publisher.subscribe(
            UUID.randomUUID(),
            ignored -> {},
            () -> {
              Thread closer =
                  new Thread(
                      () -> {
                        handleRef[0].close();
                        closedFromFailure.countDown();
                      });
              closer.start();
              try {
                callbackCouldAcquireHandle.set(closedFromFailure.await(1, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });

    pending.remove().run();
    handleRef[0].request();
    assertTrue(callbackCouldAcquireHandle.get());
    assertTrue(closedFromFailure.await(1, TimeUnit.SECONDS));
  }

  @Test
  void closeFencesQueuedProjection() {
    Queue<Runnable> pending = new ArrayDeque<>();
    AtomicInteger projections = new AtomicInteger();
    AtomicInteger snapshots = new AtomicInteger();
    ThreadTreeSnapshotPublisher publisher =
        new ThreadTreeSnapshotPublisher(
            pending::add,
            ignored -> {
              projections.incrementAndGet();
              return List.of(node("root"));
            });
    ThreadTreeSnapshotPublisher.Handle handle =
        publisher.subscribe(UUID.randomUUID(), ignored -> snapshots.incrementAndGet(), () -> {});

    handle.close();
    pending.remove().run();

    assertEquals(0, projections.get());
    assertEquals(0, snapshots.get());
  }

  @Test
  void closeFencesProjectionAlreadyInProgress() throws InterruptedException {
    Queue<Runnable> pending = new ArrayDeque<>();
    CountDownLatch projecting = new CountDownLatch(1);
    CountDownLatch continueProjection = new CountDownLatch(1);
    AtomicInteger snapshots = new AtomicInteger();
    ThreadTreeSnapshotPublisher publisher =
        new ThreadTreeSnapshotPublisher(
            pending::add,
            ignored -> {
              projecting.countDown();
              try {
                if (!continueProjection.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("projection was not released");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              }
              return List.of(node("root"));
            });
    ThreadTreeSnapshotPublisher.Handle handle =
        publisher.subscribe(UUID.randomUUID(), ignored -> snapshots.incrementAndGet(), () -> {});
    Thread worker = new Thread(pending.remove());
    worker.start();
    boolean projectionStarted = projecting.await(5, TimeUnit.SECONDS);
    handle.close();
    continueProjection.countDown();
    worker.join(5_000);

    assertTrue(projectionStarted);
    assertFalse(worker.isAlive());
    assertEquals(0, snapshots.get());
  }

  private static HarnessThreadTreeNodeDTO node(String name) {
    HarnessThreadTreeNodeDTO node = new HarnessThreadTreeNodeDTO();
    node.setThreadId("00000000-0000-0000-0000-000000000001");
    node.setName(name);
    return node;
  }
}
