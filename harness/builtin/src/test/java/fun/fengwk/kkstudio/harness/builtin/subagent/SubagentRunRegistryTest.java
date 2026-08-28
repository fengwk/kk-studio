package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 进程内 task 并发 reservation 的计数与 session 归属语义。 */
class SubagentRunRegistryTest {

  private static final Duration ONE_MS = Duration.ofMillis(1);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static SubagentConfig config(int maxConcurrency, int maxTotalConcurrency) {
    return new SubagentConfig(2, maxConcurrency, maxTotalConcurrency, ONE_MS, 50);
  }

  /** 同一 parent 的直系子 Agent 数不得超过 maxConcurrency；关闭释放计数。 */
  @Test
  void enforcesMaxConcurrencyPerParent() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(1, 0);

    try (SubagentRunRegistry.Reservation first = registry.reserve(id(1), id(1), null, config)) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), null, config));
      assertEquals("subagent concurrency limit reached (1/1)", error.getMessage());
    }

    // 关闭后计数已释放：同一 parent 可再次占用唯一槽位，占满后依旧拒绝。
    try (SubagentRunRegistry.Reservation second = registry.reserve(id(1), id(1), null, config)) {
      assertThrows(
          IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), null, config));
    }
  }

  /** 不同 parent 共享同一 root 时，总 reservation 不得超过 maxTotalConcurrency。 */
  @Test
  void enforcesMaxTotalConcurrencyAcrossParents() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 2);

    try (SubagentRunRegistry.Reservation alpha = registry.reserve(id(1), id(9), null, config);
        SubagentRunRegistry.Reservation beta = registry.reserve(id(2), id(9), null, config)) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> registry.reserve(id(3), id(9), null, config));
      assertEquals("subagent tree concurrency limit reached (2/2)", error.getMessage());
    }

    // root 总量随 reservation 关闭而释放：两个新的不同 parent 可重新占满并再次拒绝第三个。
    try (SubagentRunRegistry.Reservation gamma = registry.reserve(id(3), id(9), null, config);
        SubagentRunRegistry.Reservation delta = registry.reserve(id(4), id(9), null, config)) {
      assertThrows(
          IllegalArgumentException.class, () -> registry.reserve(id(5), id(9), null, config));
    }
  }

  /** maxTotalConcurrency 为 0 时不设树级上限。 */
  @Test
  void allowsUnlimitedConcurrencyWhenMaxTotalIsZero() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);

    try (SubagentRunRegistry.Reservation r1 = registry.reserve(id(1), id(9), null, config);
        SubagentRunRegistry.Reservation r2 = registry.reserve(id(2), id(9), null, config);
        SubagentRunRegistry.Reservation r3 = registry.reserve(id(3), id(9), null, config)) {
      assertTrue(true);
    }
  }

  /** 同一 resume session 同时只能有一个 reservation 持有；释放后可恢复。 */
  @Test
  void rejectsResumeSessionWhileRunning() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);

    try (SubagentRunRegistry.Reservation first = registry.reserve(id(1), id(1), id(42), config)) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), id(42), config));
      assertEquals("subagent session \"" + id(42) + "\" is currently running", error.getMessage());
    }

    // session 释放后可再次恢复；恢复后再次被独占。
    try (SubagentRunRegistry.Reservation resumed = registry.reserve(id(1), id(1), id(42), config)) {
      assertThrows(
          IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), id(42), config));
    }
  }

  /** attach 的 thread 是唯一的运行中 session 事实：已被占用的 thread 不能再 attach。 */
  @Test
  void rejectsAttachingAnAlreadyRunningThread() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);

    try (SubagentRunRegistry.Reservation first = registry.reserve(id(1), id(1), null, config)) {
      first.attach(id(200));
      SubagentRunRegistry.Reservation second = registry.reserve(id(2), id(2), null, config);
      try {
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> second.attach(id(200)));
        assertEquals(
            "subagent session \"" + id(200) + "\" is currently running", error.getMessage());
      } finally {
        second.close();
      }
    }

    // thread 随 reservation 关闭而释放：新 reservation 可再次 attach 并重新独占它。
    try (SubagentRunRegistry.Reservation again = registry.reserve(id(1), id(1), null, config)) {
      again.attach(id(200));
      SubagentRunRegistry.Reservation other = registry.reserve(id(2), id(2), null, config);
      try {
        assertThrows(IllegalArgumentException.class, () -> other.attach(id(200)));
      } finally {
        other.close();
      }
    }
  }

  /** 同一 reservation 重复 attach 相同 thread 是幂等的；换 thread 则拒绝。 */
  @Test
  void rejectsReattachingWithDifferentThread() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);

    try (SubagentRunRegistry.Reservation reservation =
        registry.reserve(id(1), id(1), null, config)) {
      reservation.attach(id(200));
      reservation.attach(id(200));
      assertThrows(IllegalStateException.class, () -> reservation.attach(id(201)));
    }
  }

  /** close 幂等：重复 close 不抛错、不重复释放；关闭后 attach 是 no-op，计数与 session 均归还。 */
  @Test
  void closeIsIdempotent() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(1, 1);

    // 新子 Agent 的 reservation：attach 的 thread 随 close 释放。
    SubagentRunRegistry.Reservation child = registry.reserve(id(1), id(1), null, config);
    child.attach(id(200));
    child.close();
    child.close();
    // 关闭后 attach 不得重新登记 thread。
    child.attach(id(200));

    // resume session 的 reservation：session 占用随 close 释放。
    SubagentRunRegistry.Reservation resumed = registry.reserve(id(1), id(1), id(42), config);
    resumed.close();
    resumed.close();

    // 计数、resume session 与 thread 均已归还：可重新占满并再次拒绝。
    try (SubagentRunRegistry.Reservation again = registry.reserve(id(1), id(1), id(42), config)) {
      assertThrows(
          IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), null, config));
      assertThrows(
          IllegalArgumentException.class, () -> registry.reserve(id(1), id(1), id(42), config));
    }
    try (SubagentRunRegistry.Reservation withThread =
        registry.reserve(id(1), id(1), null, config)) {
      withThread.attach(id(200));
    }
  }

  /** 嵌套 task 的活动状态按 thread parent 链投影给全部祖先，并在 reservation 释放时立即消失。 */
  @Test
  void relaysDescendantStatusesToAncestors() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);
    SubagentRunRegistry.Reservation child = registry.reserve(id(1), id(1), null, config);
    SubagentRunRegistry.Reservation grandchild = registry.reserve(id(2), id(1), null, config);
    child.attach(id(2));
    grandchild.attach(id(3));
    var approval = new SubagentRunRegistry.RelayedApproval(id(51), "bash", "confirm");
    var status =
        new SubagentRunRegistry.RelayedStatus(
            id(3), "coder", "waiting_approval", 3, 1, 1, "waiting bash", List.of(approval));

    registry.publishStatus(status);

    assertEquals(List.of(status), registry.descendantStatuses(id(1)));
    assertEquals(List.of(status), registry.descendantStatuses(id(2)));
    assertEquals(List.of(), registry.descendantStatuses(id(3)));

    grandchild.close();
    assertEquals(List.of(), registry.descendantStatuses(id(1)));
    child.close();
  }

  /** descendant 订阅在 publish/attach/release 三个变更路径都收到锁外通知；释放订阅后不再通知。 */
  @Test
  void notifiesDescendantSubscribersOnPublishAttachAndRelease() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);
    List<String> events = new ArrayList<>();
    try (SubagentRunRegistry.ChangeSubscription subscription =
        registry.subscribeDescendants(id(1), () -> events.add("wake"))) {
      SubagentRunRegistry.Reservation child = registry.reserve(id(1), id(1), null, config);
      try {
        // attach 通知祖先。
        child.attach(id(2));
        assertEquals(1, events.size(), "attach must notify descendant subscribers");

        // publish 通知祖先。
        registry.publishStatus(
            new SubagentRunRegistry.RelayedStatus(
                id(2), "coder", "running_model", 2, 1, 0, "running_model", List.of()));
        assertEquals(2, events.size(), "publish must notify descendant subscribers");
      } finally {
        // release 通知祖先。
        child.close();
      }
      assertEquals(3, events.size(), "release must notify descendant subscribers");
    }
    // 订阅释放后不再收到通知。
    SubagentRunRegistry.Reservation again = registry.reserve(id(1), id(1), null, config);
    try {
      again.attach(id(4));
    } finally {
      again.close();
    }
    assertEquals(3, events.size(), "closed subscription must not be notified");
  }

  /** 发布 thread 自身的 status 只通知严格祖先：观察自己 child 的订阅者不会被自己的发布唤醒（无自循环）。 */
  @Test
  void publishingOwnStatusDoesNotWakeDescendantSelfSubscriber() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);
    SubagentRunRegistry.Reservation child = registry.reserve(id(1), id(1), null, config);
    child.attach(id(2));
    List<String> selfWakes = new ArrayList<>();
    List<String> ancestorWakes = new ArrayList<>();
    try (SubagentRunRegistry.ChangeSubscription self =
            registry.subscribeDescendants(id(2), () -> selfWakes.add("wake"));
        SubagentRunRegistry.ChangeSubscription ancestor =
            registry.subscribeDescendants(id(1), () -> ancestorWakes.add("wake"))) {
      registry.publishStatus(
          new SubagentRunRegistry.RelayedStatus(
              id(2), "coder", "running_model", 2, 1, 0, "running_model", List.of()));

      assertTrue(
          selfWakes.isEmpty(), "own status publish must not wake own descendant subscription");
      assertEquals(1, ancestorWakes.size(), "ancestor subscribers must be notified");
    }
    child.close();
  }

  /**
   * 回调在锁外执行且异常隔离：一个订阅者抛异常不阻断其他订阅者；回调被阻塞期间，另一线程仍能在 bounded timeout 内 读/写 registry（若回调在锁内执行，publish
   * 持锁阻塞回调，检查线程将超时失败）。
   */
  @Test
  void isolatesCallbackFailuresAndRunsOutsideRegistryLock() throws Exception {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 0);
    SubagentRunRegistry.Reservation child = registry.reserve(id(1), id(1), null, config);
    child.attach(id(2));
    List<String> healthyWakes = new CopyOnWriteArrayList<>();
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    ExecutorService publisher = daemonExecutor();
    ExecutorService checker = daemonExecutor();
    try (SubagentRunRegistry.ChangeSubscription failing =
            registry.subscribeDescendants(
                id(1),
                () -> {
                  throw new IllegalStateException("boom");
                });
        SubagentRunRegistry.ChangeSubscription healthy =
            registry.subscribeDescendants(
                id(1),
                () -> {
                  callbackEntered.countDown();
                  try {
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                  healthyWakes.add("wake");
                })) {
      // publish 在独立线程执行；failing 订阅者抛异常被隔离，healthy 订阅者随后进入并阻塞。
      Future<?> published =
          publisher.submit(
              () ->
                  registry.publishStatus(
                      new SubagentRunRegistry.RelayedStatus(
                          id(2), "coder", "running_model", 2, 1, 0, "running_model", List.of())));
      assertTrue(callbackEntered.await(5, TimeUnit.SECONDS), "healthy callback must run");
      // 回调被阻塞期间，另一线程读写 registry 必须立即完成：锁内回调会让 publish 持锁，这些调用会超时。
      Future<?> concurrentAccess =
          checker.submit(
              () -> {
                assertEquals(1, registry.descendantStatuses(id(1)).size());
                try (SubagentRunRegistry.Reservation other =
                    registry.reserve(id(3), id(1), null, config)) {
                  other.attach(id(4));
                }
              });
      concurrentAccess.get(3, TimeUnit.SECONDS);
      releaseCallback.countDown();
      published.get(3, TimeUnit.SECONDS);
    } finally {
      releaseCallback.countDown(); // 回归路径（回调持锁）时解除潜在死锁再关闭线程池。
      publisher.shutdownNow();
      checker.shutdownNow();
    }
    assertEquals(1, healthyWakes.size(), "failing subscriber must not block healthy subscribers");
    child.close();
  }

  private static ExecutorService daemonExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable);
          thread.setDaemon(true);
          return thread;
        });
  }
}
