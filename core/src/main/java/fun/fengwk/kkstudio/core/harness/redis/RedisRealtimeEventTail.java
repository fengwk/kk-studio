package fun.fengwk.kkstudio.core.harness.redis;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Best-effort Redis Stream tail for SSE. Reads the {@code event} field written by {@link
 * RedisRealtimeEventSink}; failures propagate so the SSE adapter can disconnect without touching
 * PostgreSQL correctness.
 */
public final class RedisRealtimeEventTail {

  private static final String EVENT_FIELD = "event";

  private final Supplier<StringRedisTemplate> stringRedisTemplateSupplier;
  private final String realtimeKeyPrefix;

  public RedisRealtimeEventTail(
      StringRedisTemplate stringRedisTemplate, HarnessRedisProperties properties) {
    this(() -> Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate"), properties);
  }

  RedisRealtimeEventTail(
      Supplier<StringRedisTemplate> stringRedisTemplateSupplier,
      HarnessRedisProperties properties) {
    this.stringRedisTemplateSupplier =
        Objects.requireNonNull(stringRedisTemplateSupplier, "stringRedisTemplateSupplier");
    this.realtimeKeyPrefix =
        Objects.requireNonNull(properties, "properties").requireRealtimeKeyPrefix();
  }

  /**
   * Reads records strictly after {@code afterId}. Use {@code "0-0"} (or blank/0) to start from the
   * beginning of the retained window; use a previous SSE id to resume. When {@code block} is
   * positive and no records are available, waits up to that duration.
   */
  public List<Record> readAfter(long threadId, String afterId, int count, Duration block) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    Objects.requireNonNull(block, "block");
    if (block.isNegative()) {
      throw new IllegalArgumentException("block must not be negative");
    }
    String key = realtimeKeyPrefix + Long.toString(threadId);
    String exclusiveStart = normalizeAfterId(afterId);
    StringRedisTemplate template = stringRedisTemplateSupplier.get();
    if (template == null) {
      throw new IllegalStateException("StringRedisTemplate is unavailable");
    }
    StreamReadOptions options = StreamReadOptions.empty().count(count);
    if (!block.isZero()) {
      options = options.block(block);
    }
    List<MapRecord<String, Object, Object>> records =
        template
            .opsForStream()
            .read(options, StreamOffset.create(key, ReadOffset.from(exclusiveStart)));
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    List<Record> result = new ArrayList<>(records.size());
    for (MapRecord<String, Object, Object> record : records) {
      String id = record.getId().getValue();
      Map<Object, Object> body = record.getValue();
      if (body == null) {
        continue;
      }
      Object payload = body.get(EVENT_FIELD);
      if (payload == null) {
        continue;
      }
      result.add(new Record(id, payload.toString()));
    }
    return List.copyOf(result);
  }

  public static String normalizeAfterId(String afterId) {
    if (afterId == null || afterId.isBlank() || "0".equals(afterId.trim())) {
      return "0-0";
    }
    String trimmed = afterId.trim();
    if (!trimmed.matches("\\d+-\\d+")) {
      throw new IllegalArgumentException(
          "afterEventId must be a Redis stream id (ms-seq) or 0: " + afterId);
    }
    return trimmed;
  }

  public record Record(String id, String payloadJson) {
    public Record {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(payloadJson, "payloadJson");
    }
  }
}
