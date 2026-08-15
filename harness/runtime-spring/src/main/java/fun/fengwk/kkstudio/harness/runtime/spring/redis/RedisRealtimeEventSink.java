package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.util.Objects;

/**
 * 按 Thread 分 channel 的 Redis Pub/Sub {@link RealtimeEventSink}。
 *
 * <p>每次 {@link #append(RealtimeEvent)} 在 channel {@code prefix + threadId} 上 PUBLISH 一条 canonical
 * JSON payload。Pub/Sub 是有损 live overlay：没有订阅者时消息直接消失，订阅者也不回放历史，遗漏由 durable snapshot 修复。Redis
 * 失败直接向上传播，由 Runtime 调用方隔离。
 */
public final class RedisRealtimeEventSink implements RealtimeEventSink {

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
    stringRedisTemplate.convertAndSend(config.channel(event.threadId()), eventCodec.encode(event));
  }
}
