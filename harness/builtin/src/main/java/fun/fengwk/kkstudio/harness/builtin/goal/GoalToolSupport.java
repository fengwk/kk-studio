package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputValidator;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Goal tools 共享的严格参数解析、当前 Goal 读取与结果构造。 */
final class GoalToolSupport {

  private static final GoalProgressCodec CODEC = new GoalProgressCodec();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private GoalToolSupport() {}

  static ObjectNode arguments(ToolCall call, ToolDescriptor descriptor) {
    InputValidator.validate(call.argumentsJson(), descriptor.inputSchema());
    JsonNode value = CODEC.parse(call.argumentsJson(), "goal tool arguments");
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("goal tool arguments must be an object");
    }
    return object;
  }

  /**
   * 当前生效的用户 Goal 正文：唯一真值来自 branch settings，Agent 无权创改。没有 durable context 或用户未设置（或已清除）时 返回 empty。
   */
  static Optional<GoalSnapshot> goal(BranchView branch) {
    if (branch == null) {
      return Optional.empty();
    }
    Optional<GoalSnapshot> goal = branch.goal();
    return goal == null ? Optional.empty() : goal;
  }

  /**
   * 当前 Goal 的 Agent 进度声明：只有绑定当前 {@code goalId} 的报告才算数。用户设置新目标或清除目标后，指向旧 id 的历史报告 自动失效，不会被当成当前目标的进度。
   */
  static Optional<GoalProgress> progress(BranchView branch, UUID goalId) {
    Objects.requireNonNull(goalId, "goalId");
    if (branch == null) {
      return Optional.empty();
    }
    GoalProgress latest = null;
    for (var snapshot : branch.customEntries(GoalFeature.PROGRESS_TYPE)) {
      GoalProgress progress = CODEC.decode(snapshot);
      if (progress.goalId().equals(goalId)) {
        latest = progress;
      }
    }
    return Optional.ofNullable(latest);
  }

  static String requiredNonBlankText(ObjectNode arguments, String field) {
    JsonNode value = arguments.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " is required and must be a non-blank string");
    }
    return value.textValue().strip();
  }

  static Instant timestamp(Instant value) {
    return value.truncatedTo(ChronoUnit.MILLIS);
  }

  /** Agent 进度声明：只追加 goal-id-bound 的 {@code goal.progress} 快照，不改动 Goal 正文或业务状态。 */
  static ToolOutcome progressChange(ToolCall call, GoalProgress progress) {
    AppendCustomEntry entry =
        new AppendCustomEntry(
            GoalFeature.PROGRESS_TYPE, GoalProgressCodec.SCHEMA_VERSION, CODEC.encode(progress));
    return new ToolOutcome(successResult(call.id(), CODEC.encode(progress)), List.of(entry));
  }

  static ToolOutcome success(ToolCall call, String text) {
    return ToolOutcome.withoutEffects(successResult(call.id(), text));
  }

  static ToolOutcome error(ToolCall call, RuntimeException error) {
    String message = error.getMessage();
    ToolResult errorResult =
        ToolResult.error(
            call.id(),
            message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    return ToolOutcome.withoutEffects(errorResult);
  }

  /**
   * 读取模型：{@code {"goal":null}} 或 {@code {"goal":{"id","text","progress"}}}；progress 是 Agent
   * 声明而非系统验收。
   */
  static String envelope(GoalSnapshot goal, GoalProgress progress) {
    ObjectNode root = NODES.objectNode();
    if (goal == null) {
      root.putNull("goal");
      return write(root);
    }
    ObjectNode goalNode = NODES.objectNode();
    goalNode.put("id", goal.id().toString());
    goalNode.put("text", goal.text());
    if (progress == null) {
      goalNode.putNull("progress");
    } else {
      goalNode.set("progress", CODEC.encodeNode(progress));
    }
    root.set("goal", goalNode);
    return write(root);
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode goal JSON", error);
    }
  }

  private static ToolResult successResult(String callId, String text) {
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
  }
}
