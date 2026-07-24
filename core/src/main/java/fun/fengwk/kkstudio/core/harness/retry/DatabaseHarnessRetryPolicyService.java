package fun.fengwk.kkstudio.core.harness.retry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.retry.mapper.HarnessRetryPolicyMapper;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.share.model.HarnessRetryPolicyDTO;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** 数据库支持的全局 retry policy；schema 必须初始化单例持久行。 */
@Service
public class DatabaseHarnessRetryPolicyService implements HarnessRetryPolicyService {
  private static final int MAX_RETRIES_LIMIT = 10;
  private static final long MIN_DELAY_MILLIS = 1_000L;
  private static final long MAX_DELAY_MILLIS = 60_000L;

  private final HarnessRetryPolicyMapper mapper;

  public DatabaseHarnessRetryPolicyService(HarnessRetryPolicyMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public InvocationRetryPolicy resolve() {
    HarnessRetryPolicyDO policy = mapper.find();
    if (policy == null) {
      throw new IllegalStateException("harness retry policy is not initialized");
    }
    return toPolicy(policy);
  }

  @Override
  public HarnessRetryPolicyDTO getRetryPolicy() {
    return toDto(resolve());
  }

  @Override
  @Transactional
  public HarnessRetryPolicyDTO updateRetryPolicy(HarnessRetryPolicyDTO retryPolicy) {
    if (retryPolicy == null) {
      throw new IllegalArgumentException("retryPolicy must not be null");
    }
    InvocationRetryPolicy policy = toPolicy(retryPolicy);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    HarnessRetryPolicyDO existing = mapper.findForUpdate();
    if (existing == null) {
      HarnessRetryPolicyDO row = toDO(policy, now);
      if (mapper.insert(row) == 1) {
        return toDto(policy);
      }
    }
    if (mapper.update(
            policy.maxRetries(),
            policy.backoffStrategy().name(),
            policy.baseDelay().toMillis(),
            policy.maxDelay().toMillis(),
            now)
        != 1) {
      throw new IllegalStateException("update harness retry policy failed");
    }
    return toDto(policy);
  }

  private static InvocationRetryPolicy toPolicy(HarnessRetryPolicyDTO dto) {
    return toPolicy(
        dto.getMaxRetries(),
        dto.getBackoffStrategy(),
        dto.getBaseDelayMillis(),
        dto.getMaxDelayMillis());
  }

  private static InvocationRetryPolicy toPolicy(HarnessRetryPolicyDO row) {
    return toPolicy(
        row.getMaxRetries(),
        row.getBackoffStrategy(),
        row.getBaseDelayMillis(),
        row.getMaxDelayMillis());
  }

  private static InvocationRetryPolicy toPolicy(
      Integer maxRetries, String backoffStrategy, Long baseDelayMillis, Long maxDelayMillis) {
    if (maxRetries == null || maxRetries < 0 || maxRetries > MAX_RETRIES_LIMIT) {
      throw new IllegalArgumentException("maxRetries must be between 0 and " + MAX_RETRIES_LIMIT);
    }
    long base = requireDelay(baseDelayMillis, "baseDelayMillis");
    long maximum = requireDelay(maxDelayMillis, "maxDelayMillis");
    if (maximum < base) {
      throw new IllegalArgumentException("maxDelayMillis must not be less than baseDelayMillis");
    }
    return new InvocationRetryPolicy(
        maxRetries,
        InvocationRetryBackoffStrategy.fromValue(backoffStrategy),
        Duration.ofMillis(base),
        Duration.ofMillis(maximum));
  }

  private static long requireDelay(Long value, String name) {
    if (value == null || value < MIN_DELAY_MILLIS || value > MAX_DELAY_MILLIS) {
      throw new IllegalArgumentException(
          name
              + " must be between "
              + MIN_DELAY_MILLIS
              + " and "
              + MAX_DELAY_MILLIS
              + " milliseconds");
    }
    return value;
  }

  private static HarnessRetryPolicyDO toDO(InvocationRetryPolicy policy, OffsetDateTime now) {
    HarnessRetryPolicyDO row = new HarnessRetryPolicyDO();
    row.setId(1);
    row.setMaxRetries(policy.maxRetries());
    row.setBackoffStrategy(policy.backoffStrategy().name());
    row.setBaseDelayMillis(policy.baseDelay().toMillis());
    row.setMaxDelayMillis(policy.maxDelay().toMillis());
    row.setCreateTime(now);
    row.setUpdateTime(now);
    return row;
  }

  private static HarnessRetryPolicyDTO toDto(InvocationRetryPolicy policy) {
    HarnessRetryPolicyDTO dto = new HarnessRetryPolicyDTO();
    dto.setMaxRetries(policy.maxRetries());
    dto.setBackoffStrategy(policy.backoffStrategy().name());
    dto.setBaseDelayMillis(policy.baseDelay().toMillis());
    dto.setMaxDelayMillis(policy.maxDelay().toMillis());
    return dto;
  }
}
