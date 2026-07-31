package fun.fengwk.kkstudio.harness.runtime.goal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Shared argument parsing and immediate completion helpers for PLATFORM tools. */
final class PlatformToolSupport {
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private PlatformToolSupport() {}

  static ToolExecutionHandle complete(
      ToolExecutionRequest request,
      ToolExecutionListener listener,
      Function<ToolExecutionRequest, ToolResult> body) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    CompletedHandle handle = new CompletedHandle();
    try {
      if (request.context() == null) {
        throw new IllegalArgumentException("tool requires a durable execution context");
      }
      ToolResult result = body.apply(request);
      if (!handle.cancelled.get()) {
        listener.onComplete(result);
      }
    } catch (RuntimeException error) {
      if (!handle.cancelled.get()) {
        listener.onComplete(error(request.call().id(), message(error)));
      }
    }
    return handle;
  }

  static JsonNode requireObjectArgs(String argumentsJson) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("arguments must be a JSON object");
      }
      return root;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("arguments must be valid JSON", error);
    }
  }

  static void rejectUnknownFields(JsonNode root, Set<String> allowed) {
    Iterator<String> names = root.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException("unknown argument: " + name);
      }
    }
  }

  static String requireNonBlankString(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || !value.isTextual()) {
      throw new IllegalArgumentException(field + " is required and must be a string");
    }
    String text = value.textValue().trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return text;
  }

  static Long optionalPositiveLong(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException(field + " must be a positive integer when provided");
    }
    long parsed;
    try {
      BigDecimal decimal = value.decimalValue();
      parsed = decimal.longValueExact();
    } catch (ArithmeticException | NumberFormatException error) {
      throw new IllegalArgumentException(
          field + " must be a positive integer when provided", error);
    }
    if (parsed <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer when provided");
    }
    return parsed;
  }

  static ToolResult success(String callId, String text) {
    return new ToolResult(callId, List.of(new TextToolContent(text)), false, "{}", false);
  }

  static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextToolContent(detail)), true, "{}", false);
  }

  static String message(Throwable error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  static String formatGoalJson(ThreadGoal goal) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("goal", goal == null ? null : toMap(goal));
      return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("failed to serialize goal", error);
    }
  }

  static Map<String, Object> toMap(ThreadGoal goal) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("threadId", goal.threadId());
    map.put("objective", goal.objective());
    map.put("tokenBudget", goal.tokenBudget());
    map.put("status", goal.status().name());
    map.put("reason", goal.reason());
    map.put("createdAt", goal.createdAt().toString());
    map.put("updatedAt", goal.updatedAt().toString());
    return map;
  }

  static String formatGetGoal(ThreadGoal goal) {
    if (goal == null) {
      return "There is no current goal.\n\n" + formatGoalJson(null);
    }
    return String.join(
        "\n",
        "This is the current goal. Use it to advance the objective or check whether every objective requirement is fully satisfied.",
        "Use update_goal only when the current goal status and evidence satisfy its tool policy.",
        "",
        "Current goal:",
        formatGoalJson(goal));
  }

  static final class CompletedHandle implements ToolExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
