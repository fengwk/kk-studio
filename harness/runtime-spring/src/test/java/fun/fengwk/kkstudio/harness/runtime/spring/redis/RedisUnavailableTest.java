package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.net.ServerSocket;
import java.time.Instant;

/** Redis 不可用时：sink 失败必须向上传播；source 订阅不抛错、可干净关闭。adapter 直连一个已释放的本地端口，无需 Docker。 */
class RedisUnavailableTest {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate stringRedisTemplate;

  @BeforeEach
  void setUp() throws Exception {
    int brokenPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      brokenPort = socket.getLocalPort();
    }
    connectionFactory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", brokenPort));
    connectionFactory.setShareNativeConnection(false);
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
  }

  @AfterEach
  void tearDown() {
    connectionFactory.destroy();
  }

  @Test
  void sinkPropagatesRedisFailure() {
    RedisRealtimeEventSink sink =
        new RedisRealtimeEventSink(
            stringRedisTemplate, new RedisRealtimeConfig(), new RealtimeEventJsonCodec());
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                sink.append(
                    new RealtimeEvent.ModelDelta(
                        id(1L),
                        id(42L),
                        1,
                        1L,
                        new ProviderStreamEvent.TextDelta("hi"),
                        Instant.parse("2026-08-05T00:00:00Z"))));
    assertRedisRelated(thrown);
  }

  @Test
  void sourceSubscriptionIsAsyncAndClosesCleanlyWithoutRedis() throws Exception {
    RedisRealtimeEventSource source =
        new RedisRealtimeEventSource(
            connectionFactory, new RedisRealtimeConfig(), new RealtimeEventJsonCodec());
    try {
      // 订阅失败在 reactor 上异步发生：调用本身不抛错，也不产生 resync（从未连接成功）。
      AutoCloseable subscription = source.subscribe(id(1L), event -> {}, () -> {});
      subscription.close();
    } finally {
      source.close();
    }
    assertThrows(
        IllegalStateException.class, () -> source.subscribe(id(1L), event -> {}, () -> {}));
  }

  private static void assertRedisRelated(Throwable thrown) {
    Throwable cursor = thrown;
    boolean found = false;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null
          && (message.contains("Connection refused")
              || message.contains("Unable to connect")
              || message.contains("connection")
              || message.contains("Redis"))) {
        found = true;
        break;
      }
      String name = cursor.getClass().getName();
      if (name.contains("redis")
          || name.contains("RedisConnectionFailureException")
          || name.contains("RedisException")) {
        found = true;
        break;
      }
      cursor = cursor.getCause();
    }
    assertTrue(found, "expected a Redis-related exception, got " + thrown);
  }
}
