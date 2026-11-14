package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * preflight 结果与重排的 durable 写入：把「工具肯定未执行」的决策固化为 Tool 行状态或 Work 延迟。
 *
 * <p>每个写入都在自己的短事务内按 own Thread → ToolInvocation → Work 锁序二次校验 claim 与期望状态，任一条件不满足即完整
 * no-op；这里不启动执行、不改 attempt。
 */
final class ToolWorkWrites {

  private final HarnessStore store;

  ToolWorkWrites(HarnessStore store) {
    this.store = store;
  }

  /**
   * Allow：把尚未审批的 READY 调用持久为 not-required approval，并递增 Thread version。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean allow(ClaimedWork claim, UUID threadId, int attempt) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ReadyRows ready = lockUnapprovedReady(tx, claim, threadId, attempt);
              if (ready == null) {
                return false;
              }
              tx.tools().update(List.of(ready.tool().markApprovalNotRequired(ready.now())));
              tx.threads().update(ready.thread().touchVersion(ready.now()));
              return true;
            }));
  }

  /**
   * Ask：READY -&gt; WAITING_APPROVAL(reason)，version+1，complete TOOL Work；不 request THREAD。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean ask(ClaimedWork claim, UUID threadId, int attempt, ToolGateway.Ask ask) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ReadyRows ready = lockUnapprovedReady(tx, claim, threadId, attempt);
              if (ready == null) {
                return false;
              }
              tx.tools()
                  .update(
                      List.of(
                          ready
                              .tool()
                              .requestApproval(ask.reason(), ask.editReview(), ready.now())));
              tx.threads().update(ready.thread().touchVersion(ready.now()));
              tx.work().complete(claim);
              return true;
            }));
  }

  /**
   * 肯定未执行的确定性失败（preflight Deny / admission Rejected）：READY -&gt; FAILED + version+1 + 请求 THREAD
   * Work + complete TOOL Work（attempt 不变）。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean fail(ClaimedWork claim, UUID threadId, int attempt, ToolInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.tools().update(List.of(tool.fail(error, now)));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /**
   * 肯定未执行且无需改状态（preflight 失败 / admission Busy 或失败 / close）：二次校验 READY + attempt 后仅按延迟 reschedule
   * TOOL Work，无 version。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean rescheduleReady(ClaimedWork claim, UUID threadId, int attempt, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                return false;
              }
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  /**
   * 远端重新挂接的 RUNNING 重排：锁后二次校验 claim + RUNNING + attempt 后按延迟 reschedule TOOL Work，无 version。
   *
   * @return false 表示 claim 已丢失或状态被并发改写，完整 no-op
   */
  boolean rescheduleRunning(ClaimedWork claim, UUID threadId, int attempt, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  /** 已锁定的未审批 READY 行：READY + 期望 attempt + approval null 且 claim 仍 owned 时返回，否则 null（完整 no-op）。 */
  private static ReadyRows lockUnapprovedReady(
      HarnessStore.Transaction tx, ClaimedWork claim, UUID threadId, int attempt) {
    Instant now = tx.now();
    ThreadState thread = tx.threads().lock(threadId).orElse(null);
    if (thread == null) {
      return null;
    }
    ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
    if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
      return null;
    }
    if (tool.status() != ToolInvocationStatus.READY
        || tool.attempt() != attempt
        || tool.approval() != null) {
      return null;
    }
    return new ReadyRows(thread, tool, now);
  }

  private record ReadyRows(ThreadState thread, ToolInvocation tool, Instant now) {}
}
