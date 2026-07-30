package fun.fengwk.kkstudio.core.ai.runtime.retry;

import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessRetryPolicyDTO;

/** 全局自动重试策略的读取、替换和 Runtime 解析边界。 */
public interface HarnessRetryPolicyService extends InvocationRetryPolicyResolver {
  HarnessRetryPolicyDTO getRetryPolicy();

  HarnessRetryPolicyDTO updateRetryPolicy(HarnessRetryPolicyDTO retryPolicy);
}
