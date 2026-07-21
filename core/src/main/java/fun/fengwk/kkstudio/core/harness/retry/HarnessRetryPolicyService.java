package fun.fengwk.kkstudio.core.harness.retry;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRetryPolicyResolver;
import fun.fengwk.kkstudio.share.model.HarnessRetryPolicyDTO;

/** 全局自动重试策略的读取、替换和 Runtime 解析边界。 */
public interface HarnessRetryPolicyService extends ThreadRetryPolicyResolver {
  HarnessRetryPolicyDTO getRetryPolicy();

  HarnessRetryPolicyDTO updateRetryPolicy(HarnessRetryPolicyDTO retryPolicy);
}
