package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link IssueTransitionTool} 的模型可见契约：执行身份必须来自 durable context，业务结论由 {@link IssueTransitionService}
 * 决定，工具只负责参数解析与结果/错误信封。
 */
class IssueTransitionToolTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID INVOCATION_ID = new UUID(0L, 2L);
  private static final UUID RUN_ID = new UUID(0L, 3L);

  private final IssueTransitionService service = mock(IssueTransitionService.class);
  private final IssueTransitionTool tool = new IssueTransitionTool(service);

  /** 记录唯一终态结果的测试监听器。 */
  private static final class RecordingListener implements ToolExecutionListener {

    private final AtomicReference<ToolOutcome> outcome = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolOutcome value) {
      if (!outcome.compareAndSet(null, value)) {
        throw new IllegalStateException("duplicate terminal outcome");
      }
    }

    @Override
    public void onError(Throwable throwable) {
      error.set(throwable);
    }

    ToolResult result() {
      assertTrue(error.get() == null, "tool must not signal a raw failure");
      ToolOutcome value = outcome.get();
      assertTrue(value != null, "tool must produce exactly one terminal outcome");
      return value.result();
    }
  }

  private ToolExecutionRequest request(String argumentsJson, ToolExecutionContext context) {
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("call-1", IssueTransitionTool.NAME, argumentsJson),
        Duration.ofSeconds(1),
        context);
  }

  private static ToolExecutionContext context(UUID threadId) {
    return new ToolExecutionContext(INVOCATION_ID, threadId, Instant.now(), mock(BranchView.class));
  }

  private ToolResult execute(String argumentsJson, ToolExecutionContext context) {
    RecordingListener listener = new RecordingListener();
    tool.execute(request(argumentsJson, context), listener);
    return listener.result();
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  /** 测试意图：工具身份、副作用级别与 schema 冻结为受控交接契约；它不声明任何 Environment 依赖。 */
  @Test
  void descriptorFreezesTheControlledContract() {
    assertEquals("issue_transition", tool.descriptor().name());
    assertEquals(ToolSideEffect.IDEMPOTENT, tool.descriptor().sideEffect());
    assertEquals(EnvironmentSupport.NONE, tool.requirements().environmentSupport());
    assertTrue(tool.descriptor().inputSchema().properties().containsKey("to_state"));
    assertEquals(Set.of("to_state"), tool.descriptor().inputSchema().required());
  }

  /** 测试意图：被接受的交接返回可读回执，并提示模型收尾（不再发起新的业务写）。 */
  @Test
  void returnsAcceptedHandoffReceipt() {
    when(service.accept(THREAD_ID, "REVIEW"))
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
    when(service.accept(THREAD_ID, "REVIEW"))
        .thenReturn(
            new IssueTransitionService.IssueTransitionResult("DESIGN", "REVIEW", RUN_ID, true));

    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", context(THREAD_ID));

    assertFalse(result.error());
    assertTrue(text(result).contains("already accepted"), text(result));
  }

  /** 测试意图：没有 durable Thread 身份时不得推断归属，直接返回错误结果。 */
  @Test
  void returnsErrorWithoutDurableContext() {
    ToolResult result = execute("{\"to_state\":\"REVIEW\"}", null);

    assertTrue(result.error());
    assertTrue(text(result).contains("durable tool execution context"), text(result));
  }

  /** 测试意图：业务拒绝原因必须原样回到模型，否则模型无法修正目标阶段。 */
  @Test
  void returnsValidationMessageFromService() {
    when(service.accept(eq(THREAD_ID), any()))
        .thenThrow(
            new AiValidationException("issue_run", "state DESIGN cannot transition to DONE"));

    ToolResult result = execute("{\"to_state\":\"DONE\"}", context(THREAD_ID));

    assertTrue(result.error());
    assertEquals("state DESIGN cannot transition to DONE", text(result));
  }

  /** 测试意图：未知失败绝不回显内部细节，只给出稳定的通用错误。 */
  @Test
  void hidesUnknownFailureDetail() {
    when(service.accept(eq(THREAD_ID), any()))
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
}
