package fun.fengwk.kkstudio.core.harness.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.net.ServerSocket;
import java.time.Instant;

/**
 * Redis 不可用时两个 adapter 都向上抛 RuntimeException，不吞。直接构造 adapter，避免启动第二个 Spring 上下文；连接指向一个预留后立即释放的本地端口。
 */
class RedisUnavailableTest {

  private LettuceConnectionFactory connectionFactory;
  private StringRedisTemplate stringRedisTemplate;
  private HarnessRedisProperties properties;
  private ExecutionTargetJsonCodec targetCodec;
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
    properties.setSignalChannel("kk-studio:harness:signal");
    properties.setRealtimeKeyPrefix("kk-studio:harness:realtime:");

    targetCodec = new ExecutionTargetJsonCodec();
    eventCodec = new RealtimeEventJsonCodec();
  }

  @AfterEach
  void teardown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  void activationNotifierPropagatesRedisException() {
    RedisActivationNotifier notifier =
        new RedisActivationNotifier(stringRedisTemplate, properties, targetCodec);

    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 42L);
    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> notifier.notifyAfterCommit(target));
    assertRedisRelated(thrown);
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
            new ProviderStreamEvent.TextDelta("hi"),
            Instant.parse("2026-01-01T00:00:00Z"));
    RuntimeException thrown = assertThrows(RuntimeException.class, () -> sink.append(event));
    assertRedisRelated(thrown);
  }

  @Test
  void missingRedisTemplateFailsOnlyWhenAdapterIsCalled() {
    RedisActivationNotifier notifier =
        new RedisActivationNotifier(() -> null, properties, targetCodec);
    RedisRealtimeEventSink sink =
        new RedisRealtimeEventSink(() -> null, properties, eventCodec, () -> 5_000L);
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);
    RealtimeEvent.ModelDelta event =
        new RealtimeEvent.ModelDelta(
            1L,
            42L,
            1,
            new ProviderStreamEvent.TextDelta("hi"),
            Instant.parse("2026-01-01T00:00:00Z"));

    assertThrows(IllegalStateException.class, () -> notifier.notifyAfterCommit(target));
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
