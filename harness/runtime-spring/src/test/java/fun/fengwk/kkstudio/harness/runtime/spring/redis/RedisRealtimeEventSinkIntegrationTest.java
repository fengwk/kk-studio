package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** RedisRealtimeEventSink 集成测试：PUBLISH 到 Thread 专属 channel，payload 为 canonical JSON。 */
class RedisRealtimeEventSinkIntegrationTest {

  private static final RedisRealtimeConfig CONFIG = new RedisRealtimeConfig();
  private static final RealtimeEventJsonCodec CODEC = new RealtimeEventJsonCodec();
  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private StringRedisTemplate template;
  private RedisRealtimeEventSink sink;

  @BeforeEach
  void setUp() {
    template = RedisRealtimeFixture.template();
    RedisRealtimeFixture.reset(CONFIG.prefix());
    sink = new RedisRealtimeEventSink(template, CONFIG, CODEC);
  }

  @AfterEach
  void tearDown() {
    RedisRealtimeFixture.reset(CONFIG.prefix());
  }

  @Test
  void appendPublishesCanonicalJsonOnThreadChannel() throws Exception {
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            id(1L), id(42L), 1, 1L, new ProviderStreamEvent.TextDelta("hi"), NOW);
    assertEquals(
        CODEC.encode(event),
        publishAndRead(event.threadId(), event),
        "wire payload must be canonical");
  }

  @Test
  void toolPartialAppendPublishesOnThreadChannel() throws Exception {
    RealtimeEvent.ToolPartial event =
        new RealtimeEvent.ToolPartial(
            id(1L),
            id(99L),
            2,
            new ToolResult("call-1", List.of(new TextToolContent("partial")), false, "{}", false),
            NOW);
    assertEquals(CODEC.encode(event), publishAndRead(event.threadId(), event));
  }

  @Test
  void perThreadChannelsAreIsolated() throws Exception {
    RealtimeEvent.ModelDelta first =
        new RealtimeEvent.ModelDelta(
            id(1L), id(100L), 1, 1L, new ProviderStreamEvent.TextDelta("one"), NOW);
    RealtimeEvent.ModelDelta second =
        new RealtimeEvent.ModelDelta(
            id(2L), id(200L), 1, 1L, new ProviderStreamEvent.TextDelta("two"), NOW);

    assertEquals(CODEC.encode(first), publishAndRead(first.threadId(), first));
    assertEquals(CODEC.encode(second), publishAndRead(second.threadId(), second));
  }

  /** 在独立连接上 SUBSCRIBE 指定 channel 并保持连接直到收到消息；主线程反复 PUBLISH（幂等 payload，规避订阅落位竞态）。 */
  private String publishAndRead(UUID threadId, RealtimeEvent event) throws Exception {
    String channel = CONFIG.channel(threadId);
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread thread = new Thread(r, "realtime-sink-test-subscribe");
              thread.setDaemon(true);
              return thread;
            });
    try {
      executor.submit(
          () -> {
            RedisConnection connection = template.getConnectionFactory().getConnection();
            try {
              connection.subscribe(
                  (message, pattern) ->
                      received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                  channel.getBytes(StandardCharsets.UTF_8));
              // subscribe 是 fire-and-forget：保持连接直到收到消息，避免订阅随线程退出被撤销。
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
              while (received.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
              }
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            } finally {
              connection.close();
            }
          });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        sink.append(event);
        String payload = received.poll(100, TimeUnit.MILLISECONDS);
        if (payload != null) {
          return payload;
        }
      }
      fail("event not received on channel " + channel);
      return null;
    } finally {
      executor.shutdownNow();
    }
  }
}
