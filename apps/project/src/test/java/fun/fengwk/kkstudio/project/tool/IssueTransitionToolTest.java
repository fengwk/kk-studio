package fun.fengwk.kkstudio.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.error.DomainException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IssueTransitionTool} 的模型可见契约：执行身份只来自 Harness 冻结的执行上下文（调用 Thread 与该调用的 assistant 入口），业务结论由
 * {@link IssueTransitionService} 决定，工具只负责参数解析与结果/错误信封。
 */
class IssueTransitionToolTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID INVOCATION_ID = new UUID(0L, 2L);
  private static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 3L);
  private static final UUID RUN_ID = new UUID(0L, 4L);
  private static final UUID SESSION_ID = new UUID(0L, 6L);
  private static final UUID ROOT_THREAD_ID = new UUID(0L, 7L);
  private static final UUID ROOT_SESSION_ID = new UUID(0L, 8L);

  private final IssueTransitionService service = mock(IssueTransitionService.class);
  private final IssueTransitionTool tool = new IssueTransitionTool(service);

  private ToolExecutionRequest request(String argumentsJson, ToolExecutionContext context) {
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("call-1", IssueTransitionTool.NAME, argumentsJson),
        Duration.ofSeconds(1),
        context);
  }

  /** 冻结的执行上下文：工具据此定位调用 Thread 与该调用的真实 assistant 入口，模型参数里没有 Run 身份。 */
  private static ToolExecutionContext context(UUID threadId, UUID assistantEntryId) {
    return new ToolExecutionContext(
        INVOCATION_ID,
        threadId,
        SESSION_ID,
        ROOT_THREAD_ID,
        ROOT_SESSION_ID,
        assistantEntryId,
        Instant.now(),
        TurnMediaCapabilities.NONE,
        mock(BranchView.class),
        Optional.empty());
  }

  private static ToolExecutionContext context(UUID threadId) {
    return context(threadId, ASSISTANT_ENTRY_ID);
  }

  private ToolResult execute(String argumentsJson, ToolExecutionContext context) {
    return tool.execute(request(argumentsJson, context), partial -> {}).result();
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  /** 测试意图：工具身份、副作用级别与 schema 冻结为受控交接契约；它不声明 Environment 或任何 branch state 依赖。 */
  @Test
  void descriptorFreezesTheControlledContract() {
    assertEquals("issue_transition", tool.descriptor().name());
    assertEquals(ToolSideEffect.IDEMPOTENT, tool.descriptor().sideEffect());
    assertEquals(EnvironmentSupport.NONE, tool.requirements().environmentSupport());
    assertTrue(tool.requirements().stateAccesses().isEmpty());
    assertTrue(tool.descriptor().inputSchema().properties().containsKey("to_state"));
    assertEquals(Set.of("to_state"), tool.descriptor().inputSchema().required());
  }

  /** 测试意图：被接受的交接返回可读回执，并提示模型收尾（不再发起新的业务写）。 */
  @Test
  void returnsAcceptedHandoffReceipt() {
    when(service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW"))
        .thenReturn(
            new IssueTransitionService.IssueTransitionResult("DESIGN", "REVIEW", RUN_ID, false));

    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", context(THREAD_ID));

    assertFalse(result.error());
    String text = text(result);
    assertTrue(text.contains("DESIGN -> REVIEW"), text);
    assertTrue(text.contains("Do not start new business writes"), text);
  }

  /** 测试意图：幂等重放回执必须与首次接受可区分，避免模型误判自己没有交接成功而重复尝试。 */
  @Test
  void returnsReplayReceiptForIdempotentRetry() {
    when(service.accept(THREAD_ID, ASSISTANT_ENTRY_ID, "REVIEW"))
        .thenReturn(
            new IssueTransitionService.IssueTransitionResult("DESIGN", "REVIEW", RUN_ID, true));

    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", context(THREAD_ID));

    assertFalse(result.error());
    assertTrue(text(result).contains("already accepted"), text(result));
  }

  /** 测试意图：没有 durable 执行上下文时不得推断归属，直接返回错误结果。 */
  @Test
  void returnsErrorWithoutDurableContext() {
    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", null);

    assertTrue(result.error());
    assertTrue(text(result).contains("durable tool execution context"), text(result));
  }

  /** 测试意图：业务拒绝原因必须原样回到模型，否则模型无法修正目标阶段。 */
  @Test
  void returnsValidationMessageFromService() {
    when(service.accept(eq(THREAD_ID), eq(ASSISTANT_ENTRY_ID), anyString()))
        .thenThrow(
            DomainException.validation("issue_run", "state DESIGN cannot transition to DONE"));

    ToolResult result = execute("{\"to_state\":\"DONE\"}", context(THREAD_ID));

    assertTrue(result.error());
    assertEquals("state DESIGN cannot transition to DONE", text(result));
  }

  /** 测试意图：未知失败绝不回显内部细节，只给出稳定的通用错误。 */
  @Test
  void hidesUnknownFailureDetail() {
    when(service.accept(eq(THREAD_ID), eq(ASSISTANT_ENTRY_ID), anyString()))
        .thenThrow(new IllegalStateException("jdbc connection lost"));

    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", context(THREAD_ID));

    assertTrue(result.error());
    assertEquals("issue_transition failed", text(result));
  }

  /** 测试意图：空白目标值在工具边界拒绝，不把空目标带进业务事务。 */
  @Test
  void rejectsBlankTargetState() {
    ToolResult result = execute("{\"to_state\":\"  \"}", context(THREAD_ID));

    assertTrue(result.error());
    assertTrue(text(result).contains("to_state"), text(result));
  }

  /** 测试意图：工具把执行上下文里的 assistant 入口原样交给业务事务，不做任何身份推断。 */
  @Test
  void forwardsAssistantEntryIdentityToTheTransaction() {
    UUID laterEntryId = UUID.randomUUID();
    when(service.accept(THREAD_ID, laterEntryId, "REVIEW"))
        .thenReturn(
            new IssueTransitionService.IssueTransitionResult("DESIGN", "REVIEW", RUN_ID, false));

    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", context(THREAD_ID, laterEntryId));

    assertFalse(result.error());
    assertTrue(text(result).contains("DESIGN -> REVIEW"), text(result));
  }
}
