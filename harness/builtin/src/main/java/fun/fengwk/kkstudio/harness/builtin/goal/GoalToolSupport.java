package fun.fengwk.kkstudio.harness.builtin.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Goal tools 共享的严格参数、state reduce 与结果构造。 */
final class GoalToolSupport {

  private static final GoalStateCodec CODEC = new GoalStateCodec();

  private GoalToolSupport() {}

  static ObjectNode arguments(ToolCall call, ToolDescriptor descriptor) {
    ToolArgumentsValidator.validate(call.argumentsJson(), descriptor.inputSchema());
    JsonNode value = CODEC.parse(call.argumentsJson(), "goal tool arguments");
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("goal tool arguments must be an object");
    }
    return object;
  }

  static Optional<GoalState> latest(BranchView branch) {
    return branch
        .latestCustomEntry(GoalFeature.CONTRIBUTOR_ID, GoalFeature.STATE_TYPE)
        .map(CODEC::decode);
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

  static DeclarativeToolResult stateChange(ToolCall call, GoalState state) {
    CustomEntryPayload payload =
        new CustomEntryPayload(
            GoalFeature.CONTRIBUTOR_ID.value(),
            GoalFeature.STATE_TYPE,
            GoalStateCodec.SCHEMA_VERSION,
            CODEC.encode(state));
    return new DeclarativeToolResult(
        success(call.id(), CODEC.envelope(state)), List.of(new AppendCustomEntry(payload)));
  }

  static DeclarativeToolResult success(ToolCall call, String text) {
    return DeclarativeToolResult.withoutIntents(success(call.id(), text));
  }

  static DeclarativeToolResult error(ToolCall call, RuntimeException error) {
    String message = error.getMessage();
    return DeclarativeToolResult.withoutIntents(
        ToolResult.error(
            call.id(),
            message == null || message.isBlank() ? error.getClass().getSimpleName() : message));
  }

  static String envelope(GoalState state) {
    return CODEC.envelope(state);
  }

  private static ToolResult success(String callId, String text) {
    return new ToolResult(callId, List.of(new TextToolContent(text)), false, "{}");
  }
}
