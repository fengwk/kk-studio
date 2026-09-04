package fun.fengwk.kkstudio.harness.builtin.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 以普通 durable Harness Thread 运行隔离 Subagent 的内部 task 工具适配器。 */
public final class TaskTool implements Tool {

  public static final String NAME = "task";
  public static final String VERSION = "1";
  public static final String RENDERER_KEY = "task";
  public static final AgentToolId AGENT_TOOL_ID = BuiltinToolIds.TASK;

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
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
      return runner.run(taskRequest, listener);
    } catch (RuntimeException error) {
      listener.onComplete(error(callId, message(error)));
      return CompletedToolExecutionHandle.INSTANCE;
    }
  }

  private SubagentTaskRequest parseArguments(UUID invocationId, UUID threadId, ToolCall call) {
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
    JsonNode maxTurnsNode = node.get("maxTurns");
    if (maxTurnsNode != null && !maxTurnsNode.isNull()) {
      if (!maxTurnsNode.canConvertToInt() || !maxTurnsNode.isIntegralNumber()) {
        throw reject("maxTurns must be a positive integer");
      }
      maxTurns = maxTurnsNode.intValue();
      if (maxTurns < 1) {
        throw reject("maxTurns must be a positive integer");
      }
    }
    UUID sessionId = null;
    JsonNode sessionNode = node.get("session_id");
    if (sessionNode != null && !sessionNode.isNull()) {
      if (!sessionNode.isTextual()) {
        throw reject("session_id must be a canonical UUID string");
      }
      String raw = sessionNode.textValue();
      UUID parsed;
      try {
        parsed = UUID.fromString(raw);
      } catch (IllegalArgumentException error) {
        throw reject("session_id must be a canonical UUID string");
      }
      if (!parsed.toString().equals(raw)) {
        throw reject("session_id must be a canonical UUID string");
      }
      sessionId = parsed;
    }
    return new SubagentTaskRequest(
        invocationId, threadId, prompt, subagentType, maxTurns, sessionId);
  }

  private static String requiredText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw reject(field + " is required");
    }
    String text = value.textValue().strip();
    if (field.equals("subagent_type") && !text.equals(value.textValue())) {
      throw reject("subagent_type must not contain surrounding whitespace");
    }
    return text;
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
