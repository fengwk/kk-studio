package fun.fengwk.kkstudio.core.studio.realtime;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.studio.canvas.CanvasPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis Stream {@link CanvasPatchStore}：每个 Canvas 一个 {@code {prefix}{canvasId}:changes} 键， 单 field
 * {@code patch} 记录，{@code XAddOptions.maxlen} exact trim 保证有界。读取使用 XRANGE 全量回放，不建立 consumer
 * group；损坏记录与 Redis 失败向上传播，由 changes 服务回退 snapshot。
 */
public final class RedisCanvasPatchStore implements CanvasPatchStore {

  private static final String PATCH_FIELD = "patch";

  private final StringRedisTemplate stringRedisTemplate;
  private final CanvasRealtimeProperties properties;
  private final SystemSettings.Advanced advanced;
  private final CanvasPatchJsonCodec codec;

  public RedisCanvasPatchStore(
      StringRedisTemplate stringRedisTemplate,
      CanvasRealtimeProperties properties,
      SystemSettings.Advanced advanced,
      CanvasPatchJsonCodec codec) {
    this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.advanced = Objects.requireNonNull(advanced, "advanced");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public void append(UUID canvasId, CanvasPatch patch) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(patch, "patch");
    stringRedisTemplate
        .opsForStream()
        .add(
            properties.key(canvasId.toString()),
            Map.of(PATCH_FIELD, codec.encode(patch)),
            XAddOptions.maxlen(advanced.canvasRealtimeMaxLength()));
  }

  @Override
  public Optional<CanvasPatch> findByVersion(UUID canvasId, long version) {
    for (CanvasPatch patch : readAll(canvasId)) {
      if (patch.version() == version) {
        return Optional.of(patch);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<CanvasPatch> readAll(UUID canvasId) {
    Objects.requireNonNull(canvasId, "canvasId");
    List<MapRecord<String, Object, Object>> records =
        stringRedisTemplate
            .opsForStream()
            .range(properties.key(canvasId.toString()), Range.unbounded(), Limit.unlimited());
    if (records == null || records.isEmpty()) {
      return List.of();
    }
    List<CanvasPatch> patches = new ArrayList<>(records.size());
    for (MapRecord<String, Object, Object> record : records) {
      Map<Object, Object> body = record.getValue();
      Object payload = body.get(PATCH_FIELD);
      if (body.size() != 1 || !(payload instanceof String payloadJson)) {
        throw new IllegalArgumentException(
            "canvas changes stream record must contain exactly one text patch field");
      }
      patches.add(codec.decode(payloadJson));
    }
    return List.copyOf(patches);
  }
}
