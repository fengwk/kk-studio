package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.join.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.spi.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.spi.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tool execution 的 durable 终态写入：RUNNING 登记、retry 回退、成功结果物化与失败 / 取消 / 未知收敛。
 *
 * <p>每个写入都在自己的短事务内按本 Thread 行 -&gt; ToolInvocation -&gt; claimed Work 加锁，校验 status + attempt
 * 后写回；任一步不满足即完整 no-op（返回 false）。事务之外不做任何持久化，也绝不重放外部副作用。
 */
@Slf4j
final class ToolTerminalWrites {

  private final HarnessStore store;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;

  ToolTerminalWrites(
      HarnessStore store, ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.toolResultHistoryMaterializer =
        Objects.requireNonNull(toolResultHistoryMaterializer, "toolResultHistoryMaterializer");
  }

  /** READY -&gt; RUNNING（attempt+1）+ version+1 并登记退出事实；事务提交成功但未登记时返回 {@code null}。 */
  ExecutionExitFact registerRunning(
      UUID executionId,
      ClaimedWork claim,
      UUID threadId,
      UUID invocationId,
      int attempt,
      ToolInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    return store.transaction(
        tx -> {
          Instant now = tx.now();
          ThreadState thread = tx.threads().lock(threadId).orElse(null);
          if (thread == null) {
            return null;
          }
          ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
          if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
            return null;
          }
          if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt - 1) {
            return null;
          }
          tx.tools().update(List.of(tool.markRunning(now)));
          tx.threads().update(thread.touchVersion(now));
          return ExecutionExitLedger.register(
              tx,
              executionId,
              threadId,
              invocationId,
              attempt,
              claim.leaseToken(),
              ExecutionExitKind.TOOL,
              null);
        });
  }

  /**
   * 挂接执行体在事务内锁 Thread -&gt; Tool -&gt; Work 登记本节点的 TOOL 事实： 同一 attempt、新 executionId、本 claim
   * token。不调用 markRunning，不修改 version。
   */
  ExecutionExitFact registerReattach(
      UUID executionId, ClaimedWork claim, UUID threadId, UUID invocationId, int attempt) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElse(null);
          if (thread == null) {
            return null;
          }
          ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
          if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
            return null;
          }
          if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
            return null;
          }
          return ExecutionExitLedger.register(
              tx,
              executionId,
              threadId,
              invocationId,
              attempt,
              claim.leaseToken(),
              ExecutionExitKind.TOOL,
              null);
        });
  }

  /** RUNNING -&gt; READY + version+1 + reschedule；不 request THREAD。 */
  boolean retry(ClaimedWork claim, UUID threadId, UUID invocationId, int attempt, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.tools().update(List.of(tool.retryReady(now)));
              tx.threads().update(thread.touchVersion(now));
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  /** RUNNING -&gt; WAITING_CHILD：已接受的 task Join 成为唯一等待事实，并立即释放 TOOL claim。 */
  boolean waitingForChild(ClaimedWork claim, UUID threadId, UUID invocationId, int attempt) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
              if (tool == null
                  || tool.status() != ToolInvocationStatus.RUNNING
                  || tool.attempt() != attempt) {
                return false;
              }
              ThreadJoin join =
                  tx.joins()
                      .find(invocationId)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "accepted task has no durable Join " + invocationId));
              SubagentTaskRequest.requireMatchingJoin(tool, join);
              if (join.supersededByInvocationId() != null) {
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
              }
              if (tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (thread.executionControl().isStopped()) {
                tx.tools()
                    .update(
                        List.of(
                            tool.cancel(
                                new ToolInvocationError(
                                    "PARENT_STOPPED",
                                    "parent thread stopped while task was accepted"),
                                now)));
              } else if (join.supersededByInvocationId() != null) {
                tx.tools()
                    .update(
                        List.of(
                            tool.fail(
                                new ToolInvocationError(
                                    "TASK_SUPERSEDED",
                                    "task Join was superseded by a later delegation"),
                                now)));
              } else {
                tx.tools().update(List.of(tool.waitForChild(now)));
              }
              tx.threads().update(thread.touchVersion(now));
              tx.work().complete(claim);
              return true;
            }));
  }

  /**
   * RUNNING -&gt; SUCCEEDED：在同一事务内先把结果物化为 Session 稳定引用，再保存该唯一稳定结果与 SUCCEEDED。物化永久失败时回滚整个
   * 成功事务，并以独立的无资源 UNKNOWN 收敛，绝不重试外部副作用或再次消费同一 upload。
   */
  boolean success(
      ClaimedWork claim,
      UUID threadId,
      UUID invocationId,
      int attempt,
      ToolInvocationRequest request,
      ToolResult result) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ToolResult stable =
                    toolResultHistoryMaterializer.materialize(
                        thread.sessionId(), request.call().toolName(), result);
                requireStableResult(stable, request.call().id());
                if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
                    stable, ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
                  throw new ToolOutcomeMaterializationException(
                      "stable tool outcome exceeds "
                          + ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES
                          + " bytes");
                }
                tx.tools().update(List.of(tool.succeed(stable, now)));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    } catch (ToolOutcomeMaterializationException failure) {
      log.warn(
          "cannot materialize stable tool outcome for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      String message =
          failure.getMessage() == null || failure.getMessage().isBlank()
              ? "tool result resource persistence failed"
              : failure.getMessage();
      return terminal(
          claim,
          threadId,
          invocationId,
          attempt,
          TerminalKind.UNKNOWN,
          new ToolInvocationError("RESOURCE_MATERIALIZATION_FAILED", message));
    }
  }

  /** 收敛为失败 / 取消 / 未知终态，并请求 THREAD Work 让模型看到结果。 */
  boolean terminal(
      ClaimedWork claim,
      UUID threadId,
      UUID invocationId,
      int attempt,
      TerminalKind kind,
      ToolInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ToolInvocation next =
                    switch (kind) {
                      case FAILED -> tool.fail(error, now);
                      case CANCELLED -> tool.cancel(error, now);
                      case UNKNOWN -> tool.unknown(error, now);
                    };
                tx.tools().update(List.of(next));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /** 稳定结果再校验：同一调用、仅 Text/JSON/Session Resource，且不含 inline binary 或瞬时 upload。 */
  private static void requireStableResult(ToolResult result, String toolCallId) {
    if (result == null) {
      throw new ToolOutcomeMaterializationException("stable tool outcome must not be null");
    }
    if (!result.toolCallId().equals(toolCallId)) {
      throw new ToolOutcomeMaterializationException(
          "stable tool outcome toolCallId does not match the request call");
    }
    for (ResultContent content : result.contents()) {
      if (content instanceof BinaryResultContent) {
        throw new ToolOutcomeMaterializationException(
            "stable tool outcome must not carry inline binary content");
      }
      if (content instanceof ResourceResultContent resource
          && resource.resource().sessionBlobId() == null) {
        throw new ToolOutcomeMaterializationException(
            "stable tool outcome resources must be session resources");
      }
    }
  }

  /** 收敛为哪个终态。 */
  enum TerminalKind {
    /** 收敛为明确的失败终态。 */
    FAILED,

    /** 收敛为取消终态。 */
    CANCELLED,

    /** 收敛为结果不确定的终态。 */
    UNKNOWN
  }
}
