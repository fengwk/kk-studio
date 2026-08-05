package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.util.Map;
import java.util.Objects;

/**
 * 按 Thread 分键的有界 Redis Stream {@link RealtimeEventSink}。
 *
 * <p>每次 {@link #append(RealtimeEvent)} 写入一个 field {@code event} 的 record，{@link
 * XAddOptions#maxlen(long)} 提供 exact trim（非 approximate），stream 长度不超过配置的 maxLength。Redis 失败直接
 * 向上传播，由 Runtime 调用方隔离。
 */
public final class RedisRealtimeEventSink implements RealtimeEventSink {

  private static final String EVENT_FIELD = "event";

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisRealtimeConfig config;
  private final RealtimeEventJsonCodec eventCodec;

  public RedisRealtimeEventSink(
      StringRedisTemplate stringRedisTemplate,
      RedisRealtimeConfig config,
      RealtimeEventJsonCodec eventCodec) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate");
    this.config = Objects.requireNonNull(config, "config");
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  @Override
  public void append(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    String payload = eventCodec.encode(event);
    XAddOptions options = XAddOptions.maxlen(config.maxLength());
    stringRedisTemplate
        .opsForStream()
        .add(config.key(event.threadId()), Map.of(EVENT_FIELD, payload), options);
  }
}
