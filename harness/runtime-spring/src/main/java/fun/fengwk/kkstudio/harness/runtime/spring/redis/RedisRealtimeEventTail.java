package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Redis Stream {@link RealtimeEventTail} 实现：初始化返回稳定的具体 live-edge ID，后续提供 exclusive read-after
 * 语义；blocking 为可选的非负整毫秒时长，返回不可变列表。
 *
 * <p>Record 必须且只能包含 canonical {@code event} field；malformed/foreign record 与 Redis 失败直接传播，SSE
 * 适配层据此断开并重新读取 snapshot，不把无效 wire 发送给客户端。
 */
public final class RedisRealtimeEventTail implements RealtimeEventTail {

  private static final String EVENT_FIELD = "event";

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisRealtimeConfig config;
  private final RealtimeEventJsonCodec eventCodec;

  public RedisRealtimeEventTail(
      StringRedisTemplate stringRedisTemplate,
      RedisRealtimeConfig config,
      RealtimeEventJsonCodec eventCodec) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate");
    this.config = Objects.requireNonNull(config, "config");
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  @Override
  public String initialCursor(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    String key = config.key(threadId);
    List<MapRecord<String, Object, Object>> records =
        stringRedisTemplate
            .opsForStream()
            .reverseRange(key, Range.<String>unbounded(), Limit.limit().count(1));
    if (records == null || records.isEmpty()) {
      return "0-0";
    }
    return records.get(0).getId().getValue();
  }

  @Override
  public List<Record> readAfter(UUID threadId, String afterId, int count, Duration block) {
    Objects.requireNonNull(threadId, "threadId");
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    Objects.requireNonNull(block, "block");
    if (block.isNegative()) {
      throw new IllegalArgumentException("block must not be negative");
    }
    if (block.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("block must use whole milliseconds");
    }
    String exclusiveStart = RealtimeEventTail.normalizeAfterId(afterId);
    StreamReadOptions options = StreamReadOptions.empty().count(count);
    if (!block.isZero()) {
      options = options.block(block);
    }
    ReadOffset offset = ReadOffset.from(exclusiveStart);
    List<MapRecord<String, Object, Object>> records =
        stringRedisTemplate
            .opsForStream()
            .read(options, StreamOffset.create(config.key(threadId), offset));
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    List<Record> result = new ArrayList<>(records.size());
    for (MapRecord<String, Object, Object> record : records) {
      Map<Object, Object> body = record.getValue();
      Object payload = body.get(EVENT_FIELD);
      if (body.size() != 1 || !(payload instanceof String payloadJson)) {
        throw new IllegalArgumentException(
            "realtime stream record must contain exactly one text event field");
      }
      RealtimeEvent event = eventCodec.decode(payloadJson);
      if (!eventCodec.encode(event).equals(payloadJson)) {
        throw new IllegalArgumentException("realtime stream event must use canonical JSON");
      }
      result.add(new Record(record.getId().getValue(), payloadJson));
    }
    return List.copyOf(result);
  }
}
