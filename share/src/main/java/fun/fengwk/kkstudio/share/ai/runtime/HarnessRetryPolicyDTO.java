package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 全局 Harness 自动重试策略；PUT 必须提交完整配置。 */
@Data
public class HarnessRetryPolicyDTO {
  /** 初始调用失败后最多再尝试的次数。 */
  private Integer maxRetries;

  /** {@code FIXED} 或 {@code EXPONENTIAL}。 */
  private String backoffStrategy;

  /** 第一次自动重试前的等待时间。 */
  private Long baseDelayMillis;

  /** 指数退避的等待上限；固定退避仍保持该安全上限。 */
  private Long maxDelayMillis;
}
