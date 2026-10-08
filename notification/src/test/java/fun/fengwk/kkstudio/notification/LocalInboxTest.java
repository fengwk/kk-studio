package fun.fengwk.kkstudio.notification;

import static fun.fengwk.kkstudio.notification.NotificationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch recovered = new CountDownLatch(1);
    List<String> values = new CopyOnWriteArrayList<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(1))) {
      inbox.subscribe(
          EVENTS,
          value -> {
            values.add(value);
            started.countDown();
            hold(release);
          },
          recovered::countDown);
      inbox.accept(notification("first"), 5);
      await(started);
      inbox.accept(notification("queued"), 6);
      inbox.accept(notification("overflow"), 8);
      inbox.resync(EVENTS.name());
      inbox.resyncAll();
      release.countDown();
      await(recovered);
      assertEquals(List.of("first"), values);
    } finally {
      release.countDown();
    }
  }

  @Test
  void byteOverflowAndHandlerFailureRequestRecoveryAndFailedRecoveryIsVisible() throws Exception {
    CountDownLatch failed = new CountDownLatch(1);
    AtomicReference<NotificationSubscription> subscription = new AtomicReference<>();
    try (LocalInbox inbox = new LocalInbox(smallLimits(2))) {
      subscription.set(
          inbox.subscribe(
              EVENTS,
              value -> {
                throw new IllegalStateException("consumer failed");
              },
              () -> {
                failed.countDown();
                throw new IllegalStateException("recovery failed");
              }));
      inbox.accept(notification("first"), 5);
      await(failed);
      awaitState(subscription.get(), NotificationSubscription.State.FAILED);
      inbox.accept(notification("ignored"), 1);
      assertEquals(NotificationSubscription.State.FAILED, subscription.get().state());
      subscription.get().close();
      assertEquals(NotificationSubscription.State.CLOSED, subscription.get().state());

      CountDownLatch byteRecovery = new CountDownLatch(1);
      inbox.subscribe(EVENTS, ignored -> fail("oversized mailbox entry"), byteRecovery::countDown);
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
    CountDownLatch recovered = new CountDownLatch(2);
    try (LocalInbox inbox = new LocalInbox(smallLimits(2))) {
      for (int index = 0; index < 2; index++) {
        inbox.subscribe(
            EVENTS,
            value -> {
              running.countDown();
              hold(release);
            },
            recovered::countDown);
      }
      assertThrows(
          IllegalStateException.class, () -> inbox.subscribe(EVENTS, ignored -> {}, () -> {}));
      inbox.accept(notification("occupy"), 20000);
      await(running);
      inbox.accept(notification("overflow"), 1);
      release.countDown();
      await(recovered);
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

  private static void awaitState(
      NotificationSubscription subscription, NotificationSubscription.State state) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (subscription.state() != state && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(state, subscription.state());
  }
}
