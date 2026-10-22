package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;

import java.util.Objects;

/** 模型组一步失败的唯一纯决策：普通重试、冻结并降级、或保留专用终止语义。 */
final class ModelGroupFailureDecider {

  enum Action {
    RETRY_CURRENT,
    ADVANCE,
    DEDICATED_TERMINAL
  }

  private ModelGroupFailureDecider() {}

  /**
   * @param retryable 现有平台 HTTP/普通 retry 判定结果
   * @param retryAllowed 现有普通重试预算是否仍允许当前成员再试
   */
  static Action decide(
      ModelInvocationError error,
      ModelGroupExecution group,
      boolean retryable,
      boolean retryAllowed) {
    Objects.requireNonNull(error, "error");
    Objects.requireNonNull(group, "group");
    // 取消与上下文溢出沿用专用终止/压缩语义，不能把模型组当作普通上游失败降级。
    if (error.kind() == ProviderErrorKind.CANCELLED || error.kind() == ProviderErrorKind.OVERFLOW) {
      return Action.DEDICATED_TERMINAL;
    }
    if (error.httpStatus() != null && group.fallbackStatusCodes().contains(error.httpStatus())) {
      return Action.ADVANCE;
    }
    if (retryable && retryAllowed) {
      return Action.RETRY_CURRENT;
    }
    return Action.ADVANCE;
  }
}
