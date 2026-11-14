package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.harness.model.ModelInvocationError;

/**
 * 一次模型失败的普通重试资格：候选判定来自 {@link ModelInvocationError#retryCandidate()}，带 HTTP 状态时再由现读的 {@link
 * fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy} 唯一决定；其余非 HTTP 失败走共享 {@link
 * fun.fengwk.kkstudio.harness.api.retry.InvocationRetryPolicy} 预算。
 *
 * <p>单模型失败与模型组成员失败共用同一判定；调用点保持在失败判定当时，不改写配置读取时机。
 */
final class ModelFailureRetry {

  private ModelFailureRetry() {}

  static boolean isRetryable(
      ModelInvocationError error, ModelProcessorConfig config, String providerName) {
    if (!error.retryCandidate()) {
      return false;
    }
    if (error.httpStatus() != null) {
      return config.httpErrorPolicyProvider().apply(providerName).allowsRetry(error.httpStatus());
    }
    return true;
  }
}
