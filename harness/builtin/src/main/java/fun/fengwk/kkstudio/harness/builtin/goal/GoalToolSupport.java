package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputValidator;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Goal tools 共享的严格参数、state reduce 与结果构造。 */
final class GoalToolSupport {

  private static final GoalStateCodec CODEC = new GoalStateCodec();

  private GoalToolSupport() {}

  static ObjectNode arguments(ToolCall call, ToolDescriptor descriptor) {
    InputValidator.validate(call.argumentsJson(), descriptor.inputSchema());
    JsonNode value = CODEC.parse(call.argumentsJson(), "goal tool arguments");
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("goal tool arguments must be an object");
    }
    return object;
  }

  static Optional<GoalState> latest(BranchView branch) {
    if (branch == null) {
      return Optional.empty();
    }
    return branch.latestCustomEntry(GoalFeature.STATE_TYPE).map(CODEC::decode);
  }

  static String requiredNonBlankText(ObjectNode arguments, String field) {
    JsonNode value = arguments.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " is required and must be a non-blank string");
    }
    return value.textValue().strip();
  }

  static Long optionalPositiveLong(ObjectNode arguments, String field) {
    JsonNode value = arguments.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer when provided");
    }
    return value.longValue();
  }

  static Instant timestamp(Instant value) {
    return value.truncatedTo(ChronoUnit.MILLIS);
  }

  static ToolOutcome stateChange(ToolCall call, GoalState state) {
    AppendCustomEntry entry =
        new AppendCustomEntry(
            GoalFeature.STATE_TYPE, GoalStateCodec.SCHEMA_VERSION, CODEC.encode(state));
    return new ToolOutcome(successResult(call.id(), CODEC.envelope(state)), List.of(entry));
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

  static String envelope(GoalState state) {
    return CODEC.envelope(state);
  }

  private static ToolResult successResult(String callId, String text) {
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
  }
}
