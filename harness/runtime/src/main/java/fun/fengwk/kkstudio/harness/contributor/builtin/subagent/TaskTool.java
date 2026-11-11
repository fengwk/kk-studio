package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.builtin.BuiltinHistoryRenderers;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 以普通 durable Harness Thread 运行隔离 Subagent 的内部 {@code task} 工具适配器。
 *
 * <p>本工具以内部 {@code async=false} 委派任务，并等待 Join 冻结结果后才返回对应
 * tool_result。等待发生在独立工具执行线程中，不占用事务或调度线程；并发批次仍由父 Thread 的 Work 状态机统一结算。
 *
 * <p>参数被拒或 Runner 抛异常都收敛为错误结果，绝不抛出。
 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";

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

  public TaskTool(SubagentRunner runner) {
    this(runner, JsonMapper.builder().build());
  }

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
          parseArguments(
              request.context().invocationId(), request.context().threadId(), request.call());
    } catch (TaskRejectedException rejected) {
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
      SubagentTaskResult taskResult =
          runner.run(
              taskRequest,
              false,
              acceptance ->
                  progress.accept(running(callId, taskRequest.subagentType(), acceptance)));
      return new ToolOutcome(result(callId, taskRequest.subagentType(), taskResult));
    } catch (RuntimeException failure) {
      String detail = message(failure);
      if (detail.contains("subagent concurrency quota exceeded")
          || detail.contains("parent join quota exceeded")) {
        detail = "not started: the subagent concurrency limit is reached; retry in a later batch";
        return new ToolOutcome(error(callId, detail));
      }
      return new ToolOutcome(
          error(
              callId,
              ToolErrorGuidance.message(
                  detail, ExecutionFact.UNCERTAIN, TASK_CONTINUATION_NEXT_ACTION)));
    }
  }

  private SubagentTaskRequest parseArguments(
      UUID invocationId, UUID parentThreadId, ToolCall call) {
    JsonNode value;
    try {
      value = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
    } catch (JacksonException error) {
      throw reject("task arguments are not valid JSON");
    }
    if (!(value instanceof ObjectNode node)) {
      throw reject("task arguments must be a JSON object");
    }
    String subagentType = requiredText(node, "subagent_type");
    String prompt = requiredText(node, "prompt");
    Integer maxTurns = null;
    JsonNode maxTurnsNode = node.get("max_turns");
    if (maxTurnsNode != null && !maxTurnsNode.isNull()) {
      if (!maxTurnsNode.isIntegralNumber() || !maxTurnsNode.canConvertToInt()) {
        throw reject("max_turns must be a positive integer");
      }
      maxTurns = maxTurnsNode.intValue();
      if (maxTurns < 1) {
        throw reject("max_turns must be a positive integer");
      }
    }
    UUID threadId = optionalUuid(node, "thread_id");
    return new SubagentTaskRequest(
        invocationId, parentThreadId, prompt, subagentType, maxTurns, threadId);
  }

  /** {@code thread_id} 若给出必须是规范 UUID 文本（大小写与格式逐字一致），用于继续既有子 Thread。 */
  private static UUID optionalUuid(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw reject(field + " must be a canonical UUID string");
    }
    String raw = value.stringValue();
    try {
      return Uuids.parseCanonical(raw, field);
    } catch (IllegalArgumentException error) {
      throw reject(field + " must be a canonical UUID string");
    }
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw reject(field + " is required");
    }
    String text = value.stringValue();
    if (field.equals("subagent_type")) {
      if (!text.equals(text.strip())) {
        throw reject("subagent_type must not contain surrounding whitespace");
      }
      return text;
    }
    return text.strip();
  }

  private ToolResult running(
      String callId, String subagentType, SubagentTaskAcceptance acceptance) {
    return statusResult(callId, subagentType, acceptance, "running", "Subagent is running.", false);
  }

  private ToolResult result(String callId, String subagentType, SubagentTaskResult taskResult) {
    ThreadJoinReceipt receipt = taskResult.receipt();
    if (receipt == null) {
      throw new IllegalArgumentException("synchronous task result has no Join receipt");
    }
    boolean error = receipt.outcome() != ThreadJoinOutcome.COMPLETED;
    String status =
        switch (receipt.outcome()) {
          case COMPLETED -> "completed";
          case ERROR -> "failed";
          case CANCELLED -> "cancelled";
        };
    String text =
        switch (receipt.outcome()) {
          case COMPLETED -> receipt.report();
          case ERROR -> failureText("Subagent failed", receipt);
          case CANCELLED -> failureText("Subagent cancelled", receipt);
        };
    if (!error && (text == null || text.isBlank())) {
      status = "failed";
      error = true;
      text = "Subagent ended without a final answer.";
    }
    return statusResult(callId, subagentType, taskResult.acceptance(), status, text, error);
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

  private static String failureText(String prefix, ThreadJoinReceipt receipt) {
    StringBuilder text = new StringBuilder(prefix).append(": ");
    text.append(
        receipt.error() == null || receipt.error().isBlank()
            ? "no reason provided"
            : receipt.error());
    if (receipt.partialResult() != null && !receipt.partialResult().isBlank()) {
      text.append("\nPartial result:\n").append(receipt.partialResult());
    }
    return text.toString();
  }

  private static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextResultContent(detail)), true, "{}");
  }

  private static String message(Throwable error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  private static TaskRejectedException reject(String message) {
    return new TaskRejectedException(message);
  }

  private static final class TaskRejectedException extends RuntimeException {
    private TaskRejectedException(String message) {
      super(message);
    }
  }
}
