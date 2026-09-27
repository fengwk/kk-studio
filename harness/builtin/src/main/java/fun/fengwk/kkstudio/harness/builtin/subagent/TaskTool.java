package fun.fengwk.kkstudio.harness.builtin.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHistoryRenderers;
import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 以普通 durable Harness Thread 运行隔离 Subagent 的内部 {@code task} 工具适配器。
 *
 * <p>本工具只有「持久接受」一个阶段：把 arguments 归一化为 {@link SubagentTaskRequest} 交给 {@link
 * SubagentRunner}，接受成功后立即用一次 tool_result 回执唯一 JSON {@code
 * {"thread_id":"...","status":"accepted"}}，然后 结束。它不再等待子任务终态，也不再有第二个 tool_result；子执行结清后由运行时把结果作为父
 * Thread 的一条独立消息交付。
 *
 * <p>参数被拒或 Runner 抛异常都收敛为错误结果，绝不抛出。
 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";
  public static final String RENDERER_KEY = "task";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          SubagentPrompts.taskToolDescription(),
          RENDERER_KEY,
          SubagentPrompts.taskInputSchema(),
          ToolSideEffect.NON_IDEMPOTENT,
          Duration.ZERO);

  private final SubagentRunner runner;
  private final ObjectMapper objectMapper;

  public TaskTool(SubagentRunner runner) {
    this(runner, new ObjectMapper());
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
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
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
      listener.onComplete(error(callId, rejected.getMessage()));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    try {
      SubagentTaskAcceptance acceptance = runner.accept(taskRequest);
      listener.onComplete(accepted(callId, taskRequest.subagentType(), acceptance));
    } catch (RuntimeException failure) {
      listener.onComplete(error(callId, message(failure)));
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private SubagentTaskRequest parseArguments(
      UUID invocationId, UUID parentThreadId, ToolCall call) {
    JsonNode value;
    try {
      value = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
    } catch (JsonProcessingException error) {
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
    if (!value.isTextual()) {
      throw reject(field + " must be a canonical UUID string");
    }
    String raw = value.textValue();
    UUID parsed;
    try {
      parsed = UUID.fromString(raw);
    } catch (IllegalArgumentException error) {
      throw reject(field + " must be a canonical UUID string");
    }
    if (!parsed.toString().equals(raw)) {
      throw reject(field + " must be a canonical UUID string");
    }
    return parsed;
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw reject(field + " is required");
    }
    String text = value.textValue();
    if (field.equals("subagent_type")) {
      if (!text.equals(text.strip())) {
        throw reject("subagent_type must not contain surrounding whitespace");
      }
      return text;
    }
    return text.strip();
  }

  private ToolResult accepted(
      String callId, String subagentType, SubagentTaskAcceptance acceptance) {
    String text = SubagentTaskMessages.accepted(acceptance.childThreadId());
    ObjectNode details = objectMapper.createObjectNode();
    details.put("kind", "task.accepted");
    // details 与即时回执表达同一份事实：thread_id / status 与 text 一致，其余是 UI 需要的会话与幂等元数据。
    details.put("thread_id", acceptance.childThreadId().toString());
    details.put("status", "accepted");
    details.put("session_id", acceptance.childSessionId().toString());
    details.put("subagent_type", subagentType);
    details.put("replayed", acceptance.replayed());
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, details.toString());
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
