package fun.fengwk.kkstudio.harness.runtime.spring.redis;

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
import java.time.Duration;
import java.time.Instant;

/** Redis 不可用时失败必须向上传播，不能被 adapter 吞掉。adapter 直连一个已释放的本地端口，无需 Docker。 */
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
    connectionFactory.setShareNativeConnection(true);
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
                        1L,
                        42L,
                        1,
                        1L,
                        new ProviderStreamEvent.TextDelta("hi"),
                        Instant.parse("2026-08-05T00:00:00Z"))));
    assertRedisRelated(thrown);
  }

  @Test
  void tailPropagatesRedisFailure() {
    RedisRealtimeEventTail tail =
        new RedisRealtimeEventTail(
            stringRedisTemplate, new RedisRealtimeConfig(), new RealtimeEventJsonCodec());
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> tail.readAfter(1L, "0-0", 10, Duration.ZERO));
    assertRedisRelated(thrown);
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
