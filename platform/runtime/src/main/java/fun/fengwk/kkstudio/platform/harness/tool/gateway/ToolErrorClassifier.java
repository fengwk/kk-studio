package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

/**
 * 工具执行错误的唯一 kind 词汇与分类：准入拒绝、执行体异常与结果契约违反共用同一套 kind 常量。
 *
 * <p>准入阶段的拒绝必须声明 {@link ExecutionFact#NOT_EXECUTED}；已经进入执行体的失败按异常类型分类，只有 transport 明确保证未发送的 Busy /
 * Unavailable 才算肯定未执行，其余未知结果绝不声称未执行，也绝不建议同一调用盲目重试。
 *
 * <p>分类只依赖异常类型与已脱敏的 message；底层原始异常只进日志，不进入模型可见文案。
 */
@Slf4j
final class ToolErrorClassifier {

  static final String PERMISSION_DENIED_KIND = "PERMISSION_DENIED";
  static final String TOOL_NOT_FOUND_KIND = "TOOL_NOT_FOUND";
  static final String TOOL_DEFINITION_MISMATCH_KIND = "TOOL_DEFINITION_MISMATCH";
  static final String CANCELLED_KIND = "CANCELLED";
  static final String UNAVAILABLE_KIND = "UNAVAILABLE";
  static final String REMOTE_UNCERTAIN_KIND = "REMOTE_UNCERTAIN";
  static final String INVALID_REQUEST_KIND = "INVALID_REQUEST";
  static final String EXECUTION_FAILED_KIND = "EXECUTION_FAILED";
  static final String INVALID_PARTIAL_KIND = "INVALID_PARTIAL";
  static final String EDIT_REVIEW_REQUIRED_KIND = "EDIT_REVIEW_REQUIRED";
  static final String VFS_READ_ONLY_KIND = "VFS_READ_ONLY";

  /** 确定性拒绝的下一步：同一调用在修复前会得到同样结果，不构成自动重试建议。 */
  static final String DETERMINISTIC_REJECTION_NEXT_ACTION =
      "Correct the underlying contract violation before calling the tool again";

  /** 结果不确定时的下一步：先核查副作用再决定，绝不能假设未执行。 */
  static final String VERIFY_EFFECT_NEXT_ACTION =
      "Check whether the tool already took effect before calling it again";

  private static final String CANCELLED_MESSAGE = "Tool execution was cancelled";

  private ToolErrorClassifier() {}

  /** 肯定未执行且不可重试的执行体异常结果。 */
  static ToolGateway.Completion fail(ToolInvocationError error) {
    return new ToolGateway.Failed(error, false);
  }

  /**
   * 执行被中断：取消可能发生在副作用之后，只能声明结果未确认，绝不能声称未执行。
   *
   * <p>本地工具执行与远端重挂接共用同一文案，避免同一取消事实出现两套措辞。
   */
  static ToolGateway.Completion cancelled() {
    return new ToolGateway.Cancelled(
        new ToolInvocationError(
            CANCELLED_KIND,
            ToolErrorGuidance.message(
                CANCELLED_MESSAGE, ExecutionFact.UNCERTAIN, VERIFY_EFFECT_NEXT_ACTION)));
  }

  /** 无法分类或无法确认结果的失败：原始 message 只进日志，模型只看到固定文案。 */
  static ToolGateway.Completion unknown(String toolName, Throwable failure, String whatFailed) {
    log.warn("tool gateway unknown outcome for tool {}: {}", toolName, failure.getMessage());
    return new ToolGateway.Unknown(
        new ToolInvocationError(
            EXECUTION_FAILED_KIND,
            ToolErrorGuidance.message(
                whatFailed, ExecutionFact.UNCERTAIN, VERIFY_EFFECT_NEXT_ACTION)));
  }

  /** 执行体运行时异常按固定表分类；未命中任何类型时收敛为未知结果。 */
  static ToolGateway.Completion classify(String toolName, RuntimeException error) {
    if (error instanceof EnvironmentCapabilityCancelledException cancelled) {
      // 取消可能发生在副作用之后：只能声明结果未确认，绝不能声称未执行。
      return new ToolGateway.Cancelled(
          new ToolInvocationError(
              CANCELLED_KIND,
              ToolErrorGuidance.message(
                  failureMessage(cancelled, CANCELLED_MESSAGE),
                  ExecutionFact.UNCERTAIN,
                  VERIFY_EFFECT_NEXT_ACTION)));
    }
    if (error instanceof EnvironmentCapabilityFailedException failed) {
      return new ToolGateway.Failed(
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              ToolErrorGuidance.message(
                  failureMessage(failed, "Tool execution failed"),
                  ExecutionFact.FAILED,
                  "Inspect the failure and adjust the request before calling the tool again")),
          false);
    }
    if (error instanceof EnvironmentCapabilitySendUncertainException uncertain) {
      return new ToolGateway.Unknown(
          new ToolInvocationError(
              REMOTE_UNCERTAIN_KIND,
              ToolErrorGuidance.message(
                  failureMessage(uncertain, "Remote tool outcome is uncertain"),
                  ExecutionFact.UNCERTAIN,
                  VERIFY_EFFECT_NEXT_ACTION)));
    }
    if (error instanceof EnvironmentCapabilityUnavailableException unavailable) {
      // transport 契约：unavailable 时调用肯定未执行。
      return new ToolGateway.Failed(
          new ToolInvocationError(
              UNAVAILABLE_KIND,
              ToolErrorGuidance.message(
                  failureMessage(unavailable, "Tool is unavailable"),
                  ExecutionFact.NOT_EXECUTED,
                  "Wait for the Environment to become available, then call the tool again")),
          true);
    }
    if (error instanceof EnvironmentCapabilityBusyException busy) {
      return new ToolGateway.Failed(
          new ToolInvocationError(
              UNAVAILABLE_KIND,
              ToolErrorGuidance.message(
                  failureMessage(busy, "Tool is busy"),
                  ExecutionFact.NOT_EXECUTED,
                  "Wait for the Environment to finish its current work, then call the tool again")),
          true);
    }
    return unknown(toolName, error, "the tool failed for an unclassified reason");
  }

  /** 终态结果本身不满足契约。 */
  static ToolInvocationError invalidResult(String whatFailed) {
    return new ToolInvocationError(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        ToolErrorGuidance.message(
            whatFailed, ExecutionFact.UNCERTAIN, DETERMINISTIC_REJECTION_NEXT_ACTION));
  }

  static ToolInvocationError invalidPartial(String whatFailed) {
    return new ToolInvocationError(
        INVALID_PARTIAL_KIND,
        ToolErrorGuidance.message(
            whatFailed, ExecutionFact.UNCERTAIN, DETERMINISTIC_REJECTION_NEXT_ACTION));
  }

  /** 校验 partial；合法返回 null，非法返回确定性失败。 */
  static ToolInvocationError validatePartial(ToolResult partial, String expectedCallId) {
    if (partial == null) {
      return invalidPartial("partial must not be null");
    }
    if (!partial.toolCallId().equals(expectedCallId)) {
      return invalidPartial("partial toolCallId does not match the request call");
    }
    for (ResultContent content : partial.contents()) {
      if (content instanceof BinaryResultContent || content instanceof ResourceResultContent) {
        return invalidPartial("partial must not carry binary or resource content");
      }
    }
    if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
        partial, ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES)) {
      return invalidPartial(
          "partial must not exceed "
              + ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES
              + " bytes of canonical tool result JSON");
    }
    return null;
  }

  /** 异常 message 为 null 或空白时退化为给定文案。 */
  static String failureMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }
}
