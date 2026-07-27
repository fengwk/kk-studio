package fun.fengwk.kkstudio.core.harness.realtime.stream;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.realtime.stream.mapper.HarnessRealtimeStreamPolicyMapper;
import fun.fengwk.kkstudio.share.model.HarnessRealtimeStreamPolicyDTO;

import java.time.Duration;
import java.util.Objects;

/**
 * 数据库支持的全局 realtime Stream 策略。
 *
 * <p>Redis sink 在每条 realtime event 上调用 {@link #resolveMaxLength()}，因此本类将数据库读取限制为每个进程最多每秒一次。成功 PUT
 * 会立即替换本实例缓存；其他实例至多在一个刷新周期后读取到数据库中的新值。
 */
@Service
public class DatabaseHarnessRealtimeStreamPolicyService
    implements HarnessRealtimeStreamPolicyService {
  private static final long CACHE_REFRESH_NANOS = Duration.ofSeconds(1).toNanos();

  private final HarnessRealtimeStreamPolicyMapper mapper;
  private volatile CachedMaxLength cached;

  public DatabaseHarnessRealtimeStreamPolicyService(HarnessRealtimeStreamPolicyMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public long resolveMaxLength() {
    long now = System.nanoTime();
    CachedMaxLength current = cached;
    if (current != null && now - current.loadedAtNanos() < CACHE_REFRESH_NANOS) {
      return current.maxLength();
    }
    synchronized (this) {
      current = cached;
      now = System.nanoTime();
      if (current != null && now - current.loadedAtNanos() < CACHE_REFRESH_NANOS) {
        return current.maxLength();
      }
      long maxLength = loadMaxLength();
      cached = new CachedMaxLength(maxLength, now);
      return maxLength;
    }
  }

  @Override
  public HarnessRealtimeStreamPolicyDTO getPolicy() {
    return toDto(resolveMaxLength());
  }

  @Override
  public HarnessRealtimeStreamPolicyDTO updatePolicy(HarnessRealtimeStreamPolicyDTO dto) {
    long maxLength = requireMaxLength(dto);
    synchronized (this) {
      if (mapper.upsert(maxLength) != 1) {
        throw new IllegalStateException("upsert harness realtime stream policy failed");
      }
      cached = new CachedMaxLength(maxLength, System.nanoTime());
    }
    return toDto(maxLength);
  }

  private long loadMaxLength() {
    HarnessRealtimeStreamPolicyDO policy = mapper.find();
    if (policy == null) {
      throw new IllegalStateException("harness realtime stream policy is not initialized");
    }
    Long maxLength = policy.getMaxLength();
    if (maxLength == null || maxLength <= 0) {
      throw new IllegalStateException("harness realtime stream policy maxLength must be positive");
    }
    return maxLength;
  }

  private static long requireMaxLength(HarnessRealtimeStreamPolicyDTO policy) {
    if (policy == null) {
      throw new IllegalArgumentException("realtimeStreamPolicy must not be null");
    }
    Long maxLength = policy.getMaxLength();
    if (maxLength == null || maxLength <= 0) {
      throw new IllegalArgumentException("maxLength must be positive");
    }
    return maxLength;
  }

  private static HarnessRealtimeStreamPolicyDTO toDto(long maxLength) {
    HarnessRealtimeStreamPolicyDTO dto = new HarnessRealtimeStreamPolicyDTO();
    dto.setMaxLength(maxLength);
    return dto;
  }

  private record CachedMaxLength(long maxLength, long loadedAtNanos) {}
}
