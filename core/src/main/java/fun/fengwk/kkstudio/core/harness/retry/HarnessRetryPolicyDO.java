package fun.fengwk.kkstudio.core.harness.retry;

import lombok.Data;

/** 单例全局重试策略持久行。 */
@Data
public class HarnessRetryPolicyDO {
  private Integer id;
  private Integer maxRetries;
  private String backoffStrategy;
  private Long baseDelayMillis;
  private Long maxDelayMillis;
}
