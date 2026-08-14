package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** RedisRealtimeEventSource 集成测试：live 接收、per-thread 隔离、refcount、重新订阅与 resync。 */
class RedisRealtimeEventSourceIntegrationTest {

  private static final RedisRealtimeConfig CONFIG = new RedisRealtimeConfig();
  private static final RealtimeEventJsonCodec CODEC = new RealtimeEventJsonCodec();
  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private StringRedisTemplate template;
  private RedisRealtimeEventSink sink;
  private RedisRealtimeEventSource source;

  @BeforeEach
  void setUp() {
    template = RedisRealtimeFixture.template();
    RedisRealtimeFixture.reset(CONFIG.prefix());
    sink = new RedisRealtimeEventSink(template, CONFIG, CODEC);
    source = new RedisRealtimeEventSource(RedisRealtimeFixture.connectionFactory(), CONFIG, CODEC);
  }

  @AfterEach
  void tearDown() {
    source.close();
    RedisRealtimeFixture.reset(CONFIG.prefix());
  }

  @Test
  void receivesSinkAppendsAsDecodedEvents() throws Exception {
    RealtimeEvent.ModelDelta event = modelDelta(id(1L), "one");
    BlockingQueue<RealtimeEvent> events = subscribe(id(1L));
    RealtimeEvent received = publishUntilReceived(id(1L), event, events);
    assertNotNull(received);
    assertEquals(CODEC.encode(event), CODEC.encode(received));
  }

  @Test
  void perThreadChannelsAreIsolated() throws Exception {
    BlockingQueue<RealtimeEvent> first = subscribe(id(11L));
    BlockingQueue<RealtimeEvent> second = subscribe(id(12L));
    RealtimeEvent one = modelDelta(id(11L), "one");
    RealtimeEvent two = modelDelta(id(12L), "two");

    publishUntilReceived(id(11L), one, first);
    assertNull(second.poll(300, TimeUnit.MILLISECONDS), "other thread must not receive events");
    publishUntilReceived(id(12L), two, second);
  }

  @Test
  void multipleLocalSubscribersShareOneChannel() throws Exception {
    BlockingQueue<RealtimeEvent> first = subscribe(id(21L));
    BlockingQueue<RealtimeEvent> second = subscribe(id(21L));
    RealtimeEvent event = modelDelta(id(21L), "shared");

    publishUntilReceived(id(21L), event, first);
    assertNotNull(
        second.poll(5, TimeUnit.SECONDS), "every local subscriber of the channel must receive");
  }

  @Test
  void closedSubscriptionStopsDeliveryAndResubscribeRestoresIt() throws Exception {
    UUID threadId = id(31L);
    BlockingQueue<RealtimeEvent> events = new LinkedBlockingQueue<>();
    try (AutoCloseable ignored = source.subscribe(threadId, events::add, () -> {})) {
      publishUntilReceived(threadId, modelDelta(threadId, "before"), events);
    }
    Thread.sleep(300); // 等待容器异步退订落位
    sink.append(modelDelta(threadId, "dropped"));
    assertNull(events.poll(500, TimeUnit.MILLISECONDS), "closed subscription must not deliver");

    try (AutoCloseable ignored = source.subscribe(threadId, events::add, () -> {})) {
      publishUntilReceived(threadId, modelDelta(threadId, "after"), events);
    }
  }

  @Test
  void malformedOrNonCanonicalMessagesTriggerResync() throws Exception {
    UUID threadId = id(41L);
    AtomicInteger resyncs = new AtomicInteger();
    BlockingQueue<RealtimeEvent> events = new LinkedBlockingQueue<>();
    try (AutoCloseable ignored =
        source.subscribe(threadId, events::add, resyncs::incrementAndGet)) {
      awaitResync(
          CONFIG.channel(threadId),
          resyncs,
          1,
          () -> template.convertAndSend(CONFIG.channel(threadId), "{not-json"));
      assertEquals(1, resyncs.get(), "malformed payload must trigger resync");
      assertEquals(0, events.size(), "malformed payload must not be delivered");

      awaitResync(
          CONFIG.channel(threadId),
          resyncs,
          2,
          () ->
              template.convertAndSend(
                  CONFIG.channel(threadId), " " + CODEC.encode(modelDelta(threadId, "x"))));
      assertEquals(2, resyncs.get(), "non-canonical payload must trigger resync");
      assertEquals(0, events.size(), "non-canonical payload must not be delivered");
    }
  }

  private BlockingQueue<RealtimeEvent> subscribe(UUID threadId) {
    BlockingQueue<RealtimeEvent> events = new LinkedBlockingQueue<>();
    source.subscribe(threadId, events::add, () -> {});
    return events;
  }

  /** 反复 PUBLISH 相同事件直到收到（幂等 payload，规避订阅落位竞态），返回收到的原始 payload。 */
  private RealtimeEvent publishUntilReceived(
      UUID threadId, RealtimeEvent event, BlockingQueue<RealtimeEvent> events) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      sink.append(event);
      RealtimeEvent received = events.poll(100, TimeUnit.MILLISECONDS);
      if (received != null) {
        return received;
      }
    }
    throw new AssertionError("event not received on channel " + CONFIG.channel(threadId));
  }

  /**
   * PUBLISH 坏消息直到 resync 计数精确达到 {@code expected}。Pub/Sub 不排队：未落位的消息直接丢弃， 因此每次循环先 publish 再等待 500ms
   * 落位；期间计数超调说明同一条消息被重复处理，直接报错。
   */
  private static void awaitResync(
      String channel, AtomicInteger resyncs, int expected, Runnable publish) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      publish.run();
      long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
      while (System.nanoTime() < settle) {
        int current = resyncs.get();
        if (current > expected) {
          throw new AssertionError(
              "resync overshot on channel " + channel + " (count=" + current + ")");
        }
        if (current == expected) {
          return;
        }
        Thread.sleep(20);
      }
    }
    throw new AssertionError(
        "resync not triggered on channel " + channel + " (count=" + resyncs.get() + ")");
  }

  private static RealtimeEvent.ModelDelta modelDelta(UUID threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        threadId, id(42L), 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }
}
