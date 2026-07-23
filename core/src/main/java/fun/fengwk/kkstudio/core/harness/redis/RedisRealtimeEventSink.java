package fun.fengwk.kkstudio.core.harness.redis;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 按 Thread 分键的有界 Redis Stream sink。
 *
 * <p>每次 {@link #append(RealtimeEvent)} 写入一个 field {@code event} 的 record，{@link
 * XAddOptions#maxlen(long)} 提供 exact trim（approximateTrimming=false）；stream 长度保证 {@code <=
 * realtimeMaxLength}。所有 Redis 异常向上抛，Runtime 调用方负责隔离。
 */
public class RedisRealtimeEventSink implements RealtimeEventSink {

  private static final String EVENT_FIELD = "event";

  private final Supplier<StringRedisTemplate> stringRedisTemplateSupplier;
  private final String realtimeKeyPrefix;
  private final long realtimeMaxLength;
  private final RealtimeEventJsonCodec eventCodec;

  public RedisRealtimeEventSink(
      StringRedisTemplate stringRedisTemplate,
      HarnessRedisProperties properties,
      RealtimeEventJsonCodec eventCodec) {
    this(
        () -> Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate"),
        properties,
        eventCodec);
  }

  RedisRealtimeEventSink(
      Supplier<StringRedisTemplate> stringRedisTemplateSupplier,
      HarnessRedisProperties properties,
      RealtimeEventJsonCodec eventCodec) {
    this.stringRedisTemplateSupplier =
        Objects.requireNonNull(stringRedisTemplateSupplier, "stringRedisTemplateSupplier");
    HarnessRedisProperties requiredProperties = Objects.requireNonNull(properties, "properties");
    this.realtimeKeyPrefix = requiredProperties.requireRealtimeKeyPrefix();
    this.realtimeMaxLength = requiredProperties.requireRealtimeMaxLength();
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    String key = realtimeKeyPrefix + Long.toString(event.threadId());
    String payload = eventCodec.encode(event);
    XAddOptions options = XAddOptions.maxlen(realtimeMaxLength);
    StringRedisTemplate stringRedisTemplate = stringRedisTemplateSupplier.get();
    if (stringRedisTemplate == null) {
      throw new IllegalStateException("StringRedisTemplate is unavailable");
    }
    stringRedisTemplate.opsForStream().add(key, Map.of(EVENT_FIELD, payload), options);
  }
}
