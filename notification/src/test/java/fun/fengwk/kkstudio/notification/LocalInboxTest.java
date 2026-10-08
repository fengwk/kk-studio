package fun.fengwk.kkstudio.notification;

import static fun.fengwk.kkstudio.notification.NotificationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LocalInboxTest {
  @Test
  void handlersHaveIndependentOrderedWorkersNotPublisherThread() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch fastDone = new CountDownLatch(3);
    List<String> fast = new CopyOnWriteArrayList<>();
    AtomicReference<Thread> consumerThread = new AtomicReference<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(3))) {
      inbox.subscribe(
          EVENTS,
          value -> {
            blocked.countDown();
            hold(release);
          },
          () -> {});
      inbox.subscribe(
          EVENTS,
          value -> {
            consumerThread.set(Thread.currentThread());
            fast.add(value);
            fastDone.countDown();
          },
          () -> {});
      inbox.accept(notification("one"), 3);
      await(blocked);
      inbox.accept(notification("two"), 3);
      inbox.accept(notification("three"), 5);
      await(fastDone);
      assertEquals(List.of("one", "two", "three"), fast);
      assertNotEquals(Thread.currentThread(), consumerThread.get());
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  @Test
  void fullNormalQueueUsesIndependentCoalescedRecoverySlot() throws Exception {
    CountDownLatch initialRecovery = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch overflowRecovery = new CountDownLatch(1);
    AtomicInteger recoveries = new AtomicInteger();
    List<String> values = new CopyOnWriteArrayList<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(1))) {
      inbox.subscribe(
          EVENTS,
          value -> {
            values.add(value);
            started.countDown();
            hold(release);
          },
          () -> {
            // 订阅建立时的首次权威恢复先消费，之后的恢复才来自溢出/重连折叠。
            if (recoveries.incrementAndGet() == 1) {
              initialRecovery.countDown();
            } else {
              overflowRecovery.countDown();
            }
          });
      await(initialRecovery);
      inbox.accept(notification("first"), 5);
      await(started);
      inbox.accept(notification("queued"), 6);
      inbox.accept(notification("overflow"), 8);
      inbox.resync(EVENTS.name());
      inbox.resyncAll();
      release.countDown();
      await(overflowRecovery);
      assertEquals(List.of("first"), values);
    } finally {
      release.countDown();
    }
  }

  @Test
  void everySubscriptionPerformsOneInitialAuthoritativeRecoveryBeforeDelivery() throws Exception {
    CountDownLatch recovered = new CountDownLatch(2);
    CountDownLatch delivered = new CountDownLatch(2);
    AtomicInteger recoveries = new AtomicInteger();
    List<String> order = new CopyOnWriteArrayList<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(2))) {
      for (int index = 0; index < 2; index++) {
        inbox.subscribe(
            EVENTS,
            value -> {
              order.add(value);
              delivered.countDown();
            },
            () -> {
              recoveries.incrementAndGet();
              order.add("recover");
              recovered.countDown();
            });
      }
      await(recovered);
      assertEquals(2, recoveries.get(), "每个订阅恰好执行一次订阅期权威恢复");
      inbox.accept(notification("after"), 5);
      await(delivered);
      assertEquals(List.of("recover", "recover", "after", "after"), order);
    }
  }

  @Test
  void byteOverflowAndHandlerFailureRequestRecoveryAndFailedRecoveryIsVisible() throws Exception {
    CountDownLatch initialRecovery = new CountDownLatch(1);
    CountDownLatch failed = new CountDownLatch(1);
    AtomicInteger recoveries = new AtomicInteger();
    AtomicReference<NotificationSubscription> subscription = new AtomicReference<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(2))) {
      subscription.set(
          inbox.subscribe(
              EVENTS,
              value -> {
                throw new IllegalStateException("consumer failed");
              },
              () -> {
                // 订阅建立时的首次权威恢复成功；consumer 失败触发的下次恢复才失败并置为终态。
                if (recoveries.incrementAndGet() == 1) {
                  initialRecovery.countDown();
                  return;
                }
                failed.countDown();
                throw new IllegalStateException("recovery failed");
              }));
      await(initialRecovery);
      assertTrue(inbox.healthy());
      inbox.accept(notification("first"), 5);
      await(failed);
      awaitState(subscription.get(), NotificationSubscription.State.FAILED);
      assertFalse(inbox.healthy());
      inbox.accept(notification("ignored"), 1);
      assertEquals(NotificationSubscription.State.FAILED, subscription.get().state());
      subscription.get().close();
      assertEquals(NotificationSubscription.State.CLOSED, subscription.get().state());

      CountDownLatch initialSecondRecovery = new CountDownLatch(1);
      CountDownLatch byteRecovery = new CountDownLatch(1);
      AtomicInteger secondRecoveries = new AtomicInteger();
      inbox.subscribe(
          EVENTS,
          ignored -> fail("oversized mailbox entry"),
          () -> {
            if (secondRecoveries.incrementAndGet() == 1) {
              initialSecondRecovery.countDown();
            } else {
              byteRecovery.countDown();
            }
          });
      await(initialSecondRecovery);
      inbox.accept(notification("oversized"), 40001);
      await(byteRecovery);
    }
  }

  @Test
  void closingReleasesSubscriptionsAndRefusesNewOnes() {
    LocalInbox inbox = new LocalInbox(smallLimits(1));
    NotificationSubscription subscription = inbox.subscribe(EVENTS, ignored -> {}, () -> {});
    subscription.close();
    inbox.accept(notification("ignored"), 1);
    inbox.close();
    inbox.close();
    inbox.accept(notification("ignored"), 1);
    inbox.resyncAll();
    inbox.resync(EVENTS.name());
    assertThrows(
        IllegalStateException.class, () -> inbox.subscribe(EVENTS, ignored -> {}, () -> {}));
  }

  @Test
  void totalBytesIncludeAllSubscribersInFlightAndRecoveryNeedsNoNormalCapacity() throws Exception {
    CountDownLatch running = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch initialRecoveries = new CountDownLatch(2);
    CountDownLatch overflowRecoveries = new CountDownLatch(2);
    AtomicInteger recoveries = new AtomicInteger();
    try (LocalInbox inbox = new LocalInbox(smallLimits(2))) {
      for (int index = 0; index < 2; index++) {
        inbox.subscribe(
            EVENTS,
            value -> {
              running.countDown();
              hold(release);
            },
            () -> {
              if (recoveries.getAndIncrement() < 2) {
                initialRecoveries.countDown();
              } else {
                overflowRecoveries.countDown();
              }
            });
      }
      assertThrows(
          IllegalStateException.class, () -> inbox.subscribe(EVENTS, ignored -> {}, () -> {}));
      // 两个订阅各自的首次权威恢复先完成，之后的恢复才由字节溢出触发。
      await(initialRecoveries);
      inbox.accept(notification("occupy"), 20000);
      await(running);
      inbox.accept(notification("overflow"), 1);
      release.countDown();
      await(overflowRecoveries);
    } finally {
      release.countDown();
    }
  }

  @Test
  void closeInterruptingRecoveryKeepsClosedStateRatherThanFailed() throws Exception {
    CountDownLatch recovering = new CountDownLatch(1);
    CountDownLatch neverReleased = new CountDownLatch(1);
    LocalInbox inbox = new LocalInbox(smallLimits(1));
    NotificationSubscription subscription =
        inbox.subscribe(
            EVENTS,
            ignored -> {},
            () -> {
              recovering.countDown();
              hold(neverReleased);
            });
    inbox.resyncAll();
    await(recovering);
    inbox.close();
    assertEquals(NotificationSubscription.State.CLOSED, subscription.state());
  }

  @Test
  void consumerCanCloseItsInboxWithoutInterruptingItsOwnCleanup() throws Exception {
    LocalInbox inbox = new LocalInbox(smallLimits(2));
    CountDownLatch closed = new CountDownLatch(1);
    AtomicReference<NotificationSubscription> own = new AtomicReference<>();
    own.set(
        inbox.subscribe(
            EVENTS,
            value -> {
              inbox.close();
              assertFalse(Thread.currentThread().isInterrupted());
              closed.countDown();
            },
            () -> {}));
    inbox.subscribe(EVENTS, ignored -> {}, () -> {});
    inbox.accept(notification("close"), 5);
    await(closed);
    assertEquals(NotificationSubscription.State.CLOSED, own.get().state());
  }

  @Test
  void inboxCloseWithInterruptedThreadReportsExceptionAndRestoresInterrupt() throws Exception {
    LocalInbox inbox = new LocalInbox(smallLimits(2));
    CountDownLatch running = new CountDownLatch(1);
    CompletableFuture<Void> release = new CompletableFuture<>();
    AtomicReference<Thread> consumerThread = new AtomicReference<>();
    inbox.subscribe(
        EVENTS,
        value -> {
          consumerThread.set(Thread.currentThread());
          running.countDown();
          // 保持 worker 存活，避免 shutdown 的中断使 join 在等待前就结束。
          release.join();
        },
        () -> {});
    try {
      inbox.accept(notification("hold"), 5);
      await(running);
      Thread.currentThread().interrupt();
      IllegalStateException error = assertThrows(IllegalStateException.class, inbox::close);
      assertInstanceOf(InterruptedException.class, error.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
      release.complete(null);
      inbox.close();
      Thread worker = consumerThread.get();
      if (worker != null) {
        worker.join(1000);
        assertFalse(worker.isAlive());
      }
    }
  }

  @Test
  void multipleHungWorkersAllJoinAndReportSuppressedUnderDeadline() {
    LocalInbox inbox = new LocalInbox(smallLimits(2));
    CountDownLatch started1 = new CountDownLatch(1);
    CountDownLatch started2 = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    inbox.subscribe(
        EVENTS,
        value -> {
          started1.countDown();
          while (release.getCount() > 0) {
            try {
              Thread.sleep(100);
            } catch (InterruptedException ignored) {
              // Ignore interrupt to simulate uncooperative worker
            }
          }
        },
        () -> {});
    inbox.subscribe(
        EVENTS,
        value -> {
          started2.countDown();
          while (release.getCount() > 0) {
            try {
              Thread.sleep(100);
            } catch (InterruptedException ignored) {
              // Ignore interrupt to simulate uncooperative worker
            }
          }
        },
        () -> {});
    inbox.accept(notification("stuck"), 5);
    hold(started1);
    hold(started2);
    long start = System.nanoTime();
    try {
      IllegalStateException ex = assertThrows(IllegalStateException.class, inbox::close);
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(
          elapsedMillis >= 4800 && elapsedMillis < 7000,
          "expected around 5s, got " + elapsedMillis);
      assertEquals(
          1, ex.getSuppressed().length, "second hung worker must be reported as suppressed");
    } finally {
      release.countDown();
    }
  }

  private static void awaitState(
      NotificationSubscription subscription, NotificationSubscription.State state) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (subscription.state() != state && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(state, subscription.state());
  }
}
