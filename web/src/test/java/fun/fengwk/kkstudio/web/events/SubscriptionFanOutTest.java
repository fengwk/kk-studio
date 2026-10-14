package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

/**
 * SubscriptionFanOut：events 包内共享的组合式订阅容器。
 *
 * <p>覆盖三个 Hub 共同依赖的语义：UUID 定点与全局注册/注销、定点投递与 broadcast 的投递集合、注销幂等、订阅与注销竞争、 单个 RuntimeException 隔离而
 * Error 不被吞掉。
 */
class SubscriptionFanOutTest {

  private static final UUID KEY_A = new UUID(0L, 1L);
  private static final UUID KEY_B = new UUID(0L, 2L);

  @Test
  void publishDeliversOnlyToItsOwnKeyAndToGlobalSubscribers() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> keyedA = new ArrayList<>();
    List<String> keyedB = new ArrayList<>();
    List<String> global = new ArrayList<>();
    fanOut.subscribe(KEY_A, keyedA::add);
    fanOut.subscribe(KEY_B, keyedB::add);
    fanOut.subscribe(null, global::add);

    fanOut.publish(KEY_A, "a");

    assertEquals(List.of("a"), keyedA);
    assertTrue(keyedB.isEmpty(), "其他 key 的投递不得泄漏到本 key");
    assertEquals(List.of("a"), global, "全局订阅者接收每一次合法定点投递");
  }

  @Test
  void publishWithoutMatchingSubscriberIsANoOp() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> keyedA = new ArrayList<>();
    fanOut.subscribe(KEY_A, keyedA::add);

    fanOut.publish(KEY_B, "b");

    assertTrue(keyedA.isEmpty(), "没有对应 key 订阅者时定点投递不得投给其他 key");
  }

  @Test
  void broadcastDeliversResyncToEverySubscriberExactlyOnce() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> keyedA = new ArrayList<>();
    List<String> keyedB = new ArrayList<>();
    List<String> global = new ArrayList<>();
    fanOut.subscribe(KEY_A, keyedA::add);
    fanOut.subscribe(KEY_B, keyedB::add);
    fanOut.subscribe(null, global::add);

    fanOut.broadcast("resync");

    assertEquals(List.of("resync"), keyedA);
    assertEquals(List.of("resync"), keyedB);
    assertEquals(List.of("resync"), global, "全局订阅者在广播中只投递一次");
  }

  @Test
  void unsubscribeIsIdempotentAndStopsOnlyItsOwnDelivery() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> released = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    Runnable unsubscribe = fanOut.subscribe(KEY_A, released::add);
    fanOut.subscribe(KEY_A, kept::add);

    unsubscribe.run();
    unsubscribe.run(); // 幂等：重复注销不抛异常，也不影响同 key 的其他订阅者

    fanOut.broadcast("resync");

    assertTrue(released.isEmpty(), "注销后不得再收到投递");
    assertEquals(List.of("resync"), kept);
  }

  @Test
  void releaseDuringFanOutStopsLaterDeliveriesWithoutDisturbingOthers() {
    // 确定性注销竞争：消费者在回调内注销自身，CopyOnWriteArraySet 快照迭代不受影响，其他消费者照常收到全部事件。
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> selfReleased = new ArrayList<>();
    List<String> other = new ArrayList<>();
    AtomicReference<Runnable> handle = new AtomicReference<>();
    handle.set(
        fanOut.subscribe(
            KEY_A,
            event -> {
              selfReleased.add(event);
              handle.get().run();
            }));
    fanOut.subscribe(KEY_A, other::add);

    fanOut.publish(KEY_A, "a");
    fanOut.publish(KEY_A, "b");

    assertEquals(List.of("a"), selfReleased);
    assertEquals(List.of("a", "b"), other);
  }

  /** 旧 handle 重复关闭不能注销同一 callback 后来重新建立的订阅。 */
  @Test
  void repeatedOldCloseKeepsTheReplacementKeyedSubscription() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> received = new ArrayList<>();
    Consumer<String> callback = received::add;
    Runnable old = fanOut.subscribe(KEY_A, callback);
    old.run();
    fanOut.subscribe(KEY_A, callback);
    old.run();

    fanOut.publish(KEY_A, "new");
    assertEquals(List.of("new"), received);
  }

  @Test
  void repeatedOldCloseKeepsTheReplacementGlobalSubscription() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> received = new ArrayList<>();
    Consumer<String> callback = received::add;
    Runnable old = fanOut.subscribe(null, callback);
    old.run();
    fanOut.subscribe(null, callback);
    old.run();

    fanOut.broadcast("new");
    assertEquals(List.of("new"), received);
  }

  @Test
  void subscribeDuringFanOutBecomesVisibleOnNextDelivery() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> late = new ArrayList<>();
    AtomicInteger firstDeliveries = new AtomicInteger();
    fanOut.subscribe(
        KEY_A,
        event -> {
          if (firstDeliveries.incrementAndGet() == 1) {
            fanOut.subscribe(KEY_A, late::add);
          }
        });

    fanOut.broadcast("one");
    assertTrue(late.isEmpty(), "fan-out 迭代使用快照，迭代中注册的订阅者只从下一次投递开始收到事件");

    fanOut.broadcast("two");
    assertEquals(List.of("two"), late);
  }

  @Test
  void concurrentReleaseNeverDetachesALiveSubscriber() throws Exception {
    // 并发注销不得移除存活订阅者：本线程的注册只由本线程注销，每次 broadcast 都必须投递到它。
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    int iterations = 500;
    ExecutorService churn = Executors.newSingleThreadExecutor();
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> churnTask =
          churn.submit(
              () -> {
                await(start);
                for (int i = 0; i < iterations; i++) {
                  fanOut.subscribe(KEY_A, ignored -> {}).run();
                }
              });
      start.countDown();
      AtomicInteger delivered = new AtomicInteger();
      for (int i = 0; i < iterations; i++) {
        Runnable unsubscribe = fanOut.subscribe(KEY_A, ignored -> delivered.incrementAndGet());
        fanOut.broadcast("resync");
        unsubscribe.run();
      }
      churnTask.get(10, TimeUnit.SECONDS);
      assertEquals(iterations, delivered.get(), "并发注册/注销不得丢失存活订阅者的投递");
    } finally {
      churn.shutdownNow();
    }
  }

  @Test
  void runtimeExceptionIsIsolatedAndErrorIsNotSwallowed() {
    SubscriptionFanOut<String> fanOut = new SubscriptionFanOut<>();
    List<String> healthy = new ArrayList<>();
    fanOut.subscribe(
        KEY_A,
        event -> {
          throw new IllegalStateException("callback failure");
        });
    fanOut.subscribe(KEY_A, healthy::add);

    fanOut.publish(KEY_A, "a");
    assertEquals(List.of("a"), healthy, "单个消费者 RuntimeException 只隔离该消费者");

    fanOut.subscribe(
        KEY_B,
        event -> {
          throw new AssertionError("injected error");
        });
    assertThrows(AssertionError.class, () -> fanOut.publish(KEY_B, "b"), "Error 不得被吞掉");
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting start", interrupted);
    }
  }
}
