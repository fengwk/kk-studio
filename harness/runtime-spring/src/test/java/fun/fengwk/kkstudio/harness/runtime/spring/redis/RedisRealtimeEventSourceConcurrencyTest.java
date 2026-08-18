package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.ReactiveGeoCommands;
import org.springframework.data.redis.connection.ReactiveHashCommands;
import org.springframework.data.redis.connection.ReactiveHyperLogLogCommands;
import org.springframework.data.redis.connection.ReactiveKeyCommands;
import org.springframework.data.redis.connection.ReactiveListCommands;
import org.springframework.data.redis.connection.ReactiveNumberCommands;
import org.springframework.data.redis.connection.ReactivePubSubCommands;
import org.springframework.data.redis.connection.ReactiveRedisClusterConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveScriptingCommands;
import org.springframework.data.redis.connection.ReactiveServerCommands;
import org.springframework.data.redis.connection.ReactiveSetCommands;
import org.springframework.data.redis.connection.ReactiveStreamCommands;
import org.springframework.data.redis.connection.ReactiveStringCommands;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.ReactiveZSetCommands;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RedisRealtimeEventSource 的确定性并发回归（无需真实 Redis）：用受控 container/flux 钉住「最后释放关闭 listen 期间并发
 * subscribe」窗口，验证 listener 不会在已移除的 detached channel 上重建；并覆盖 close 后拒绝订阅与 消费者回调异常隔离。
 */
class RedisRealtimeEventSourceConcurrencyTest {

