package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.SpawnQuotaExceededException;
import fun.fengwk.kkstudio.harness.api.join.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.builtin.BuiltinHistoryRenderers;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 以普通 durable Harness Thread 运行隔离 Subagent 的内部 {@code task} 工具适配器。
 *
 * <p>本工具持久接受委派 Join 后立即退出；Join 匹配时由父 TOOL Work 投影冻结结果为原调用的 tool_result。
 *
 * <p>参数被拒或 Runner 抛异常都收敛为错误结果，绝不抛出。
 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";

  /** 并发额度满：没有创建子执行，模型可在之后的批次重试。 */
  private static final String QUOTA_EXCEEDED =
      "not started: the subagent concurrency limit is reached; retry in a later batch";

  /** task 失败的统一下一步：明确列继续既有子线程所需的 thread_id/subagent_type/prompt，不构成重试倾向建议。 */
  private static final String TASK_CONTINUATION_NEXT_ACTION =
      "Call task with subagent_type and prompt; add the subagent's thread_id to continue an"
          + " existing subagent";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          SubagentPrompts.taskToolDescription(),
          "task",
          SubagentPrompts.taskInputSchema(),
          ToolSideEffect.NON_IDEMPOTENT,
          Duration.ZERO);

  private final SubagentRunner runner;
  private final ObjectMapper objectMapper;

  public TaskTool(SubagentRunner runner, ObjectMapper objectMapper) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** 历史动作：委派给了哪个子代理；thread_id / max_turns / prompt 由结果表达，不属于动作语义。 */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(BuiltinHistoryRenderers.task());
  }

  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    Objects.requireNonNull(request, "request");
    if (request.context() == null) {
      throw new IllegalArgumentException("task requires durable ToolExecutionContext");
    }
    String callId = request.call().id();
    SubagentTaskRequest taskRequest;
    try {
      taskRequest =
          SubagentTaskRequest.parseArguments(
              objectMapper,
              request.context().invocationId(),
              request.context().threadId(),
              request.call());
    } catch (IllegalArgumentException rejected) {
      // 参数被拒是派发前确定性结果：父线程保留，明确未执行并给出继续参数。
      return new ToolOutcome(
          error(
              callId,
              ToolErrorGuidance.message(
                  rejected.getMessage(),
                  ExecutionFact.NOT_EXECUTED,
                  TASK_CONTINUATION_NEXT_ACTION)));
    }
    try {
      runner.accept(
          taskRequest,
          acceptance -> progress.accept(running(callId, taskRequest.subagentType(), acceptance)));
      return ToolOutcome.deferredForChild();
    } catch (RuntimeException failure) {
      if (quotaExceeded(failure)) {
        return new ToolOutcome(error(callId, QUOTA_EXCEEDED));
      }
      return new ToolOutcome(
          error(
              callId,
              ToolErrorGuidance.message(
                  message(failure), ExecutionFact.UNCERTAIN, TASK_CONTINUATION_NEXT_ACTION)));
    }
  }

  private ToolResult running(
      String callId, String subagentType, SubagentTaskAcceptance acceptance) {
    return statusResult(callId, subagentType, acceptance, "running", "Subagent is running.", false);
  }

  private ToolResult statusResult(
      String callId,
      String subagentType,
      SubagentTaskAcceptance acceptance,
      String status,
      String text,
      boolean error) {
    ObjectNode details = objectMapper.createObjectNode();
    details.put("kind", "task.result");
    details.put("thread_id", acceptance.childThreadId().toString());
    details.put("status", status);
    details.put("session_id", acceptance.childSessionId().toString());
    details.put("subagent_type", subagentType);
    details.put("replayed", acceptance.replayed());
    return new ToolResult(
        callId,
        List.of(new TextResultContent(text == null ? "" : text)),
        error,
        details.toString());
  }

  private static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextResultContent(detail)), true, "{}");
  }

  private static String message(Throwable error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 额度满时没有创建任何子执行，可以明确告知模型稍后重试；准入异常可能被调用链包装，因此沿 cause 链判断。 */
  private static boolean quotaExceeded(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SpawnQuotaExceededException) {
        return true;
      }
    }
    return false;
  }
}
