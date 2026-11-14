package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;

import java.util.UUID;

/**
 * 一次 Run 收尾的终态输入及其幂等身份。
 *
 * <p>把「终态状态 + 终态 Entry + final answer + 交接目标 + 错误」的合法性判定与活动身份构造集中在一处：活动身份由动作
 * （CANCEL/FAIL/HANDOFF/COMPLETE）与规范化后的请求字段确定，同一请求键重放命中同一 receipt，异指纹确定性冲突。
 */
record IssueRunTerminal(
    IssueRunStatus status,
    UUID endEntryId,
    UUID finalAnswerEntryId,
    String nextState,
    String error,
    Identity identity) {

  /** 校验终态输入并推导本次收尾的活动身份；顺序与既有错误优先级一致。 */
  static IssueRunTerminal parse(
      UUID runId,
      String requestKey,
      IssueRunStatus status,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      String nextState,
      String error) {
    String key = InputValidation.requireRequestKey(requestKey);
    if (status != IssueRunStatus.COMPLETED
        && status != IssueRunStatus.CANCELLED
        && status != IssueRunStatus.FAILED) {
      throw DomainException.validation("issue_run", "A terminal status is required");
    }
    if ((status != IssueRunStatus.COMPLETED && (finalAnswerEntryId != null || nextState != null))
        || (status != IssueRunStatus.FAILED && error != null)) {
      throw DomainException.validation("issue_run", "Invalid terminal inputs");
    }
    String validError =
        status == IssueRunStatus.FAILED
            ? InputValidation.requireUtf8Text(error, "error", InputValidation.MAX_LARGE_TEXT_BYTES)
            : null;
    boolean handoff = nextState != null;
    Identity identity =
        status == IssueRunStatus.CANCELLED
            ? IssueActivityIdempotency.identity(
                IssueActivityKind.CONTROL,
                "CANCEL_RUN",
                key,
                runId,
                endEntryId,
                PauseReason.USER.name())
            : status == IssueRunStatus.FAILED
                ? IssueActivityIdempotency.identity(
                    IssueActivityKind.CONTROL, "FAIL_RUN", key, runId, endEntryId, validError)
                : handoff
                    ? IssueActivityIdempotency.identity(
                        IssueActivityKind.STATE_CHANGE,
                        "HANDOFF",
                        key,
                        runId,
                        endEntryId,
                        finalAnswerEntryId,
                        nextState)
                    : IssueActivityIdempotency.identity(
                        IssueActivityKind.CONTROL,
                        "COMPLETE_RUN",
                        key,
                        runId,
                        endEntryId,
                        finalAnswerEntryId);
    return new IssueRunTerminal(
        status, endEntryId, finalAnswerEntryId, nextState, validError, identity);
  }

  boolean completed() {
    return status == IssueRunStatus.COMPLETED;
  }

  boolean handoff() {
    return nextState != null;
  }

  /** 非完成终态对应的暂停原因：人工取消记 USER，失败记 ERROR。 */
  PauseReason pauseReason() {
    return status == IssueRunStatus.CANCELLED ? PauseReason.USER : PauseReason.ERROR;
  }

  /** 非完成终态写入 Issue 的暂停说明。 */
  String pauseDetail() {
    return status == IssueRunStatus.CANCELLED ? "Run was stopped by a human" : error;
  }
}
