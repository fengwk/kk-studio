package fun.fengwk.kkstudio.core.ai.runtime.retry;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.runtime.retry.mapper.HarnessRetryPolicyMapper;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRetryPolicyDTO;

import java.time.Duration;
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
  public HarnessRetryPolicyDTO updateRetryPolicy(HarnessRetryPolicyDTO retryPolicy) {
    if (retryPolicy == null) {
      throw new IllegalArgumentException("retryPolicy must not be null");
    }
    InvocationRetryPolicy policy = toPolicy(retryPolicy);
    if (mapper.upsert(
            policy.maxRetries(),
            policy.backoffStrategy().name(),
            policy.baseDelay().toMillis(),
            policy.maxDelay().toMillis())
        != 1) {
      throw new IllegalStateException("upsert harness retry policy failed");
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

  private static HarnessRetryPolicyDTO toDto(InvocationRetryPolicy policy) {
    HarnessRetryPolicyDTO dto = new HarnessRetryPolicyDTO();
    dto.setMaxRetries(policy.maxRetries());
    dto.setBackoffStrategy(policy.backoffStrategy().name());
    dto.setBaseDelayMillis(policy.baseDelay().toMillis());
    dto.setMaxDelayMillis(policy.maxDelay().toMillis());
    return dto;
  }
}
