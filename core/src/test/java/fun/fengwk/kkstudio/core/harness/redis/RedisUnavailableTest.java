package fun.fengwk.kkstudio.core.harness.redis;

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

/**
 * Redis unavailable failures remain visible to the realtime projection adapter. The test constructs
 * the adapter directly; its connection points to a released local port.
 */
class RedisUnavailableTest {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate stringRedisTemplate;
  private HarnessRedisProperties properties;
  private RealtimeEventJsonCodec eventCodec;
  private int brokenPort;

  @BeforeEach
  void setup() throws Exception {
    // Reserve a local port, then close it so connections to it fail.
    try (ServerSocket socket = new ServerSocket(0)) {
      brokenPort = socket.getLocalPort();
    }
    connectionFactory =
        new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", brokenPort));
    connectionFactory.setShareNativeConnection(true);
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);

    properties = new HarnessRedisProperties();
    properties.setRealtimeKeyPrefix("kk-studio:harness:realtime:");

    eventCodec = new RealtimeEventJsonCodec();
  }

  @AfterEach
  void teardown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void realtimeEventSinkPropagatesRedisException() {
    RedisRealtimeEventSink sink =
        new RedisRealtimeEventSink(() -> stringRedisTemplate, properties, eventCodec, () -> 5_000L);

    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L,
            42L,
            1,
            1L,
            new ProviderStreamEvent.TextDelta("hi"),
            Instant.parse("2026-01-01T00:00:00Z"));
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> sink.append(event));
    assertRedisRelated(thrown);
  }

  @Test
  void missingRedisTemplateFailsOnlyWhenRealtimeAdapterIsCalled() {
    RedisRealtimeEventSink sink =
        new RedisRealtimeEventSink(() -> null, properties, eventCodec, () -> 5_000L);
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L,
            42L,
            1,
            1L,
            new ProviderStreamEvent.TextDelta("hi"),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertThrows(IllegalStateException.class, () -> sink.append(event));
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
