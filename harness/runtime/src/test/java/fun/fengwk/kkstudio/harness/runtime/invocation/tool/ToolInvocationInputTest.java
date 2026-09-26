package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.call;
import static fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationTestData.descriptor;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 人工输入等待与回答的状态不变量：等待不是审批、回答不是执行。
 *
 * <p>测试意图：锁定 WAITING_INPUT 的形状（binding 冻结、attempt 0、无 approval、无 result/error）、回答引入的回执不可变，以及
 * SUCCEEDED 的两套互斥形状（执行成功必须有正 attempt 与已完成 approval；回答成功必须 attempt 0 且无 approval）。
 */
class ToolInvocationInputTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = CREATED.plusSeconds(1);
  private static final Instant T2 = CREATED.plusSeconds(2);

  private static ToolInvocation ready() {
    return invocation(ToolInvocationStatus.READY, 0, null, null, null, null);
  }

  private static ToolInvocation waitingInput() {
    return invocation(ToolInvocationStatus.WAITING_INPUT, 0, null, null, null, null);
  }

  private static ToolInvocation answered(ToolInvocationStatus status, ToolInputReceipt receipt) {
    return invocation(status, 0, null, result(), null, receipt);
  }

  private static ToolInvocation invocation(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolInputReceipt receipt) {
    return new ToolInvocation(
        id(1L),
        id(1L),
        id(1L),
        0,
        call("ask_user", "{}"),
        askUserBinding(),
        status,
        attempt,
        approval,
        result,
        ToolEffectBatch.EMPTY,
        error,
        CREATED,
        CREATED,
        receipt);
  }

  /** 内置 contributor 贡献的 {@code ask_user}：descriptor 名与调用名一致，localName 可与其不同。 */
  private static ToolBinding askUserBinding() {
    return new ToolBinding(
        new AgentToolDefinition(descriptor("ask_user"), ToolVisibility.SELECTABLE),
        new ContributorBinding("builtin", "ask-user", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ToolResult result() {
    return new ToolResult(
        "call-1",
        List.of(new TextResultContent("{\"answers\":[[\"a\"]]}")),
        false,
        "{\"answers\":[[\"a\"]]}");
  }

  private static ToolInputReceipt receipt() {
    return new ToolInputReceipt(id(9L), "alice", T1);
  }

  /** READY -&gt; WAITING_INPUT：冻结等待，不改 attempt、不引入 approval、不产生 terminal 事实。 */
  @Test
  void readyCanBeParkedAsWaitingInput() {
    ToolInvocation parked = ready().requestInput(T1);

    assertEquals(ToolInvocationStatus.WAITING_INPUT, parked.status());
    assertEquals(0, parked.attempt());
    assertNull(parked.approval());
    assertNull(parked.result());
    assertNull(parked.inputReceipt());
    // 共享转换校验接受该形状（store/PostgreSQL 都依赖它）。
    ToolInvocation.validateTransition(ready(), parked);
  }

  /** 等待不能从已审批或已执行状态引入：只有 READY(attempt 0, 无 approval) 才能冻结。 */
  @Test
  void onlyPlainReadyCanBeParked() {
    ToolInvocation approved =
        invocation(ToolInvocationStatus.READY, 0, ToolApproval.notRequired(), null, null, null);
    assertThrows(IllegalArgumentException.class, () -> approved.requestInput(T1));
    ToolInvocation running =
        invocation(ToolInvocationStatus.RUNNING, 1, ToolApproval.notRequired(), null, null, null);
    assertThrows(IllegalArgumentException.class, () -> running.requestInput(T1));
  }

  /** WAITING_INPUT 的形状是硬约束：携带 approval 或 terminal 事实都被拒绝。 */
  @Test
  void waitingInputRejectsApprovalAndTerminalFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_INPUT,
                0,
                ToolApproval.notRequired(),
                null,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_INPUT, 0, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.WAITING_INPUT,
                0,
                null,
                null,
                new ToolInvocationError("X", "y"),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.WAITING_INPUT, 1, null, null, null, null));
  }

  /** 回答：WAITING_INPUT -&gt; SUCCEEDED + 结果 + 回执，attempt 保持 0 且不携带 approval。 */
  @Test
  void answeringWaitsAsSucceededWithReceipt() {
    ToolInvocation answered = waitingInput().acceptInput(result(), receipt(), T2);

    assertEquals(ToolInvocationStatus.SUCCEEDED, answered.status());
    assertEquals(0, answered.attempt());
    assertNull(answered.approval());
    assertEquals(result(), answered.result());
    assertEquals(receipt(), answered.inputReceipt());
    ToolInvocation.validateTransition(waitingInput(), answered);
  }

  /** 只有等待中的调用能被回答；未等待或已回答的调用必须拒绝。 */
  @Test
  void onlyWaitingInputCanBeAnswered() {
    assertThrows(
        IllegalArgumentException.class, () -> ready().acceptInput(result(), receipt(), T1));
    ToolInvocation answered = waitingInput().acceptInput(result(), receipt(), T1);
    assertThrows(
        IllegalArgumentException.class, () -> answered.acceptInput(result(), receipt(), T2));
  }

  /** 回答不是执行：带正 attempt 或携带 approval 的 SUCCEEDED 都不接受回执。 */
  @Test
  void receiptBelongsToAnsweredSuccessOnly() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 2, null, result(), null, receipt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invocation(
                ToolInvocationStatus.SUCCEEDED,
                0,
                ToolApproval.notRequired(),
                result(),
                null,
                receipt()));
    // 非 SUCCEEDED 状态一律不得携带回执。
    for (ToolInvocationStatus status :
        List.of(
            ToolInvocationStatus.READY,
            ToolInvocationStatus.WAITING_APPROVAL,
            ToolInvocationStatus.DISPATCHING,
            ToolInvocationStatus.RUNNING)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> invocation(status, 0, null, null, null, receipt()),
          status + " must not carry an input receipt");
    }
  }

  /** 执行成功仍要求正 attempt 与已完成的 preflight approval（回归保护）。 */
  @Test
  void executedSuccessStillRequiresItsOwnShape() {
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 0, null, result(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> invocation(ToolInvocationStatus.SUCCEEDED, 1, null, result(), null, null));
  }

  /** 回执是已接受回答的不可变事实：只能由 WAITING_INPUT -&gt; SUCCEEDED 引入，引入后不得改变。 */
  @Test
  void receiptIsIntroducedOnceAndNeverChanges() {
    ToolInvocation answered = waitingInput().acceptInput(result(), receipt(), T1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                answered, answered(answered.status(), new ToolInputReceipt(id(10L), "bob", T2))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                ready(), answered(ToolInvocationStatus.SUCCEEDED, receipt())));
  }

  /** Stop：等待可以被取消（迟到回答不会恢复执行），但不可被伪造为失败。 */
  @Test
  void waitingInputCanOnlyBeAnsweredOrCancelled() {
    ToolInvocation cancelled =
        waitingInput().cancel(new ToolInvocationError("CANCELLED", "stopped"), T1);

    assertEquals(ToolInvocationStatus.CANCELLED, cancelled.status());
    assertNull(cancelled.inputReceipt());
    assertThrows(
        IllegalArgumentException.class,
        () -> waitingInput().fail(new ToolInvocationError("FAILED", "boom"), T1));
    ToolInvocation dispatching =
        invocation(ToolInvocationStatus.WAITING_INPUT, 0, null, null, null, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolInvocation.validateTransition(
                dispatching,
                new ToolInvocation(
                    dispatching.id(),
                    dispatching.modelInvocationId(),
                    dispatching.assistantEntryId(),
                    dispatching.callIndex(),
                    dispatching.call(),
                    dispatching.binding(),
                    ToolInvocationStatus.DISPATCHING,
                    0,
                    ToolApproval.notRequired(),
                    null,
                    ToolEffectBatch.EMPTY,
                    null,
                    T2,
                    T2)));
  }

  /** 回答结果必须精确对应被回答的 ToolCall（与执行成功同一约束）。 */
  @Test
  void answeredResultMustMatchItsToolCall() {
    assertEquals(
        "call-1", waitingInput().acceptInput(result(), receipt(), T1).result().toolCallId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            waitingInput()
                .acceptInput(
                    new ToolResult("other-call", List.of(new TextResultContent("x")), false, "{}"),
                    receipt(),
                    T1));
  }

  /** 回执只接受规范化的非空 actor 标识。 */
  @Test
  void receiptRequiresCanonicalActor() {
    UUID submissionId = id(11L);
    assertThrows(
        IllegalArgumentException.class, () -> new ToolInputReceipt(submissionId, "  alice  ", T1));
    assertThrows(IllegalArgumentException.class, () -> new ToolInputReceipt(submissionId, "", T1));
    assertThrows(NullPointerException.class, () -> new ToolInputReceipt(null, "alice", T1));
    assertThrows(
        NullPointerException.class, () -> new ToolInputReceipt(submissionId, "alice", null));
    ToolInputReceipt receipt = new ToolInputReceipt(submissionId, "alice", T1);
    assertTrue(receipt.replays(submissionId, "alice"));
    assertEquals(false, receipt.replays(id(12L), "alice"));
    assertEquals(false, receipt.replays(submissionId, "bob"));
  }
}