  private static final RedisRealtimeConfig CONFIG = new RedisRealtimeConfig();
  private static final RealtimeEventJsonCodec CODEC = new RealtimeEventJsonCodec();
  private static final Duration RETRY_DELAY = Duration.ofMillis(1);
  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  @Test
  void lastReleaseConcurrentWithSubscribeNeverRebuildsListenerOnDetachedChannel() throws Exception {
    // 复现 Review 竞态：subscribe 已取得旧 channel、最后 release 删除 map 后 subscribe 才继续。
    // release 在 channel 锁内先 retire + identity 移除 map entry、再取消 listen（用 latch 卡住取消）；
    // 阻塞窗口内并发 subscribe 只能取得全新 channel 建立新 listener。修复前 release 在 disposeListen 之后
    // 才 remove，阻塞窗口内 subscribe 会拿到旧 channel，在 detached channel 上重建 listener——后续订阅
    // 会再建第三个 channel/listener（可观察：receiveLater 调用次数）。
    UUID threadId = new UUID(0L, 51L);
    FakeContainer container = new FakeContainer();
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(container, CONFIG, CODEC, RETRY_DELAY);
    try {
      AutoCloseable first = source.subscribe(threadId, event -> {}, () -> {});
      assertEquals(1, container.receiveLaterCalls.get());
      ControllableFlux firstFlux = container.fluxes.get(0);
      firstFlux.armDisposeBlock(); // 只钉住第一个 listener 的取消窗口
      CountDownLatch subscribeTaskStarted = new CountDownLatch(1);
      CountDownLatch subscribeProceed = new CountDownLatch(1);
      CountDownLatch subscribeTaskDone = new CountDownLatch(1);

      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<?> release =
            executor.submit(
                () -> {
                  try {
                    first.close(); // 最后释放：retire + 移除 map entry，随后卡在取消 listen
                  } catch (Exception error) {
                    throw new RuntimeException(error);
                  }
                  return null;
                });
        assertTrue(
            firstFlux.awaitDisposeStarted(5, TimeUnit.SECONDS), "release must reach listen cancel");

        Future<AutoCloseable> subscribe =
            executor.submit(
                () -> {
                  subscribeTaskStarted.countDown();
                  subscribeProceed.await();
                  try {
                    return source.subscribe(threadId, event -> {}, () -> {});
                  } finally {
                    subscribeTaskDone.countDown();
                  }
                });
        assertTrue(subscribeTaskStarted.await(5, TimeUnit.SECONDS), "subscribe task must start");
        subscribeProceed.countDown(); // subscribe 开始执行 channel 查找
        // 观测窗口（不断言）：修复前 subscribe 必然拿到退役中的旧 channel 并阻塞满 500ms；
        // 修复后 remove 先于 dispose，subscribe 直接取得全新 channel、立即返回。两版最终都放行，
        // 判别留给下面的 third 订阅计数（修复前 third 会在 detached 之外再建一个 listener）。
        subscribeTaskDone.await(500, TimeUnit.MILLISECONDS);
        firstFlux.allowDispose(); // 放行最后释放
        release.get(5, TimeUnit.SECONDS);
        AutoCloseable second = subscribe.get(5, TimeUnit.SECONDS);
        // 同一 channel 名只建立了两个 listener（首次订阅 + 并发订阅）。
        assertEquals(2, container.receiveLaterCalls.get());

        // 再订阅必须复用并发订阅建立的 channel：修复前它建立在 detached channel 上，这里会再建第三个。
        try (AutoCloseable third = source.subscribe(threadId, event -> {}, () -> {})) {
          assertEquals(2, container.receiveLaterCalls.get(), "must not create a third listener");
        }
        second.close();
      } finally {
        executor.shutdownNow();
      }
    } finally {
      source.close();
    }
  }

  @Test
  void concurrentSubscribeAndCloseLeavesNoLiveListen() throws Exception {
    // 复现 close 与 publish 竞态：closed 检查若只在 compute 内、close 只观察 isEmpty，subscribe 可在
    // close 排空后插入存活 channel 并建立 listen。围栏把「设立 closed」与「发布 channel」串在同一把锁上后，
    // 并发 subscribe 要么在 close 前完成（随后被 close 取消 listen），要么看到 closed 被拒绝。
    FakeContainer container = new FakeContainer();
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(container, CONFIG, CODEC, RETRY_DELAY);
    int workers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(workers + 1);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> tasks = new ArrayList<>();
    List<AutoCloseable> accepted = Collections.synchronizedList(new ArrayList<>());
    try {
      for (int i = 0; i < workers; i++) {
        UUID threadId = new UUID(0L, 60L + i);
        tasks.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    accepted.add(source.subscribe(threadId, event -> {}, () -> {}));
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
                source.close();
                return null;
              }));
      start.countDown();
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }

      assertEquals(
          container.receiveLaterCalls.get(),
          container.disposeCalls.get(),
          "every established listen must be disposed; none may survive source close");
      assertThrows(
          IllegalStateException.class,
          () -> source.subscribe(new UUID(0L, 99L), event -> {}, () -> {}));
      assertEquals(
          container.receiveLaterCalls.get(),
          container.disposeCalls.get(),
          "post-close subscribe must not build another listener");
      for (AutoCloseable handle : accepted) {
        handle.close();
      }
      assertEquals(
          container.receiveLaterCalls.get(),
          container.disposeCalls.get(),
          "accepted subscriptions must already be retired; release is a no-op");
    } finally {
      executor.shutdownNow();
      source.close();
    }
  }

  @Test
  void closeDoesNotHoldLifecycleFenceWhileDisposingListen() throws Exception {
    // 围栏只设立 closed：排空时 dispose 若回等待，不得再持有围栏，否则并发 subscribe 会死锁。
    UUID threadId = new UUID(0L, 70L);
    FakeContainer container = new FakeContainer();
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(container, CONFIG, CODEC, RETRY_DELAY);
    AutoCloseable first = source.subscribe(threadId, event -> {}, () -> {});
    assertEquals(1, container.receiveLaterCalls.get());
    container.fluxes.get(0).armDisposeBlock();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> closing = executor.submit(source::close);
      assertTrue(
          container.fluxes.get(0).awaitDisposeStarted(5, TimeUnit.SECONDS),
          "close must reach listen dispose");

      Future<?> subscribe =
          executor.submit(
              () -> {
                source.subscribe(new UUID(0L, 71L), event -> {}, () -> {});
                return null;
              });
      ExecutionException error =
          assertThrows(
              ExecutionException.class,
              () -> subscribe.get(5, TimeUnit.SECONDS),
              "subscribe during drain must not wait on the lifecycle fence");
      assertTrue(
          error.getCause() instanceof IllegalStateException,
          "post-boundary subscribe must be rejected while drain is still disposing");

      container.fluxes.get(0).allowDispose();
      closing.get(5, TimeUnit.SECONDS);
      first.close();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void subscribeAfterCloseIsRejectedAndCloseIsIdempotent() throws Exception {
    UUID threadId = new UUID(0L, 52L);
    FakeContainer container = new FakeContainer();
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(container, CONFIG, CODEC, RETRY_DELAY);
    try (AutoCloseable ignored = source.subscribe(threadId, event -> {}, () -> {})) {
      assertEquals(1, container.receiveLaterCalls.get());
    }
    source.close();

    // 关闭后不得再成功订阅（不得在 destroy 后重建 listener）。
    assertThrows(
        IllegalStateException.class, () -> source.subscribe(threadId, event -> {}, () -> {}));
    assertEquals(1, container.receiveLaterCalls.get(), "no listener may be built after close");
    source.close(); // 幂等
  }

  @Test
  void failingSubscriberCallbackDoesNotBlockOtherSubscribersOrTriggerResync() throws Exception {
    UUID threadId = new UUID(0L, 53L);
    FakeContainer container = new FakeContainer();
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(container, CONFIG, CODEC, RETRY_DELAY);
    try {
      AtomicInteger resyncs = new AtomicInteger();
      List<RealtimeEvent> received = new ArrayList<>();
      source.subscribe(
          threadId,
          event -> {
            throw new IllegalStateException("boom");
          },
          resyncs::incrementAndGet);
      source.subscribe(threadId, received::add, resyncs::incrementAndGet);

      RealtimeEvent event = modelDelta(threadId, "hi");
      container.fluxes.get(0).emit(new FakeMessage(CONFIG.channel(threadId), CODEC.encode(event)));

      // 第一个消费者抛异常只被隔离：第二个消费者照常收到，且不触发全局 resync。
      assertEquals(1, received.size(), "healthy subscriber must still receive the event");
      assertEquals(0, resyncs.get(), "subscriber failure must not trigger resync");
    } finally {
      source.close();
    }
  }

  private static RealtimeEvent.ModelDelta modelDelta(UUID threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        threadId, new UUID(0L, 42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }

  /** 受控容器：记录 receiveLater 调用次数，返回可发射消息、可阻塞取消的受控 Flux。 */
  private static final class FakeContainer extends ReactiveRedisMessageListenerContainer {

    private final AtomicInteger receiveLaterCalls = new AtomicInteger();
    private final AtomicInteger disposeCalls = new AtomicInteger();
    private final List<ControllableFlux> fluxes = new ArrayList<>();

    private FakeContainer() {
      super(new FakeConnectionFactory());
    }

    @Override
    public Mono<Flux<ReactiveSubscription.Message<String, String>>> receiveLater(
        ChannelTopic... topics) {
      receiveLaterCalls.incrementAndGet();
      ControllableFlux flux = new ControllableFlux(disposeCalls);
      fluxes.add(flux);
      return Mono.just(flux);
    }
  }

  /** 自定义 Flux：订阅时挂起（不发消息），测试可 emit 消息；取消默认放行，可 arm 后阻塞在 Subscription.cancel。 */
  private static final class ControllableFlux
      extends Flux<ReactiveSubscription.Message<String, String>> {

    private final AtomicInteger disposeCalls;
    private final CountDownLatch disposeStarted = new CountDownLatch(1);
    private final CountDownLatch disposeDone = new CountDownLatch(1);
    private volatile boolean blockDispose;
    private volatile CoreSubscriber<? super ReactiveSubscription.Message<String, String>>
        subscriber;

    private ControllableFlux(AtomicInteger disposeCalls) {
      this.disposeCalls = disposeCalls;
    }

    @Override
    public void subscribe(
        CoreSubscriber<? super ReactiveSubscription.Message<String, String>> actual) {
      subscriber = actual;
      actual.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {}

            @Override
            public void cancel() {
              disposeCalls.incrementAndGet();
              disposeStarted.countDown();
              if (blockDispose) {
                try {
                  disposeDone.await();
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                }
              }
            }
          });
    }

    private void emit(ReactiveSubscription.Message<String, String> message) {
      subscriber.onNext(message);
    }

    private void armDisposeBlock() {
      blockDispose = true;
    }

    private boolean awaitDisposeStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return disposeStarted.await(timeout, unit);
    }

    private void allowDispose() {
      disposeDone.countDown();
    }
  }

  private record FakeMessage(String channel, String message)
      implements ReactiveSubscription.Message<String, String> {

    @Override
    public String getChannel() {
      return channel;
    }

    @Override
    public String getMessage() {
      return message;
    }
  }

  private static final class FakeConnectionFactory implements ReactiveRedisConnectionFactory {

    @Override
    public ReactiveRedisConnection getReactiveConnection() {
      return new FakeReactiveRedisConnection();
    }

    @Override
    public ReactiveRedisClusterConnection getReactiveClusterConnection() {
      throw new UnsupportedOperationException("no real connection in unit test");
    }

    @Override
    public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
      return null;
    }
  }

  /** 最小实现：容器构造只保存连接，destroy 只调用 closeLater；receiveLater 已被 FakeContainer 接管。 */
  private static final class FakeReactiveRedisConnection implements ReactiveRedisConnection {

    @Override
    public Mono<Void> closeLater() {
      return Mono.empty();
    }

    @Override
    public ReactiveKeyCommands keyCommands() {
      return null;
    }

    @Override
    public ReactiveStringCommands stringCommands() {
      return null;
    }

    @Override
    public ReactiveNumberCommands numberCommands() {
      return null;
    }

    @Override
    public ReactiveListCommands listCommands() {
      return null;
    }

    @Override
    public ReactiveSetCommands setCommands() {
      return null;
    }

    @Override
    public ReactiveZSetCommands zSetCommands() {
      return null;
    }

    @Override
    public ReactiveHashCommands hashCommands() {
      return null;
    }

    @Override
    public ReactiveGeoCommands geoCommands() {
      return null;
    }

    @Override
    public ReactiveHyperLogLogCommands hyperLogLogCommands() {
      return null;
    }

    @Override
    public ReactivePubSubCommands pubSubCommands() {
      return null;
    }

    @Override
    public ReactiveScriptingCommands scriptingCommands() {
      return null;
    }

    @Override
    public ReactiveServerCommands serverCommands() {
      return null;
    }

    @Override
    public ReactiveStreamCommands streamCommands() {
      return null;
    }

    @Override
    public Mono<String> ping() {
      return null;
    }
  }
}
